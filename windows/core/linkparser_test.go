package core

import (
	"encoding/base64"
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

// Real-world link shapes for every supported scheme and subscription format (same cases as iOS).

func one(t *testing.T, text string) (Node, map[string]any) {
	t.Helper()
	nodes := ParseText(text)
	if len(nodes) != 1 {
		t.Fatalf("expected one node from %q, got %d", text, len(nodes))
	}
	return nodes[0], nodes[0].fields()
}

func m(v any) map[string]any { x, _ := v.(map[string]any); return x }

func eq(t *testing.T, got, want any) {
	t.Helper()
	if f, ok := got.(float64); ok {
		got = int(f)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %#v, want %#v", got, want)
	}
}

func TestShadowsocksSIP002(t *testing.T) {
	n, o := one(t, "ss://YWVzLTI1Ni1nY206c2VjcmV0@1.2.3.4:8388#My%20SS")
	eq(t, n.Name, "My SS")
	eq(t, o["type"], "shadowsocks")
	eq(t, o["server"], "1.2.3.4")
	eq(t, o["server_port"], 8388)
	eq(t, o["method"], "aes-256-gcm")
	eq(t, o["password"], "secret")
}

func TestShadowsocksLegacyFullBase64(t *testing.T) {
	b64 := base64.StdEncoding.EncodeToString([]byte("chacha20-ietf-poly1305:pass@example.com:443"))
	n, o := one(t, "ss://"+b64+"#Legacy")
	eq(t, n.Name, "Legacy")
	eq(t, o["server"], "example.com")
	eq(t, o["server_port"], 443)
	eq(t, o["method"], "chacha20-ietf-poly1305")
	eq(t, o["password"], "pass")
}

func TestShadowsocks2022PlainUserinfoAndPlugin(t *testing.T) {
	_, o := one(t, "ss://2022-blake3-aes-128-gcm:abc%2Bdef%3D%3D@h.example:8443/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#x")
	eq(t, o["method"], "2022-blake3-aes-128-gcm")
	eq(t, o["password"], "abc+def==")
	eq(t, o["plugin"], "obfs-local")
	eq(t, o["plugin_opts"], "obfs=http;obfs-host=bing.com")
}

func TestVmessWebSocketTLS(t *testing.T) {
	cfg, _ := json.Marshal(map[string]any{"v": "2", "ps": "日本 01", "add": "jp.example.com", "port": "443", "id": "11111111-2222-3333-4444-555555555555",
		"aid": "0", "scy": "auto", "net": "ws", "host": "cdn.example.com", "path": "/ray?ed=2048", "tls": "tls", "sni": "jp.example.com"})
	n, o := one(t, "vmess://"+base64.StdEncoding.EncodeToString(cfg))
	eq(t, n.Name, "日本 01")
	eq(t, o["type"], "vmess")
	eq(t, o["server_port"], 443)
	eq(t, o["uuid"], "11111111-2222-3333-4444-555555555555")
	eq(t, m(o["tls"])["server_name"], "jp.example.com")
	tr := m(o["transport"])
	eq(t, tr["type"], "ws")
	eq(t, tr["path"], "/ray")
	eq(t, tr["max_early_data"], 2048)
	eq(t, m(tr["headers"])["Host"], "cdn.example.com")
}

func TestVlessRealityNormalizesPaddedKey(t *testing.T) {
	n, o := one(t, "vless://uuid-1@203.0.113.9:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=ab%2Bcd%2Fef%3D&sid=0123abcd&type=tcp#Reality")
	eq(t, n.Name, "Reality")
	eq(t, o["flow"], "xtls-rprx-vision")
	tls := m(o["tls"])
	eq(t, tls["server_name"], "www.microsoft.com")
	r := m(tls["reality"])
	eq(t, r["public_key"], "ab-cd_ef")
	eq(t, r["short_id"], "0123abcd")
	eq(t, m(tls["utls"])["fingerprint"], "chrome")
	if o["transport"] != nil {
		t.Error("plain tcp needs no transport")
	}
}

func TestVlessGrpcNoTLS(t *testing.T) {
	_, o := one(t, "vless://u@h.example:80?type=grpc&serviceName=svc&security=none#g")
	if o["tls"] != nil {
		t.Error("expected no tls")
	}
	eq(t, m(o["transport"])["service_name"], "svc")
}

func TestTrojanDefaultsToTLS(t *testing.T) {
	_, o := one(t, "trojan://p%40ss@t.example.com:443?sni=t.example.com&type=ws&path=%2Fws#T")
	eq(t, o["type"], "trojan")
	eq(t, o["password"], "p@ss")
	eq(t, m(o["tls"])["enabled"], true)
	eq(t, m(o["transport"])["path"], "/ws")
}

func TestHysteria2WithObfsAndPortHopping(t *testing.T) {
	_, o := one(t, "hysteria2://secret@hy.example.com:443,20000-30000/?sni=hy.example.com&obfs=salamander&obfs-password=xyz&insecure=1#HY")
	eq(t, o["type"], "hysteria2")
	eq(t, o["server_port"], 443)
	eq(t, o["password"], "secret")
	eq(t, m(o["obfs"])["password"], "xyz")
	eq(t, m(o["tls"])["insecure"], true)
}

func TestHy2Alias(t *testing.T) {
	_, o := one(t, "hy2://pw@1.1.1.2:8443#short")
	eq(t, o["type"], "hysteria2")
	eq(t, o["server_port"], 8443)
}

func TestTuic(t *testing.T) {
	_, o := one(t, "tuic://uuid-x:pw@tuic.example.com:443?congestion_control=cubic&alpn=h3&sni=tuic.example.com#TU")
	eq(t, o["uuid"], "uuid-x")
	eq(t, o["password"], "pw")
	eq(t, o["congestion_control"], "cubic")
	eq(t, m(o["tls"])["alpn"], []any{"h3"})
}

func TestAnyTls(t *testing.T) {
	_, o := one(t, "anytls://pw@a.example.com:443?sni=a.example.com#A")
	eq(t, o["type"], "anytls")
	eq(t, o["password"], "pw")
}

func TestSocksWithCredentials(t *testing.T) {
	_, o := one(t, "socks5://user:pass@10.0.0.1:1080#S")
	eq(t, o["type"], "socks")
	eq(t, o["username"], "user")
	eq(t, o["password"], "pass")
}

func names(nodes []Node) []string {
	out := []string{}
	for _, n := range nodes {
		out = append(out, n.Name)
	}
	return out
}

func TestBase64LinkListSubscription(t *testing.T) {
	list := []string{"trojan://a@x.example:443#One", "hy2://b@y.example:443#Two", "not a link", "vless://c@z.example:443?security=tls#Three"}
	body := base64.StdEncoding.EncodeToString([]byte(strings.Join(list, "\n")))
	eq(t, names(ParseText(body)), []string{"One", "Two", "Three"})
}

func TestClashYamlSubscription(t *testing.T) {
	yaml := `port: 7890
proxies:
  - name: "HK Reality"
    type: vless
    server: hk.example.com
    port: 443
    uuid: u-1
    flow: xtls-rprx-vision
    tls: true
    servername: www.apple.com
    client-fingerprint: chrome
    reality-opts:
      public-key: abc=
      short-id: 1a2b
  - name: SS
    type: ss
    server: 5.6.7.8
    port: 8388
    cipher: aes-128-gcm
    password: p
  - name: Trojan WS
    type: trojan
    server: t.example
    port: 443
    password: q
    network: ws
    ws-opts:
      path: /t
      headers:
        Host: cdn.example
  - name: Unsupported
    type: snell
    server: s
    port: 1
proxy-groups: []
`
	nodes := ParseText(yaml)
	eq(t, names(nodes), []string{"HK Reality", "SS", "Trojan WS"})
	eq(t, m(m(nodes[0].fields()["tls"])["reality"])["public_key"], "abc")
}

func TestSingBoxJsonSubscription(t *testing.T) {
	body := `{"outbounds":[{"type":"selector","tag":"auto"},{"type":"trojan","tag":"US 1","server":"u.example","server_port":443,"password":"x","detour":"z"},{"type":"direct","tag":"direct"}]}`
	nodes := ParseText(body)
	eq(t, names(nodes), []string{"US 1"})
	if strings.Contains(nodes[0].Outbound, "detour") || strings.Contains(nodes[0].Outbound, `"tag"`) {
		t.Error("detour and tag must be stripped")
	}
}

func TestGarbageGivesNothing(t *testing.T) {
	for _, s := range []string{"hello world", "", "https://example.com/sub"} {
		if len(ParseText(s)) != 0 {
			t.Errorf("expected nothing from %q", s)
		}
	}
}
