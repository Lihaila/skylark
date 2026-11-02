package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	_ "embed"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/Lihaila/skylark/windows/core"
	"github.com/Lihaila/skylark/windows/engine"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

//go:embed rules/geosite-cn.srs
var geositeCN []byte

//go:embed rules/geoip-cn.srs
var geoipCN []byte

const version = "1.0.0"

// App holds everything the window can call (methods are bound to window.go.main.App).
type App struct {
	ctx      context.Context
	dataDir  string
	rulesDir string
	store    *core.Store
	engine   engine.Runner
	license  *License

	mu          sync.Mutex
	connectedAt time.Time
	clashAddr   string
	clashSecret string
	lastErr     string
	stopStats   chan struct{}
	quitting    bool
}

func NewApp() *App {
	base, _ := os.UserConfigDir() // %APPDATA%
	dir := filepath.Join(base, "Skylark")
	a := &App{dataDir: dir, rulesDir: filepath.Join(dir, "rules")}
	_ = os.MkdirAll(a.rulesDir, 0o755)
	// sing-box writes its cache file relative to the working directory.
	_ = os.Chdir(dir)
	for name, data := range map[string][]byte{"geosite-cn.srs": geositeCN, "geoip-cn.srs": geoipCN} {
		p := filepath.Join(a.rulesDir, name)
		if _, err := os.Stat(p); err != nil {
			_ = os.WriteFile(p, data, 0o644)
		}
	}
	a.store = core.OpenStore(dir)
	a.engine = newRunner()
	a.license = LoadLicense(dir)
	return a
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	go a.license.RefreshIfDue()
	go func() {
		if home := core.EffectiveCountry(a.store.State().Settings); home != "" && home != "CN" {
			core.EnsureGeoip(a.rulesDir, home)
		}
	}()
}

func (a *App) shutdown(ctx context.Context) { a.Disconnect() }

// beforeClose keeps Skylark running (minimised) while connected; Quit() is the way out.
func (a *App) beforeClose(ctx context.Context) (prevent bool) {
	a.mu.Lock()
	quitting := a.quitting
	a.mu.Unlock()
	if quitting || !a.engine.Running() {
		return false
	}
	// Tiling Linux desktops (Hyprland, Sway…) have no minimise, so hide the window there;
	// launching Skylark again brings it back.
	if runtime.Environment(ctx).Platform == "linux" {
		runtime.WindowHide(ctx)
	} else {
		runtime.WindowMinimise(ctx)
	}
	return true
}

// secondInstance: launching Skylark again brings the existing window back.
func (a *App) secondInstance(options.SecondInstanceData) {
	if a.ctx == nil {
		return
	}
	runtime.WindowUnminimise(a.ctx)
	runtime.WindowShow(a.ctx)
	runtime.WindowSetAlwaysOnTop(a.ctx, true)
	runtime.WindowSetAlwaysOnTop(a.ctx, false)
}

// Quit disconnects and exits (Settings → Quit Skylark).
func (a *App) Quit() {
	a.mu.Lock()
	a.quitting = true
	a.mu.Unlock()
	a.Disconnect()
	runtime.Quit(a.ctx)
}

// ---------- state

type Info struct {
	Version       string `json:"version"`
	Country       string `json:"country"`       // effective home country
	DetectedCountry string `json:"detectedCountry"`
	Licensed      bool   `json:"licensed"`
	LicenseEmail  string `json:"licenseEmail"`
	LicenseKey    string `json:"licenseKey"`
	BuyURL        string `json:"buyUrl"`
}

func (a *App) GetState() core.AppState { return a.store.State() }

func (a *App) GetInfo() Info {
	s := a.store.State().Settings
	l := a.license.Status()
	return Info{Version: version, Country: core.EffectiveCountry(s), DetectedCountry: core.DetectCountry(),
		Licensed: l.Valid, LicenseEmail: l.Email, LicenseKey: l.MaskedKey, BuyURL: buyURL}
}

// ---------- servers

