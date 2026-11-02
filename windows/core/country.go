package core

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// EffectiveCountry is the user's home country for Smart mode: the setting, else the system region.
func EffectiveCountry(s Settings) string {
	if s.HomeCountry != "" {
		return strings.ToUpper(s.HomeCountry)
	}
	return DetectCountry()
}

// CCTLD is the country-code top-level domain: almost always the ISO code.
func CCTLD(code string) string {
	if strings.EqualFold(code, "GB") {
		return "uk"
	}
	return strings.ToLower(code)
}

func GeoipFile(rulesDir, code string) string {
	return filepath.Join(rulesDir, "geoip-"+strings.ToLower(code)+".srs")
}

// EnsureGeoip downloads the country's IP list once. GitHub first, then jsDelivr mirrors for
// networks that block GitHub.
func EnsureGeoip(rulesDir, code string) bool {
	if code == "" {
		return false
	}
	file := GeoipFile(rulesDir, code)
	if fileExists(file) {
		return true
	}
	_ = os.MkdirAll(rulesDir, 0o755)
	name := "geoip-" + strings.ToLower(code) + ".srs"
	urls := []string{
		"https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/" + name,
		"https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/" + name,
		"https://fastly.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/" + name,
		"https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/" + name,
	}
	client := &http.Client{Timeout: 10 * time.Second}
	for _, u := range urls {
		resp, err := client.Get(u)
		if err != nil {
			continue
		}
		data, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		// Every binary rule set starts with "SRS".
		if err != nil || resp.StatusCode != 200 || !bytes.HasPrefix(data, []byte("SRS")) {
			continue
		}
		if os.WriteFile(file+".tmp", data, 0o644) == nil && os.Rename(file+".tmp", file) == nil {
			return true
		}
	}
	return false
}

// TCPPing is the TCP handshake time to the server in ms, or -1 if unreachable within 3 s.
func TCPPing(n Node) int {
	if n.Port() <= 0 {
		return -1
	}
	start := time.Now()
	d := net.Dialer{Timeout: 3 * time.Second}
	conn, err := d.DialContext(context.Background(), "tcp", net.JoinHostPort(n.Server(), fmt.Sprint(n.Port())))
	if err != nil {
		return -1
	}
	conn.Close()
	return int(time.Since(start).Milliseconds())
}

// FetchSubscription downloads a subscription URL and parses its servers.
func FetchSubscription(url string) ([]Node, error) {
	req, err := http.NewRequest("GET", strings.TrimSpace(url), nil)
	if err != nil {
		return nil, fmt.Errorf("invalid URL")
	}
	req.Header.Set("User-Agent", "Skylark/1.0 (sing-box 1.14; v2ray links)")
	resp, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 20<<20))
	if err != nil {
		return nil, err
	}
	nodes := ParseText(string(body))
	if len(nodes) == 0 {
		return nil, fmt.Errorf("no servers found in subscription")
	}
	return nodes, nil
}
