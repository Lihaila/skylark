package core

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// Turns share links (ss, vmess, vless, trojan, hysteria2, tuic, anytls, socks) and subscription
// bodies (base64 link lists, Clash YAML, sing-box JSON) into nodes. Port of the iOS/Android LinkParser.

type obj = map[string]any

var schemes = []string{"ss", "vmess", "vless", "trojan", "hysteria2", "hy2", "tuic", "anytls", "socks", "socks5"}

func IsShareLink(text string) bool {
	t := strings.ToLower(strings.TrimSpace(text))
	for _, s := range schemes {
		if strings.HasPrefix(t, s+"://") {
			return true
		}
	}
	return false
}

var clashRe = regexp.MustCompile(`(?m)^proxies:`)

// ParseText parses any pasted text: one link, many links, or a whole subscription body.
func ParseText(raw string) []Node {
	text := strings.TrimPrefix(strings.TrimSpace(raw), "\uFEFF")
	if text == "" {
		return nil
	}
	if strings.HasPrefix(text, "{") {
		if nodes, ok := parseSingBox(text); ok {
			return nodes
		}
	}
	if clashRe.MatchString(text) {
		return parseClash(text)
	}
	var lines []string
	for _, l := range strings.Split(strings.ReplaceAll(text, "\r", "\n"), "\n") {
		if l = strings.TrimSpace(l); l != "" {
			lines = append(lines, l)
		}
	}
	for _, l := range lines {
		if IsShareLink(l) {
			var out []Node
			for _, l := range lines {
				if n, ok := ParseLink(l); ok {
					out = append(out, n)
				}
			}
			return out
		}
	}
	// Probably a base64-encoded link list.
	decoded, ok := DecodeBase64(strings.Join(strings.Fields(text), ""))
	if !ok || decoded == text {
		return nil
	}
	return ParseText(decoded)
}

func ParseLink(link string) (Node, bool) {
	scheme := strings.ToLower(before(link, "://"))
	switch scheme {
	case "ss":
		return parseSs(link)
	case "vmess":
		return parseVmess(link)
	case "vless":
		return parseVlessLike(link, "vless")
	case "trojan":
		return parseVlessLike(link, "trojan")
	case "hysteria2", "hy2":
		return parseHy2(link)
	case "tuic":
		return parseTuic(link)
	case "anytls":
		return parseAnyTls(link)
	case "socks", "socks5":
		return parseSocks(link)
	}
	return Node{}, false
}

// ---- individual schemes

func parseSs(link string) (Node, bool) {
	body := link[len("ss://"):]
	name := decode(after(body, "#"))
	body = before(body, "#")
	query := after(body, "?")
	body = strings.Trim(before(body, "?"), "/")

	var method, password, host string
	var port int
	if at := strings.LastIndex(body, "@"); at >= 0 {
		userInfo, hostPort := body[:at], body[at+1:]
		creds, ok := DecodeBase64(userInfo)
		if !ok || !strings.Contains(creds, ":") {
			creds = decode(userInfo)
		}
		method, password = before(creds, ":"), after(creds, ":")
		host, port = splitHostPort(hostPort)
	} else {
		plain, ok := DecodeBase64(body)
		if !ok {
			return Node{}, false
		}
		at := strings.LastIndex(plain, "@")
		if at < 0 {
			return Node{}, false
		}
		creds := plain[:at]
		method, password = before(creds, ":"), after(creds, ":")
		host, port = splitHostPort(plain[at+1:])
	}
	if port <= 0 {
		return Node{}, false
	}
	out := obj{"type": "shadowsocks", "server": host, "server_port": port, "method": method, "password": password}
	if plugin := params(query)["plugin"]; plugin != "" {
		pluginName := before(plugin, ";")
		if pluginName == "simple-obfs" {
			pluginName = "obfs-local"
		}
		out["plugin"] = pluginName
		out["plugin_opts"] = after(plugin, ";")
	}
	return node(orDefault(name, fmt.Sprintf("%s:%d", host, port)), out)
}

