package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func serverReturning(t *testing.T, code int) (*int, func()) {
	t.Helper()
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/deactivate") {
			calls++
		}
		w.WriteHeader(code)
		_, _ = w.Write([]byte(`{}`))
	}))
	old := licenseAPI
	licenseAPI = srv.URL
	return &calls, func() { licenseAPI = old; srv.Close() }
}

func activated(t *testing.T) (*License, string) {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "license.json"), []byte(`{"key":"SKY-A","token":"t"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	return LoadLicense(dir), filepath.Join(dir, "license.json")
}

func TestDeactivateFreesSlotThenForgetsKey(t *testing.T) {
	for _, code := range []int{200, 403, 404} {
		calls, done := serverReturning(t, code)
		l, file := activated(t)
		if err := l.Deactivate(); err != nil {
			t.Errorf("HTTP %d: unexpected error %v", code, err)
		}
		if *calls != 1 {
			t.Errorf("HTTP %d: server called %d times", code, *calls)
		}
		b, _ := os.ReadFile(file)
		if strings.Contains(string(b), "SKY-A") {
			t.Errorf("HTTP %d: key should be forgotten", code)
		}
		done()
	}
}

func TestDeactivateKeepsKeyWhenServerFails(t *testing.T) {
	_, done := serverReturning(t, 500)
	l, file := activated(t)
	if err := l.Deactivate(); err == nil {
		t.Error("expected an error on HTTP 500")
	}
	done() // server now closed: a network failure
	if err := func() error { licenseAPI = "http://127.0.0.1:1"; defer func() { licenseAPI = "" }(); return l.Deactivate() }(); err == nil || err.Error() != "network" {
		t.Errorf("expected network error, got %v", err)
	}
	if b, _ := os.ReadFile(file); !strings.Contains(string(b), "SKY-A") {
		t.Error("key must stay when the slot could not be freed")
	}
}
