package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Licensing: the buyer gets a key by email after paying on lihaila.com. Activation sends the key and
// a hashed device id to the server, which allows a few PCs per key and returns a signed token. The
// app then works offline and refreshes the token every couple of weeks, so a revoked or leaked key
// stops working. Skylark is GPL software: this protects the official build, nothing more.

var licenseAPI = "https://lihaila.com/skylark/api" // a var so tests can point it at a local server

const (
	buyURL     = "https://lihaila.com/skylark/buy"
	// Ed25519 public key of the license server (the private key never leaves the server).
	licensePublicKey = "m5bpkgeO4iZOmDeZ3Zm8Ay/Hohob4Z+9UI9CR0UNhjs="
	refreshAfter     = 14 * 24 * time.Hour
)

// devUnlock is set by `-ldflags "-X main.devUnlock=1"` for test builds handed to the developer.
var devUnlock = ""

type tokenPayload struct {
	Key    string `json:"key"`    // masked, for display
	Email  string `json:"email"`
	Device string `json:"device"` // hashed device id
	Iat    int64  `json:"iat"`
	Exp    int64  `json:"exp"`
}

type licenseFile struct {
	Key   string `json:"key"`
	Token string `json:"token"`
}

type LicenseStatus struct {
	Valid     bool
	Email     string
	MaskedKey string
}

type License struct {
	mu   sync.Mutex
	file string
	data licenseFile
}

func LoadLicense(dir string) *License {
	l := &License{file: filepath.Join(dir, "license.json")}
	if b, err := os.ReadFile(l.file); err == nil {
		_ = json.Unmarshal(b, &l.data)
	}
	return l
}

func (l *License) save() {
	b, _ := json.Marshal(l.data)
	_ = os.WriteFile(l.file, b, 0o600)
}

func verifyToken(token string) (*tokenPayload, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return nil, errors.New("bad token")
	}
	payload, err1 := base64.RawURLEncoding.DecodeString(parts[0])
	sig, err2 := base64.RawURLEncoding.DecodeString(parts[1])
	pub, err3 := base64.StdEncoding.DecodeString(licensePublicKey)
	if err1 != nil || err2 != nil || err3 != nil || !ed25519.Verify(pub, payload, sig) {
		return nil, errors.New("bad signature")
	}
	var p tokenPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, err
	}
	if p.Device != deviceID() {
		return nil, errors.New("other device")
	}
	if time.Now().Unix() > p.Exp {
		return nil, errors.New("expired")
	}
	return &p, nil
}

func (l *License) Status() LicenseStatus {
	if devUnlock == "1" {
		return LicenseStatus{Valid: true, Email: "developer build", MaskedKey: "DEV"}
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	p, err := verifyToken(l.data.Token)
	if err != nil {
		return LicenseStatus{}
	}
	return LicenseStatus{Valid: true, Email: p.Email, MaskedKey: p.Key}
}

type apiResponse struct {
	Token string `json:"token"`
	Error string `json:"error"`
}

func call(path, key string) (*apiResponse, int, error) {
	host, _ := os.Hostname()
	body, _ := json.Marshal(map[string]string{"key": key, "device": deviceID(), "name": host, "version": version})
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Post(licenseAPI+path, "application/json", bytes.NewReader(body))
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	var r apiResponse
	_ = json.Unmarshal(b, &r)
	return &r, resp.StatusCode, nil
}

// Activate checks the key with the server and stores the signed token.
func (l *License) Activate(key string) error {
	key = strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(key), " ", ""))
	if key == "" {
		return errors.New("invalidKey")
	}
	r, code, err := call("/activate", key)
	if err != nil {
		return errors.New("network")
	}
	if code != 200 || r.Token == "" {
		if r.Error != "" {
			return errors.New(r.Error) // invalidKey, tooManyDevices, revoked, region
		}
		return fmt.Errorf("HTTP %d", code)
	}
	if _, err := verifyToken(r.Token); err != nil {
		return errors.New("invalidKey")
	}
	l.mu.Lock()
	l.data = licenseFile{Key: key, Token: r.Token}
	l.save()
	l.mu.Unlock()
	return nil
}

// RefreshIfDue renews the token in the background. Network errors keep the current token (it stays
// valid until it expires), but a key the server has revoked is removed.
func (l *License) RefreshIfDue() {
	l.mu.Lock()
	data := l.data
	l.mu.Unlock()
	if data.Key == "" {
		return
	}
	if p, err := verifyToken(data.Token); err == nil && time.Since(time.Unix(p.Iat, 0)) < refreshAfter {
		return
	}
	r, code, err := call("/activate", data.Key)
	if err != nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	switch {
	case code == 200 && r.Token != "":
		l.data.Token = r.Token
		l.save()
	case code == 403 || code == 404:
		l.data = licenseFile{}
		l.save()
	}
}

// Deactivate frees this PC's slot on the server, then forgets the key. The server goes first: if it
// can't be reached the key stays here, otherwise the slot would stay used with no way to free it.
func (l *License) Deactivate() error {
	l.mu.Lock()
	key := l.data.Key
	l.mu.Unlock()
	if key != "" {
		_, code, err := call("/deactivate", key)
		if err != nil {
			return errors.New("network")
		}
		// 403/404: the key is revoked or unknown, so there is no slot left to free.
		if code != 200 && code != 403 && code != 404 {
			return fmt.Errorf("HTTP %d", code)
		}
	}
	l.mu.Lock()
	l.data = licenseFile{}
	l.save()
	l.mu.Unlock()
	return nil
}

var deviceOnce sync.Once
var deviceHash string

// deviceID is a hash of the Windows machine GUID (never sent in the clear).
func deviceID() string {
	deviceOnce.Do(func() {
		sum := sha256.Sum256([]byte("skylark:" + machineGUID()))
		deviceHash = hex.EncodeToString(sum[:16])
	})
	return deviceHash
}