func parseVmess(link string) (Node, bool) {
	s, ok := DecodeBase64(before(link[len("vmess://"):], "#"))
	if !ok {
		return Node{}, false
	}
	var j map[string]any
	if json.Unmarshal([]byte(s), &j) != nil {
		return Node{}, false
	}
	str := func(k string) string {
		if v, ok := j[k]; ok && v != nil {
			switch t := v.(type) {
			case float64:
				return strconv.FormatInt(int64(t), 10)
			default:
				return fmt.Sprint(t)
			}
		}
		return ""
	}
	host := str("add")
	port, err := strconv.Atoi(str("port"))
	if err != nil {
		return Node{}, false
	}
	aid, _ := strconv.Atoi(str("aid"))
	out := obj{"type": "vmess", "server": host, "server_port": port, "uuid": str("id"),
		"security": orDefault(str("scy"), "auto"), "alter_id": aid}
	if str("tls") == "tls" {
		sni := orDefault(str("sni"), orDefault(str("host"), host))
		out["tls"] = tlsObject(sni, false, str("alpn"), str("fp"))
	}
	if t := transport(str("net"), str("path"), str("host"), str("path"), str("type")); t != nil {
		out["transport"] = t
	}
	return node(orDefault(str("ps"), fmt.Sprintf("%s:%d", host, port)), out)
}

func parseURL(link string) (*url.URL, string, bool) {
	u, err := url.Parse(before(link, "#"))
	if err != nil || u.Hostname() == "" {
		return nil, "", false
	}
	return u, u.Hostname(), true
}

func urlPort(u *url.URL, def int) (int, bool) {
	if p := u.Port(); p != "" {
		n, err := strconv.Atoi(p)
		return n, err == nil
	}
	return def, def > 0
}

func userPart(u *url.URL) (user, pass string, hasPass bool) {
	if u.User == nil {
		return "", "", false
	}
	// Re-decode from the raw form so "+" stays literal.
	raw := u.User.String()
	if i := strings.Index(raw, ":"); i >= 0 {
		return decode(raw[:i]), decode(raw[i+1:]), true
	}
	return decode(raw), "", false
}

func parseVlessLike(link, typ string) (Node, bool) {
	u, host, ok := parseURL(link)
	if !ok {
		return Node{}, false
	}
	p := params(u.RawQuery)
	name := decode(after(link, "#"))
	port, ok := urlPort(u, 443)
	if !ok {
		return Node{}, false
	}
	secret, _, _ := userPart(u)
	out := obj{"type": typ, "server": host, "server_port": port}
	if typ == "vless" {
		out["uuid"] = secret
		if f := p["flow"]; f != "" {
			out["flow"] = f
		}
	} else {
		out["password"] = secret
	}
	security, has := p["security"]
	if !has {
		security = map[bool]string{true: "tls", false: "none"}[typ == "trojan"]
	}
	if security == "tls" || security == "reality" || security == "xtls" {
		sni := firstNonEmpty(p["sni"], p["peer"], p["host"], host)
		insecure := p["allowInsecure"] == "1" || p["insecure"] == "1" || p["allowInsecure"] == "true"
		tls := tlsObject(sni, insecure, p["alpn"], p["fp"])
		if security == "reality" {
			tls["reality"] = obj{"enabled": true, "public_key": realityKey(p["pbk"]), "short_id": p["sid"]}
			if _, ok := tls["utls"]; !ok {
				tls["utls"] = obj{"enabled": true, "fingerprint": "chrome"}
			}
		}
		out["tls"] = tls
	}
	if t := transport(p["type"], p["path"], p["host"], p["serviceName"], p["headerType"]); t != nil {
		out["transport"] = t
	}
	return node(orDefault(name, fmt.Sprintf("%s:%d", host, port)), out)
}

var (
	hy2Scheme  = regexp.MustCompile(`(?i)^hy2://`)
	hy2Hopping = regexp.MustCompile(`:(\d+)[,-][0-9,-]+`)
)

func parseHy2(link string) (Node, bool) {
	normalized := hy2Scheme.ReplaceAllString(link, "hysteria2://")
	// Port hopping ("443,20000-30000") isn't a valid URL port; keep the first number.
	cleaned := hy2Hopping.ReplaceAllString(before(normalized, "#"), ":$1")
	u, host, ok := parseURL(cleaned)
	if !ok {
		return Node{}, false
	}
	p := params(u.RawQuery)
	port, ok := urlPort(u, 443)
	if !ok {
		return Node{}, false
	}
	pw, _, _ := userPart(u)
	out := obj{"type": "hysteria2", "server": host, "server_port": port, "password": pw,
		"tls": tlsObject(orDefault(p["sni"], host), p["insecure"] == "1", p["alpn"], "")}
	if p["obfs"] == "salamander" {
		out["obfs"] = obj{"type": "salamander", "password": p["obfs-password"]}
	}
	return node(orDefault(decode(after(link, "#")), fmt.Sprintf("%s:%d", host, port)), out)
}

