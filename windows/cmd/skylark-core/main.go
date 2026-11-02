// skylark-core runs the sing-box engine for Skylark on Linux. It is installed with
// cap_net_admin so it can create the TUN adapter, while the window runs as the normal user.
// Protocol: the config arrives on stdin terminated by a NUL line; it answers "OK" or
// "ERR <message>" on stdout and runs until stdin is closed.
package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/Lihaila/skylark/windows/engine"
)

func main() {
	r := bufio.NewReader(os.Stdin)
	var b strings.Builder
	for {
		line, err := r.ReadString('\n')
		if line == "\x00\n" || err != nil {
			break
		}
		b.WriteString(line)
	}
	var e engine.Engine
	if err := e.Start(b.String()); err != nil {
		fmt.Println("ERR " + strings.ReplaceAll(err.Error(), "\n", " "))
		os.Exit(1)
	}
	fmt.Println("OK")
	stop := make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, r); close(stop) }()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	select {
	case <-stop:
	case <-sig:
	}
	e.Stop()
}