// ImportText adds every server found in pasted text. A bare http(s) URL is treated as a subscription.
func (a *App) ImportText(text string) (int, error) {
	text = strings.TrimSpace(text)
	if strings.HasPrefix(text, "http://") || strings.HasPrefix(text, "https://") {
		if !strings.ContainsAny(text, "\n ") {
			return a.AddSubscription("", text)
		}
	}
	nodes := core.ParseText(text)
	if len(nodes) == 0 {
		return 0, errors.New("noServers")
	}
	a.store.AddNodes(nodes)
	a.reloadIfRunning()
	return len(nodes), nil
}

func (a *App) PasteFromClipboard() (int, error) {
	text, err := runtime.ClipboardGetText(a.ctx)
	if err != nil || strings.TrimSpace(text) == "" {
		return 0, errors.New("clipboardEmpty")
	}
	return a.ImportText(text)
}

func (a *App) AddSubscription(name, url string) (int, error) {
	nodes, err := core.FetchSubscription(url)
	if err != nil {
		return 0, err
	}
	if name == "" {
		name = hostOf(url)
	}
	a.store.ReplaceSubNodes(core.Subscription{ID: core.NewID(), Name: name, URL: strings.TrimSpace(url)}, nodes)
	a.reloadIfRunning()
	return len(nodes), nil
}

func (a *App) UpdateSubscription(id string) (int, error) {
	for _, sub := range a.store.State().Subs {
		if sub.ID == id {
			nodes, err := core.FetchSubscription(sub.URL)
			if err != nil {
				return 0, err
			}
			a.store.ReplaceSubNodes(sub, nodes)
			a.reloadIfRunning()
			return len(nodes), nil
		}
	}
	return 0, errors.New("not found")
}

func (a *App) DeleteSubscription(id string) core.AppState {
	st := a.store.DeleteSub(id)
	a.reloadIfRunning()
	return st
}

func (a *App) SelectNode(id string) core.AppState {
	st := a.store.Update(func(s *core.AppState) { s.SelectedID = id })
	a.reloadIfRunning()
	return st
}

func (a *App) DeleteNode(id string) core.AppState {
	st := a.store.DeleteNode(id)
	a.reloadIfRunning()
	return st
}

// Ping returns TCP latency in ms per node id (-1 = timeout).
func (a *App) Ping(ids []string) map[string]int {
	nodes := map[string]core.Node{}
	for _, n := range a.store.State().Nodes {
		nodes[n.ID] = n
	}
	out := map[string]int{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 16)
	for _, id := range ids {
		n, ok := nodes[id]
		if !ok {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			ms := core.TCPPing(n)
			<-sem
			mu.Lock()
			out[n.ID] = ms
			mu.Unlock()
		}()
	}
	wg.Wait()
	return out
}

// ---------- mode, rules, settings

func (a *App) SetMode(mode string) core.AppState {
	st := a.store.Update(func(s *core.AppState) { s.Mode = core.Mode(mode) })
	a.reloadIfRunning()
	return st
}

func (a *App) AddRule(typ, value, action string) core.AppState {
	value = strings.TrimSpace(value)
	if value == "" {
		return a.store.State()
	}
	st := a.store.Update(func(s *core.AppState) {
		s.Rules = append([]core.Rule{{ID: core.NewID(), Type: core.RuleType(typ), Value: value, Action: core.RuleAction(action)}}, s.Rules...)
	})
	a.reloadIfRunning()
	return st
}

func (a *App) DeleteRule(id string) core.AppState {
	st := a.store.Update(func(s *core.AppState) {
		out := []core.Rule{}
		for _, r := range s.Rules {
			if r.ID != id {
				out = append(out, r)
			}
		}
		s.Rules = out
	})
	a.reloadIfRunning()
	return st
}

func (a *App) SaveSettings(settings core.Settings) core.AppState {
	st := a.store.Update(func(s *core.AppState) { s.Settings = settings })
	go func() {
		if home := core.EffectiveCountry(settings); home != "" && home != "CN" {
			if core.EnsureGeoip(a.rulesDir, home) {
				a.reloadIfRunning()
			}
		}
	}()
	a.reloadIfRunning()
	return st
}