func parseTuic(link string) (Node, bool) {
	u, host, ok := parseURL(link)
	if !ok || u.Port() == "" {
		return Node{}, false
	}
	port, _ := urlPort(u, 0)
	p := params(u.RawQuery)
	user, pass, _ := userPart(u)
	out := obj{"type": "tuic", "server": host, "server_port": port, "uuid": user, "password": pass,
		"congestion_control": orDefault(p["congestion_control"], "bbr"), "udp_relay_mode": orDefault(p["udp_relay_mode"], "native"),
		"tls": tlsObject(orDefault(p["sni"], host), p["allow_insecure"] == "1" || p["insecure"] == "1", orDefault(p["alpn"], "h3"), "")}
	return node(orDefault(decode(after(link, "#")), fmt.Sprintf("%s:%d", host, port)), out)
}

func parseAnyTls(link string) (Node, bool) {
	u, host, ok := parseURL(link)
	if !ok || u.Port() == "" {
		return Node{}, false
	}
	port, _ := urlPort(u, 0)
	p := params(u.RawQuery)
	pw, _, _ := userPart(u)
	out := obj{"type": "anytls", "server": host, "server_port": port, "password": pw,
		"tls": tlsObject(orDefault(p["sni"], host), p["insecure"] == "1", p["alpn"], p["fp"])}
	return node(orDefault(decode(after(link, "#")), fmt.Sprintf("%s:%d", host, port)), out)
}

func parseSocks(link string) (Node, bool) {
	fixed := strings.Replace(before(link, "#"), "socks5://", "socks://", 1)
	u, host, ok := parseURL(fixed)
	if !ok || u.Port() == "" {
		return Node{}, false
	}
	port, _ := urlPort(u, 0)
	out := obj{"type": "socks", "server": host, "server_port": port}
	if u.User != nil {
		combined := u.User.String()
		creds, ok := DecodeBase64(combined)
		if !ok || !strings.Contains(creds, ":") {
			creds = decode(combined)
		}
		out["username"], out["password"] = before(creds, ":"), after(creds, ":")
	}
	return node(orDefault(decode(after(link, "#")), fmt.Sprintf("%s:%d", host, port)), out)
}

// ---- subscription formats

var supportedOutbounds = map[string]bool{"shadowsocks": true, "vmess": true, "vless": true, "trojan": true, "hysteria2": true,
	"tuic": true, "anytls": true, "socks": true, "http": true, "hysteria": true, "shadowtls": true, "ssh": true}

func parseSingBox(text string) ([]Node, bool) {
	var root struct {
		Outbounds []map[string]any `json:"outbounds"`
	}
	if json.Unmarshal([]byte(text), &root) != nil || root.Outbounds == nil {
		return nil, false
	}
	var out []Node
	for _, o := range root.Outbounds {
		t, _ := o["type"].(string)
		if !supportedOutbounds[t] {
			continue
		}
		name, _ := o["tag"].(string)
		if name == "" {
			name, _ = o["server"].(string)
		}
		delete(o, "tag")
		delete(o, "detour")
		if n, ok := node(orDefault(name, "server"), o); ok {
			out = append(out, n)
		}
	}
	return out, true
}

func parseClash(text string) []Node {
	var root struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if yaml.Unmarshal([]byte(text), &root) != nil {
		return nil
	}
	var out []Node
	for _, m := range root.Proxies {
		if n, ok := clashProxy(m); ok {
			out = append(out, n)
		}
	}
	return out
}

func sval(v any) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}

