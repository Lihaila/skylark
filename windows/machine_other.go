//go:build !windows

package main

import "os"

// machineGUID on non-Windows builds (development only).
func machineGUID() string { h, _ := os.Hostname(); return h }
