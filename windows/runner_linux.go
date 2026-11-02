package main

import "github.com/Lihaila/skylark/windows/engine"

// On Linux the engine runs in skylark-core, which holds the network capability.
func newRunner() engine.Runner { return &engine.Process{} }
