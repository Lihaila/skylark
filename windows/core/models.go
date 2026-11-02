package core

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"time"
)

// Node is a proxy server. Outbound is a sing-box outbound object (JSON, without "tag").
type Node struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Outbound string `json:"outbound"`
	SubID    string `json:"subId,omitempty"`
}

func (n Node) fields() map[string]any {
	var m map[string]any
	_ = json.Unmarshal([]byte(n.Outbound), &m)
	return m
}

func (n Node) Type() string   { s, _ := n.fields()["type"].(string); return s }
func (n Node) Server() string { s, _ := n.fields()["server"].(string); return s }
func (n Node) Port() int      { f, _ := n.fields()["server_port"].(float64); return int(f) }

type Subscription struct {
	ID        string     `json:"id"`
	Name      string     `json:"name"`
	URL       string     `json:"url"`
	UpdatedAt *time.Time `json:"updatedAt,omitempty"`
}

type Mode string

const (
	ModeRule   Mode = "RULE"
	ModeGlobal Mode = "GLOBAL"
	ModeDirect Mode = "DIRECT"
)

type RuleType string

const (
	DomainSuffix  RuleType = "DOMAIN_SUFFIX"
	Domain        RuleType = "DOMAIN"
	DomainKeyword RuleType = "DOMAIN_KEYWORD"
	IPCIDR        RuleType = "IP_CIDR"
	GeoIP         RuleType = "GEOIP"
)

type RuleAction string

const (
	ActionProxy  RuleAction = "PROXY"
	ActionDirect RuleAction = "DIRECT"
	ActionReject RuleAction = "REJECT"
)

type Rule struct {
	ID     string     `json:"id"`
	Type   RuleType   `json:"type"`
	Value  string     `json:"value"`
	Action RuleAction `json:"action"`
}

type Settings struct {
	// Smart mode sends sites in the user's own country direct.
	BypassHome bool `json:"bypassHome"`
	// ISO country code; empty means auto-detect.
	HomeCountry string `json:"homeCountry"`
	BypassChina bool   `json:"bypassChina"`
	IPv6        bool   `json:"ipv6"`
	RemoteDNS   string `json:"remoteDns"`
	DirectDNS   string `json:"directDns"`
	Language    string `json:"language"` // "", "en", "zh-Hans", "zh-Hant"
}

type AppState struct {
	Nodes      []Node         `json:"nodes"`
	Subs       []Subscription `json:"subs"`
	SelectedID string         `json:"selectedId,omitempty"`
	Mode       Mode           `json:"mode"`
	Rules      []Rule         `json:"rules"`
	Settings   Settings       `json:"settings"`
}

func DefaultState() AppState {
	return AppState{
		Nodes: []Node{}, Subs: []Subscription{}, Rules: []Rule{}, Mode: ModeRule,
		Settings: Settings{BypassHome: true, RemoteDNS: "1.1.1.1", DirectDNS: "1.1.1.1"},
	}
}

func (s AppState) Selected() *Node {
	for i := range s.Nodes {
		if s.Nodes[i].ID == s.SelectedID {
			return &s.Nodes[i]
		}
	}
	if len(s.Nodes) > 0 {
		return &s.Nodes[0]
	}
	return nil
}

func NewID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
