// Package engine runs sing-box in-process. On Windows the TUN inbound uses the wintun driver that
// sing-tun embeds, so the whole app is one .exe (it must run as administrator to create the adapter).
package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
)

func parse(ctx context.Context, config string) (option.Options, error) {
	return sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(config))
}

// Check reports whether sing-box accepts the config, without starting anything.
func Check(config string) error {
	ctx := include.Context(context.Background())
	opts, err := parse(ctx, config)
	if err != nil {
		return err
	}
	_, err = box.New(box.Options{Context: ctx, Options: opts})
	return err
}

type Engine struct {
	mu       sync.Mutex
	instance *box.Box
	cancel   context.CancelFunc
}

func (e *Engine) Running() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.instance != nil
}

// Start (or restart) sing-box with config.
func (e *Engine) Start(config string) error {
	e.Stop()
	e.mu.Lock()
	defer e.mu.Unlock()
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	opts, err := parse(ctx, config)
	if err != nil {
		cancel()
		return err
	}
	instance, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		cancel()
		return err
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		cancel()
		return err
	}
	e.instance, e.cancel = instance, cancel
	return nil
}

func (e *Engine) Stop() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.instance != nil {
		done := make(chan struct{})
		go func() { _ = e.instance.Close(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
		e.instance = nil
	}
	if e.cancel != nil {
		e.cancel()
		e.cancel = nil
	}
}

// Traffic reads total bytes up/down from the local Clash API.
func Traffic(addr, secret string) (up, down int64, err error) {
	req, _ := http.NewRequest("GET", "http://"+addr+"/connections", nil)
	req.Header.Set("Authorization", "Bearer "+secret)
	resp, err := (&http.Client{Timeout: 2 * time.Second}).Do(req)
	if err != nil {
		return 0, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return 0, 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var v struct {
		UploadTotal   int64 `json:"uploadTotal"`
		DownloadTotal int64 `json:"downloadTotal"`
	}
	err = json.NewDecoder(resp.Body).Decode(&v)
	return v.UploadTotal, v.DownloadTotal, err
}
