package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// BuildConfig turns the app's simple settings into a complete sing-box config. Port of the iOS
// ConfigBuilder (no ad blocking, no per-app proxy). rulesDir holds the .srs rule sets.
// clashAddr/clashSecret are the local API used for traffic stats ("" = none).
func BuildConfig(state AppState, home, rulesDir, clashAddr, clashSecret string) string {
	node := state.Selected()
	s := state.Settings
	mode := state.Mode
	if node == nil {
		mode = ModeDirect
	}
	usingRules := mode == ModeRule
	// In mainland China, "my country" is exactly what the bundled China lists cover.
	homeIsChina := home == "CN"
	bypassChina := s.BypassChina || (s.BypassHome && homeIsChina)
	homeGeoip := ""
	if s.BypassHome && home != "" && !homeIsChina {
		if f := GeoipFile(rulesDir, home); fileExists(f) {
			homeGeoip = f
		}
	}

	// outbounds
	var outbounds []obj
	if node != nil {
		var o obj
		if json.Unmarshal([]byte(node.Outbound), &o) == nil {
			o["tag"] = "proxy"
			outbounds = append(outbounds, o)
		}
	}
	outbounds = append(outbounds, obj{"type": "direct", "tag": "direct"})
	hasProxy := len(outbounds) > 1
	proxyOr := func() string {
		if hasProxy {
			return "proxy"
		}
		return "direct"
	}

	// rule sets
	ruleSets := []obj{}
	local := func(tag, path string) {
		ruleSets = append(ruleSets, obj{"type": "local", "tag": tag, "format": "binary", "path": path})
	}
	geoSet := map[string]bool{}
	for _, r := range state.Rules {
		if r.Type == GeoIP && !strings.EqualFold(r.Value, "LAN") {
			geoSet[strings.ToLower(r.Value)] = true
		}
	}
	customGeoip := make([]string, 0, len(geoSet))
	for c := range geoSet {
		customGeoip = append(customGeoip, c)
	}
	sort.Strings(customGeoip)
	if usingRules {
		if bypassChina || geoSet["cn"] {
			local("geosite-cn", filepath.Join(rulesDir, "geosite-cn.srs"))
			local("geoip-cn", filepath.Join(rulesDir, "geoip-cn.srs"))
		}
		if homeGeoip != "" {
			local("geoip-home", homeGeoip)
		}
		for _, code := range customGeoip {
			if code == "cn" {
				continue
			}
			ruleSets = append(ruleSets, obj{"type": "remote", "tag": "geoip-" + code, "format": "binary",
				"url":             "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-" + code + ".srs",
				"download_detour": proxyOr()})
		}
	}

	// route
	rules := []obj{{"action": "sniff"}, {"protocol": "dns", "action": "hijack-dns"}, {"ip_is_private": true, "outbound": "direct"}}
	dnsRules := []obj{}
	if usingRules {
		for _, r := range state.Rules {
			var rule obj
			switch r.Type {
			case DomainSuffix:
				rule = obj{"domain_suffix": []string{r.Value}}
			case Domain:
				rule = obj{"domain": []string{r.Value}}
			case DomainKeyword:
				rule = obj{"domain_keyword": []string{r.Value}}
			case IPCIDR:
				rule = obj{"ip_cidr": []string{r.Value}}
			case GeoIP:
				if strings.EqualFold(r.Value, "LAN") {
					rule = obj{"ip_is_private": true}
				} else {
					rule = obj{"rule_set": "geoip-" + strings.ToLower(r.Value)}
				}
			default:
				continue
			}
			switch r.Action {
			case ActionProxy:
				rule["outbound"] = proxyOr()
			case ActionDirect:
				rule["outbound"] = "direct"
			case ActionReject:
				rule["action"] = "reject"
			}
			rules = append(rules, rule)
			// Domains sent direct also resolve with the direct DNS so CDNs pick a nearby server.
			if r.Action == ActionDirect && r.Type != IPCIDR && r.Type != GeoIP {
				d := obj{}
				for k, v := range rule {
					if k != "outbound" {
						d[k] = v
					}
				}
				d["server"] = "dns-direct"
				dnsRules = append(dnsRules, d)
			}
		}
		if s.BypassHome && home != "" && !homeIsChina {
			tld := []string{"." + CCTLD(home)}
			rules = append(rules, obj{"domain_suffix": tld, "outbound": "direct"})
			if homeGeoip != "" {
				rules = append(rules, obj{"rule_set": "geoip-home", "outbound": "direct"})
			}
			dnsRules = append(dnsRules, obj{"domain_suffix": tld, "server": "dns-direct"})
		}
		if bypassChina {
			rules = append(rules, obj{"rule_set": []string{"geosite-cn", "geoip-cn"}, "outbound": "direct"})
			dnsRules = append(dnsRules, obj{"rule_set": "geosite-cn", "server": "dns-direct"})
		}
	}
	final := "proxy"
	if mode == ModeDirect {
		final = "direct"
	}
	route := obj{"rules": rules, "rule_set": ruleSets, "final": final,
		"auto_detect_interface": true, "default_domain_resolver": "dns-direct"}

	// dns
	dnsServers := []obj{{"type": "udp", "tag": "dns-direct", "server": s.DirectDNS}}
	if hasProxy {
		dnsServers = append(dnsServers, obj{"type": "https", "tag": "dns-remote", "server": s.RemoteDNS, "detour": "proxy"})
	}
	dnsFinal := "dns-remote"
	if mode == ModeDirect || !hasProxy {
		dnsFinal = "dns-direct"
	}
	strategy := "ipv4_only"
	if s.IPv6 {
		strategy = "prefer_ipv4"
	}
	dns := obj{"servers": dnsServers, "rules": dnsRules, "final": dnsFinal, "strategy": strategy}

	// tun: Windows uses the wintun driver embedded in sing-tun.
	address := []string{"172.19.0.1/30"}
	if s.IPv6 {
		address = append(address, "fdfe:dcba:9876::1/126")
	}
	tun := obj{"type": "tun", "tag": "tun-in", "interface_name": "Skylark", "address": address, "mtu": 9000,
		"auto_route": true, "strict_route": true, "stack": "gvisor"}

	experimental := obj{"cache_file": obj{"enabled": true}}
	if clashAddr != "" {
		experimental["clash_api"] = obj{"external_controller": clashAddr, "secret": clashSecret}
	}
	config := obj{
		"log": obj{"level": "info"},
		// Reality rejects clients whose clock is off; don't trust the PC clock.
		"ntp":          obj{"enabled": true, "server": "ntp.aliyun.com", "server_port": 123, "interval": "30m", "domain_resolver": "dns-direct"},
		"dns":          dns,
		"inbounds":     []obj{tun},
		"outbounds":    outbounds,
		"route":        route,
		"experimental": experimental,
	}
	data, _ := json.MarshalIndent(config, "", "  ")
	return string(data)
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }
