package engine

// Runner starts and stops sing-box. Windows runs it in-process (Engine); Linux runs it in the
// separate skylark-core program (Process), which alone holds the network capability.
type Runner interface {
	Start(config string) error
	Stop()
	Running() bool
}
