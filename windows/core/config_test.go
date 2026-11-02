package core

import (
	"encoding/json"
	"reflect"
	"testing"
)

// Routing decisions in the generated sing-box config (same cases as iOS).

var testNode = Node{ID: "n1", Name: "LA", Outbound: `{"type":"trojan","server":"t.example","server_port":443,"password":"x","tls":{"enabled":true}}`}

func build(t *testing.T, change func(*AppState)) map[string]any {
	t.Helper()
	s := DefaultState()
	s.Nodes = []Node{testNode}
	s.SelectedID = testNode.ID
	s.Settings.HomeCountry = "JP"
	if change != nil {
		change(&s)
	}
	var c map[string]any
	if err := json.Unmarshal([]byte(BuildConfig(s, EffectiveCountry(s.Settings), t.TempDir(), "", "")), &c); err != nil {
		t.Fatal(err)
	}
	return c
}

func route(c map[string]any) map[string]any { return m(c["route"]) }
func rules(c map[string]any) []map[string]any {
	out := []map[string]any{}
	for _, r := range route(c)["rules"].([]any) {
		out = append(out, m(r))
	}
	return out
}
func strs(v any) []string {
	a, _ := v.([]any)
	out := []string{}
	for _, x := range a {
		s, _ := x.(string)
		out = append(out, s)
	}
	return out
}
func hasRule(c map[string]any, f func(map[string]any) bool) int {
	for i, r := range rules(c) {
		if f(r) {
			return i
		}
	}
	return -1
}

func TestSmartModeSendsHomeCountryDirect(t *testing.T) {
	c := build(t, nil)
	eq(t, route(c)["final"], "proxy")
	if hasRule(c, func(r map[string]any) bool {
		return reflect.DeepEqual(strs(r["domain_suffix"]), []string{".jp"}) && r["outbound"] == "direct"
	}) < 0 {
		t.Error("missing .jp direct rule")
	}
	first := m(c["outbounds"].([]any)[0])
	eq(t, first["tag"], "proxy")
	eq(t, first["type"], "trojan")
}

func TestUnitedKingdomUsesUkDomain(t *testing.T) {
	c := build(t, func(s *AppState) { s.Settings.HomeCountry = "GB" })
	if hasRule(c, func(r map[string]any) bool { return reflect.DeepEqual(strs(r["domain_suffix"]), []string{".uk"}) }) < 0 {
		t.Error("missing .uk rule")
	}
}

func TestChinaBypassAddsBundledRuleSets(t *testing.T) {
	c := build(t, func(s *AppState) { s.Settings.BypassChina = true })
	tags := map[string]bool{}
	for _, x := range route(c)["rule_set"].([]any) {
		tags[m(x)["tag"].(string)] = true
	}
	if !tags["geosite-cn"] || !tags["geoip-cn"] {
		t.Error("missing China rule sets")
	}
}

func TestGlobalModeHasNoCountryRules(t *testing.T) {
	c := build(t, func(s *AppState) { s.Mode = ModeGlobal })
	eq(t, route(c)["final"], "proxy")
	if hasRule(c, func(r map[string]any) bool { return r["domain_suffix"] != nil }) >= 0 {
		t.Error("global mode must not have country rules")
	}
}

func TestDirectModeSendsEverythingDirect(t *testing.T) {
	c := build(t, func(s *AppState) { s.Mode = ModeDirect })
	eq(t, route(c)["final"], "direct")
	eq(t, m(c["dns"])["final"], "dns-direct")
}

func TestNoServerFallsBackToDirect(t *testing.T) {
	c := build(t, func(s *AppState) { s.Nodes = nil; s.SelectedID = "" })
	eq(t, route(c)["final"], "direct")
	var tags []string
	for _, x := range m(c["dns"])["servers"].([]any) {
		tags = append(tags, m(x)["tag"].(string))
	}
	eq(t, tags, []string{"dns-direct"})
}

func TestCustomRulesKeepOrderAndActions(t *testing.T) {
	c := build(t, func(s *AppState) {
		s.Rules = []Rule{{ID: "1", Type: DomainSuffix, Value: "bank.example", Action: ActionDirect},
			{ID: "2", Type: DomainKeyword, Value: "ads", Action: ActionReject},
			{ID: "3", Type: IPCIDR, Value: "8.8.8.0/24", Action: ActionProxy}}
	})
	i1 := hasRule(c, func(r map[string]any) bool { return reflect.DeepEqual(strs(r["domain_suffix"]), []string{"bank.example"}) })
	i2 := hasRule(c, func(r map[string]any) bool { return reflect.DeepEqual(strs(r["domain_keyword"]), []string{"ads"}) })
	i3 := hasRule(c, func(r map[string]any) bool { return reflect.DeepEqual(strs(r["ip_cidr"]), []string{"8.8.8.0/24"}) })
	if !(i1 >= 0 && i1 < i2 && i2 < i3) {
		t.Fatalf("order wrong: %d %d %d", i1, i2, i3)
	}
	r := rules(c)
	eq(t, r[i1]["outbound"], "direct")
	eq(t, r[i2]["action"], "reject")
	eq(t, r[i3]["outbound"], "proxy")
	found := false
	for _, x := range m(c["dns"])["rules"].([]any) {
		d := m(x)
		if reflect.DeepEqual(strs(d["domain_suffix"]), []string{"bank.example"}) && d["server"] == "dns-direct" {
			found = true
		}
	}
	if !found {
		t.Error("direct domains must resolve via the direct DNS")
	}
}

func TestTunAndClockSettings(t *testing.T) {
	c := build(t, nil)
	tun := m(c["inbounds"].([]any)[0])
	eq(t, tun["stack"], "gvisor")
	eq(t, tun["mtu"], 9000)
	eq(t, strs(tun["address"]), []string{"172.19.0.1/30"})
	eq(t, m(c["dns"])["strategy"], "ipv4_only")
	eq(t, m(c["ntp"])["enabled"], true)
}

func TestIPv6AddsAddressAndPrefersV4(t *testing.T) {
	c := build(t, func(s *AppState) { s.Settings.IPv6 = true })
	eq(t, len(strs(m(c["inbounds"].([]any)[0])["address"])), 2)
	eq(t, m(c["dns"])["strategy"], "prefer_ipv4")
}