func clashProxy(m map[string]any) (Node, bool) {
	s := func(k string) string { return sval(m[k]) }
	b := func(k string) bool { v, ok := m[k].(bool); return (ok && v) || s(k) == "true" }
	server := s("server")
	port, err := strconv.Atoi(before(before(s("port"), ","), "-"))
	if err != nil {
		return Node{}, false
	}
	sni := firstNonEmpty(s("servername"), s("sni"), server)
	var alpnParts []string
	if a, ok := m["alpn"].([]any); ok {
		for _, x := range a {
			alpnParts = append(alpnParts, sval(x))
		}
	}
	alpn := strings.Join(alpnParts, ",")
	fp := s("client-fingerprint")
	out := obj{"server": server, "server_port": port}
	switch s("type") {
	case "ss":
		out["type"], out["method"], out["password"] = "shadowsocks", s("cipher"), s("password")
		opts, _ := m["plugin-opts"].(map[string]any)
		switch s("plugin") {
		case "obfs":
			out["plugin"] = "obfs-local"
			out["plugin_opts"] = fmt.Sprintf("obfs=%s;obfs-host=%s", sval(opts["mode"]), sval(opts["host"]))
		case "v2ray-plugin":
			parts := []string{"mode=websocket"}
			if t, _ := opts["tls"].(bool); t {
				parts = append(parts, "tls")
			}
			if h, ok := opts["host"]; ok {
				parts = append(parts, "host="+sval(h))
			}
			if p, ok := opts["path"]; ok {
				parts = append(parts, "path="+sval(p))
			}
			out["plugin"], out["plugin_opts"] = "v2ray-plugin", strings.Join(parts, ";")
		}
	case "vmess":
		aid, _ := strconv.Atoi(s("alterId"))
		out["type"], out["uuid"], out["alter_id"], out["security"] = "vmess", s("uuid"), aid, orDefault(s("cipher"), "auto")
		if b("tls") {
			out["tls"] = tlsObject(sni, b("skip-cert-verify"), alpn, fp)
		}
		if t := clashTransport(m); t != nil {
			out["transport"] = t
		}
	case "vless":
		out["type"], out["uuid"] = "vless", s("uuid")
		if f := s("flow"); f != "" {
			out["flow"] = f
		}
		r, hasReality := m["reality-opts"].(map[string]any)
		if b("tls") || hasReality {
			tls := tlsObject(sni, b("skip-cert-verify"), alpn, fp)
			if hasReality {
				tls["reality"] = obj{"enabled": true, "public_key": realityKey(sval(r["public-key"])), "short_id": sval(r["short-id"])}
				if _, ok := tls["utls"]; !ok {
					tls["utls"] = obj{"enabled": true, "fingerprint": "chrome"}
				}
			}
			out["tls"] = tls
		}
		if t := clashTransport(m); t != nil {
			out["transport"] = t
		}
	case "trojan":
		out["type"], out["password"] = "trojan", s("password")
		out["tls"] = tlsObject(sni, b("skip-cert-verify"), alpn, fp)
		if t := clashTransport(m); t != nil {
			out["transport"] = t
		}
	case "hysteria2":
		out["type"], out["password"] = "hysteria2", orDefault(s("password"), s("auth"))
		out["tls"] = tlsObject(sni, b("skip-cert-verify"), alpn, "")
		if s("obfs") == "salamander" {
			out["obfs"] = obj{"type": "salamander", "password": s("obfs-password")}
		}
	case "tuic":
		out["type"], out["uuid"], out["password"] = "tuic", s("uuid"), s("password")
		out["congestion_control"] = orDefault(s("congestion-controller"), "bbr")
		out["udp_relay_mode"] = orDefault(s("udp-relay-mode"), "native")
		out["tls"] = tlsObject(sni, b("skip-cert-verify"), orDefault(alpn, "h3"), "")
	case "anytls":
		out["type"], out["password"] = "anytls", s("password")
		out["tls"] = tlsObject(sni, b("skip-cert-verify"), alpn, fp)
	case "socks5":
		out["type"] = "socks"
		if s("username") != "" {
			out["username"], out["password"] = s("username"), s("password")
		}
	case "http":
		out["type"] = "http"
		if s("username") != "" {
			out["username"], out["password"] = s("username"), s("password")
		}
		if b("tls") {
			out["tls"] = tlsObject(sni, b("skip-cert-verify"), "", "")
		}
	default:
		return Node{}, false
	}
	return node(orDefault(s("name"), fmt.Sprintf("%s:%d", server, port)), out)
}

func clashTransport(m map[string]any) obj {
	switch sval(m["network"]) {
	case "ws":
		o, _ := m["ws-opts"].(map[string]any)
		h, _ := o["headers"].(map[string]any)
		return transport("ws", sval(o["path"]), sval(h["Host"]), "", "")
	case "grpc":
		o, _ := m["grpc-opts"].(map[string]any)
		return transport("grpc", "", "", sval(o["grpc-service-name"]), "")
	case "h2", "http":
		o, _ := m["h2-opts"].(map[string]any)
		if o == nil {
			o, _ = m["http-opts"].(map[string]any)
		}
		first := func(v any) string {
			if a, ok := v.([]any); ok {
				if len(a) > 0 {
					return sval(a[0])
				}
				return ""
			}
			return sval(v)
		}
		return transport("http", first(o["path"]), first(o["host"]), "", "")
	}
	return nil
}

