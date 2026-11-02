package engine

import (
	"testing"

	"github.com/Lihaila/skylark/windows/core"
)

// The generated configs must be accepted by the real sing-box, for every mode.
func TestGeneratedConfigsAreValid(t *testing.T) {
	link := "vless://11111111-2222-3333-4444-555555555555@203.0.113.9:443?flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=HUlxnxDxb-gHyeSjMIjIIt3vvK3AeDrw--0Vwmhl1Jo&sid=0123abcd&type=tcp#Reality"
	nodes := core.ParseText(link)
	if len(nodes) != 1 {
		t.Fatal("parse failed")
	}
	dir := t.TempDir()
	for _, mode := range []core.Mode{core.ModeRule, core.ModeGlobal, core.ModeDirect} {
		s := core.DefaultState()
		s.Nodes = nodes
		s.Mode = mode
		s.Settings.BypassChina = false
		s.Rules = []core.Rule{{ID: "1", Type: core.DomainSuffix, Value: "example.com", Action: core.ActionDirect},
			{ID: "2", Type: core.GeoIP, Value: "LAN", Action: core.ActionDirect}}
		cfg := core.BuildConfig(s, "JP", dir, "127.0.0.1:9090", "secret")
		if err := Check(cfg); err != nil {
			t.Errorf("mode %s: %v\n%s", mode, err, cfg)
		}
	}
}