// ---------- connection

type Status struct {
	Connected bool   `json:"connected"`
	Since     int64  `json:"since"` // unix ms
	Up        int64  `json:"up"`    // bytes/s
	Down      int64  `json:"down"`
	UpTotal   int64  `json:"upTotal"`
	DownTotal int64  `json:"downTotal"`
	Error     string `json:"error"`
}

func (a *App) config() string {
	st := a.store.State()
	return core.BuildConfig(st, core.EffectiveCountry(st.Settings), a.rulesDir, a.clashAddr, a.clashSecret)
}

func (a *App) Connect() error {
	if !a.license.Status().Valid {
		return errors.New("notActivated")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.clashAddr == "" {
		a.clashAddr = freeLocalAddr()
		b := make([]byte, 16)
		_, _ = rand.Read(b)
		a.clashSecret = hex.EncodeToString(b)
	}
	if err := a.engine.Start(a.config()); err != nil {
		a.lastErr = err.Error()
		return err
	}
	a.lastErr = ""
	a.connectedAt = time.Now()
	if a.stopStats == nil {
		a.stopStats = make(chan struct{})
		go a.statsLoop(a.stopStats)
	}
	return nil
}

func (a *App) Disconnect() {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.engine.Stop()
	if a.stopStats != nil {
		close(a.stopStats)
		a.stopStats = nil
	}
	a.connectedAt = time.Time{}
	if a.ctx != nil {
		runtime.EventsEmit(a.ctx, "status", Status{})
	}
}

func (a *App) GetStatus() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := Status{Connected: a.engine.Running(), Error: a.lastErr}
	if s.Connected {
		s.Since = a.connectedAt.UnixMilli()
	}
	return s
}

func (a *App) reloadIfRunning() {
	if !a.engine.Running() {
		return
	}
	go func() {
		a.mu.Lock()
		defer a.mu.Unlock()
		if err := a.engine.Start(a.config()); err != nil {
			a.lastErr = err.Error()
			runtime.EventsEmit(a.ctx, "status", Status{Error: a.lastErr})
		}
	}()
}

func (a *App) statsLoop(stop chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	var lastUp, lastDown int64
	for {
		select {
		case <-stop:
			return
		case <-t.C:
		}
		a.mu.Lock()
		addr, secret, since := a.clashAddr, a.clashSecret, a.connectedAt
		a.mu.Unlock()
		up, down, err := engine.Traffic(addr, secret)
		if err != nil {
			continue
		}
		s := Status{Connected: true, Since: since.UnixMilli(), UpTotal: up, DownTotal: down}
		if lastUp > 0 || lastDown > 0 {
			s.Up, s.Down = max(up-lastUp, 0), max(down-lastDown, 0)
		}
		lastUp, lastDown = up, down
		runtime.EventsEmit(a.ctx, "status", s)
	}
}

// ---------- license

func (a *App) Activate(key string) (Info, error) {
	if err := a.license.Activate(strings.TrimSpace(key)); err != nil {
		return a.GetInfo(), err
	}
	return a.GetInfo(), nil
}

// Deactivate reports failure so the window can say so. It calls the server before disconnecting,
// because from some networks the server is only reachable through the tunnel.
func (a *App) Deactivate() (Info, error) {
	if err := a.license.Deactivate(); err != nil {
		return a.GetInfo(), err
	}
	a.Disconnect()
	return a.GetInfo(), nil
}

func (a *App) OpenURL(url string) { runtime.BrowserOpenURL(a.ctx, url) }

// ---------- helpers

func freeLocalAddr() string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "127.0.0.1:19090"
	}
	defer l.Close()
	return l.Addr().String()
}

func hostOf(u string) string {
	s := strings.TrimPrefix(strings.TrimPrefix(u, "https://"), "http://")
	if i := strings.IndexAny(s, "/?#"); i >= 0 {
		s = s[:i]
	}
	if s == "" {
		return fmt.Sprintf("Subscription %s", time.Now().Format("01-02"))
	}
	return s
}
