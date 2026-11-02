package main

import (
	"os"
	"strings"
)

// machineGUID on Linux is the systemd/dbus machine id.
func machineGUID() string {
	for _, p := range []string{"/etc/machine-id", "/var/lib/dbus/machine-id"} {
		if b, err := os.ReadFile(p); err == nil {
			if id := strings.TrimSpace(string(b)); id != "" {
				return id
			}
		}
	}
	h, _ := os.Hostname()
	return h
}
