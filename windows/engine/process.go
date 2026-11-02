package engine

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Process runs sing-box in the skylark-core helper next to the app binary. The helper reads the
// config on stdin, prints "OK" or "ERR <message>", and keeps running until stdin closes.
type Process struct {
	mu    sync.Mutex
	cmd   *exec.Cmd
	stdin io.WriteCloser
	done  chan struct{}
}

func corePath() string {
	exe, _ := os.Executable()
	exe, _ = filepath.EvalSymlinks(exe)
	return filepath.Join(filepath.Dir(exe), "skylark-core")
}

func (p *Process) Running() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.done == nil {
		return false
	}
	select {
	case <-p.done:
		return false
	default:
		return true
	}
}

func (p *Process) Start(config string) error {
	p.Stop()
	err := p.start(config, false)
	// Not installed with the network capability (e.g. run from the unpacked folder): ask for the
	// password once through polkit instead.
	if err != nil && strings.Contains(strings.ToLower(err.Error()), "permi") {
		if _, lookErr := exec.LookPath("pkexec"); lookErr == nil {
			if err2 := p.start(config, true); err2 == nil {
				return nil
			}
		}
		return errors.New("needsInstall")
	}
	return err
}

func (p *Process) start(config string, elevated bool) error {
	core := corePath()
	var cmd *exec.Cmd
	if elevated {
		cmd = exec.Command("pkexec", core)
	} else {
		cmd = exec.Command(core)
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	if _, err := io.WriteString(stdin, config+"\n\x00\n"); err != nil {
		_ = cmd.Process.Kill()
		return err
	}
	reply := make(chan string, 1)
	go func() {
		line, _ := bufio.NewReader(stdout).ReadString('\n')
		reply <- strings.TrimSpace(line)
		_, _ = io.Copy(io.Discard, stdout)
	}()
	done := make(chan struct{})
	go func() { _ = cmd.Wait(); close(done) }()
	select {
	case line := <-reply:
		if line == "OK" {
			p.mu.Lock()
			p.cmd, p.stdin, p.done = cmd, stdin, done
			p.mu.Unlock()
			return nil
		}
		_ = cmd.Process.Kill()
		if line == "" {
			return errors.New("skylark-core exited")
		}
		return errors.New(strings.TrimPrefix(line, "ERR "))
	case <-time.After(60 * time.Second): // pkexec waits for the password dialog
		_ = cmd.Process.Kill()
		return fmt.Errorf("skylark-core did not start")
	}
}

func (p *Process) Stop() {
	p.mu.Lock()
	cmd, stdin, done := p.cmd, p.stdin, p.done
	p.cmd, p.stdin, p.done = nil, nil, nil
	p.mu.Unlock()
	if cmd == nil {
		return
	}
	_ = stdin.Close() // the helper shuts down cleanly when stdin closes
	select {
	case <-done:
	case <-time.After(6 * time.Second):
		_ = cmd.Process.Kill()
	}
}