// ---- helpers

func node(name string, out obj) (Node, bool) {
	data, err := json.Marshal(out) // map keys are sorted
	if err != nil {
		return Node{}, false
	}
	return Node{ID: NewID(), Name: name, Outbound: string(data)}, true
}

// sing-box wants unpadded URL-safe base64; many links carry padding or the standard alphabet.
func realityKey(k string) string {
	k = strings.Trim(strings.TrimSpace(k), "=")
	return strings.NewReplacer("+", "-", "/", "_").Replace(k)
}

func tlsObject(sni string, insecure bool, alpn, fp string) obj {
	tls := obj{"enabled": true, "server_name": sni}
	if insecure {
		tls["insecure"] = true
	}
	var a []string
	for _, x := range strings.Split(alpn, ",") {
		if x = strings.TrimSpace(x); x != "" {
			a = append(a, x)
		}
	}
	if len(a) > 0 {
		tls["alpn"] = a
	}
	if fp != "" && fp != "none" {
		tls["utls"] = obj{"enabled": true, "fingerprint": fp}
	}
	return tls
}

var edRe = regexp.MustCompile(`[?&]ed=(\d+)`)

func splitHosts(h string) []string { return strings.Split(h, ",") }

// sing-box transport from v2ray-style fields. "tcp" without an http header means no transport.
func transport(net, path, host, serviceName, headerType string) obj {
	switch strings.ToLower(net) {
	case "ws":
		p := orDefault(path, "/")
		o := obj{"type": "ws"}
		if m := edRe.FindStringSubmatchIndex(p); m != nil {
			ed, _ := strconv.Atoi(p[m[2]:m[3]])
			p = p[:m[0]] + p[m[1]:]
			o["max_early_data"] = ed
			o["early_data_header_name"] = "Sec-WebSocket-Protocol"
		}
		o["path"] = p
		if host != "" {
			o["headers"] = obj{"Host": host}
		}
		return o
	case "grpc":
		return obj{"type": "grpc", "service_name": orDefault(serviceName, path)}
	case "h2", "http":
		o := obj{"type": "http"}
		if path != "" {
			o["path"] = path
		}
		if host != "" {
			o["host"] = splitHosts(host)
		}
		return o
	case "httpupgrade":
		o := obj{"type": "httpupgrade", "path": orDefault(path, "/")}
		if host != "" {
			o["host"] = host
		}
		return o
	case "tcp", "":
		if headerType != "http" {
			return nil
		}
		o := obj{"type": "http"}
		if path != "" {
			o["path"] = path
		}
		if host != "" {
			o["host"] = splitHosts(host)
		}
		return o
	}
	return nil
}

func params(query string) map[string]string {
	out := map[string]string{}
	for _, pair := range strings.Split(query, "&") {
		if !strings.Contains(pair, "=") {
			continue
		}
		out[decode(before(pair, "="))] = decode(after(pair, "="))
	}
	return out
}

// Percent-decoding that keeps a literal "+" (it is common inside passwords).
func decode(s string) string {
	if d, err := url.PathUnescape(s); err == nil {
		return d
	}
	return s
}

func DecodeBase64(s string) (string, bool) {
	clean := strings.Join(strings.Fields(strings.TrimSpace(s)), "")
	clean = strings.NewReplacer("-", "+", "_", "/").Replace(clean)
	clean = strings.TrimRight(clean, "=")
	data, err := base64.RawStdEncoding.DecodeString(clean)
	if err != nil || len(data) == 0 {
		return "", false
	}
	out := string(data)
	if !isValidUTF8Text(out) {
		return "", false
	}
	return out, true
}

func isValidUTF8Text(s string) bool {
	for _, r := range s {
		if r == '�' {
			return false
		}
	}
	return true
}

func before(s, sep string) string {
	if i := strings.Index(s, sep); i >= 0 {
		return s[:i]
	}
	return s
}

func after(s, sep string) string {
	if i := strings.Index(s, sep); i >= 0 {
		return s[i+len(sep):]
	}
	return ""
}

func splitHostPort(hp string) (string, int) {
	i := strings.LastIndex(hp, ":")
	if i < 0 {
		return hp, 0
	}
	port, _ := strconv.Atoi(hp[i+1:])
	return strings.Trim(hp[:i], "[]"), port
}

func orDefault(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}
