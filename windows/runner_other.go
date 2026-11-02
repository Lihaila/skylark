//go:build !linux

package main

import "github.com/Lihaila/skylark/windows/engine"

// Windows (and macOS dev builds) run sing-box in-process.
func newRunner() engine.Runner { return &engine.Engine{} }
