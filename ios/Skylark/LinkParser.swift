import Foundation
import Yams

/// Turns share links (ss, vmess, vless, trojan, hysteria2, tuic, anytls, socks) and subscription
/// bodies (base64 link lists, Clash YAML, sing-box JSON) into nodes. Port of the Android LinkParser.
enum LinkParser {
    typealias JSON = [String: Any]

    private static let schemes = ["ss", "vmess", "vless", "trojan", "hysteria2", "hy2", "tuic", "anytls", "socks", "socks5"]

    static func isShareLink(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return schemes.contains { t.hasPrefix("\($0)://") }
    }

    /// Parses any pasted text: one link, many links, or a whole subscription body.
    static func parseText(_ raw: String) -> [Node] {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        if text.isEmpty { return [] }

        if text.hasPrefix("{"), let nodes = parseSingBox(text) { return nodes }
        if text.range(of: "(?m)^proxies:", options: .regularExpression) != nil { return parseClash(text) }

        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if lines.contains(where: isShareLink) { return lines.compactMap { parseLink($0) } }

        // Probably a base64-encoded link list.
        guard let decoded = decodeBase64(text.components(separatedBy: .whitespacesAndNewlines).joined()), decoded != text else { return [] }
        return parseText(decoded)
    }

    static func parseLink(_ link: String) -> Node? {
        let scheme = link.components(separatedBy: "://").first?.lowercased() ?? ""
        switch scheme {
        case "ss": return parseSs(link)
        case "vmess": return parseVmess(link)
        case "vless": return parseVlessLike(link, type: "vless")
        case "trojan": return parseVlessLike(link, type: "trojan")
        case "hysteria2", "hy2": return parseHy2(link)
        case "tuic": return parseTuic(link)
        case "anytls": return parseAnyTls(link)
        case "socks", "socks5": return parseSocks(link)
        default: return nil
        }
    }

    // MARK: - individual schemes

    private static func parseSs(_ link: String) -> Node? {
        var body = String(link.dropFirst("ss://".count))
        let name = decode(after(body, "#"))
        body = before(body, "#")
        let query = after(body, "?")
        body = before(body, "?").trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        var method = "", password = "", host = "", port = 0
        if let at = body.range(of: "@", options: .backwards) {
            let userInfo = String(body[..<at.lowerBound]), hostPort = String(body[at.upperBound...])
            let creds = decodeBase64(userInfo).flatMap { $0.contains(":") ? $0 : nil } ?? decode(userInfo)
            method = before(creds, ":"); password = after(creds, ":")
            (host, port) = splitHostPort(hostPort)
        } else {
            guard let plain = decodeBase64(body), let at = plain.range(of: "@", options: .backwards) else { return nil }
            let creds = String(plain[..<at.lowerBound])
            method = before(creds, ":"); password = after(creds, ":")
            (host, port) = splitHostPort(String(plain[at.upperBound...]))
        }
        guard port > 0 else { return nil }
        var out: JSON = ["type": "shadowsocks", "server": host, "server_port": port, "method": method, "password": password]
        if let plugin = params(query)["plugin"], !plugin.isEmpty {
            let pluginName = before(plugin, ";")
            out["plugin"] = pluginName == "simple-obfs" ? "obfs-local" : pluginName
            out["plugin_opts"] = plugin.contains(";") ? after(plugin, ";") : ""
        }
        return node(name.isEmpty ? "\(host):\(port)" : name, out)
    }

    private static func parseVmess(_ link: String) -> Node? {
        let b64 = before(String(link.dropFirst("vmess://".count)), "#")
        guard let s = decodeBase64(b64), let json = (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? JSON else { return nil }
        func str(_ k: String) -> String { (json[k].map { "\($0)" }) ?? "" }
        let host = str("add")
        guard let port = Int(str("port")) else { return nil }
        var out: JSON = ["type": "vmess", "server": host, "server_port": port, "uuid": str("id"),
                         "security": str("scy").isEmpty ? "auto" : str("scy"), "alter_id": Int(str("aid")) ?? 0]
        if str("tls") == "tls" {
            let sni = !str("sni").isEmpty ? str("sni") : (!str("host").isEmpty ? str("host") : host)
            out["tls"] = tlsObject(sni: sni, insecure: false, alpn: str("alpn"), fp: str("fp"))
        }
        if let t = transport(net: str("net"), path: str("path"), host: str("host"), serviceName: str("path"), headerType: str("type")) {
            out["transport"] = t
        }
        return node(str("ps").isEmpty ? "\(host):\(port)" : str("ps"), out)
    }

    private static func parseVlessLike(_ link: String, type: String) -> Node? {
        guard let comps = URLComponents(string: before(link, "#")), let host = comps.host else { return nil }
        let p = params(comps.percentEncodedQuery ?? "")
        let name = decode(after(link, "#"))
        let port = comps.port ?? 443
        let secret = decode(comps.percentEncodedUser ?? "")
        var out: JSON = ["type": type, "server": host.trimmingCharacters(in: CharacterSet(charactersIn: "[]")), "server_port": port]
        if type == "vless" {
            out["uuid"] = secret
            if let flow = p["flow"], !flow.isEmpty { out["flow"] = flow }
        } else {
            out["password"] = secret
        }
        let security = p["security"] ?? (type == "trojan" ? "tls" : "none")
        if ["tls", "reality", "xtls"].contains(security) {
            var tls = tlsObject(sni: p["sni"] ?? p["peer"] ?? p["host"] ?? host,
                                insecure: p["allowInsecure"] == "1" || p["insecure"] == "1" || p["allowInsecure"] == "true",
                                alpn: p["alpn"] ?? "", fp: p["fp"] ?? "")
            if security == "reality" {
                tls["reality"] = ["enabled": true, "public_key": realityKey(p["pbk"] ?? ""), "short_id": p["sid"] ?? ""]
                if tls["utls"] == nil { tls["utls"] = ["enabled": true, "fingerprint": "chrome"] }
            }
            out["tls"] = tls
        }
        if let t = transport(net: p["type"] ?? "", path: p["path"] ?? "", host: p["host"] ?? "", serviceName: p["serviceName"] ?? "", headerType: p["headerType"] ?? "") {
            out["transport"] = t
        }
        return node(name.isEmpty ? "\(host):\(port)" : name, out)
    }

    private static func parseHy2(_ link: String) -> Node? {
        let normalized = link.replacingOccurrences(of: "^hy2://", with: "hysteria2://", options: [.regularExpression, .caseInsensitive])
        let main = before(normalized, "#")
        // Port hopping ("443,20000-30000") isn't a valid URL port; keep the first number.
        let cleaned = main.replacingOccurrences(of: ":(\\d+)[,-][0-9,-]+", with: ":$1", options: .regularExpression)
        guard let comps = URLComponents(string: cleaned), let host = comps.host else { return nil }
        let p = params(comps.percentEncodedQuery ?? "")
        let port = comps.port ?? 443
        var out: JSON = ["type": "hysteria2", "server": host, "server_port": port, "password": decode(comps.percentEncodedUser ?? ""),
                         "tls": tlsObject(sni: p["sni"] ?? host, insecure: p["insecure"] == "1", alpn: p["alpn"] ?? "", fp: "")]
        if p["obfs"] == "salamander" { out["obfs"] = ["type": "salamander", "password": p["obfs-password"] ?? ""] }
        let name = decode(after(link, "#"))
        return node(name.isEmpty ? "\(host):\(port)" : name, out)
    }

    private static func parseTuic(_ link: String) -> Node? {
        guard let comps = URLComponents(string: before(link, "#")), let host = comps.host, let port = comps.port else { return nil }
        let p = params(comps.percentEncodedQuery ?? "")
        let user = decode(comps.percentEncodedUser ?? "")
        let pass = decode(comps.percentEncodedPassword ?? "")
        let out: JSON = ["type": "tuic", "server": host, "server_port": port, "uuid": user, "password": pass,
                         "congestion_control": p["congestion_control"] ?? "bbr", "udp_relay_mode": p["udp_relay_mode"] ?? "native",
                         "tls": tlsObject(sni: p["sni"] ?? host, insecure: p["allow_insecure"] == "1" || p["insecure"] == "1", alpn: p["alpn"] ?? "h3", fp: "")]
        let name = decode(after(link, "#"))
        return node(name.isEmpty ? "\(host):\(port)" : name, out)
    }

    private static func parseAnyTls(_ link: String) -> Node? {
        guard let comps = URLComponents(string: before(link, "#")), let host = comps.host, let port = comps.port else { return nil }
        let p = params(comps.percentEncodedQuery ?? "")
        let out: JSON = ["type": "anytls", "server": host, "server_port": port, "password": decode(comps.percentEncodedUser ?? ""),
                         "tls": tlsObject(sni: p["sni"] ?? host, insecure: p["insecure"] == "1", alpn: p["alpn"] ?? "", fp: p["fp"] ?? "")]
        let name = decode(after(link, "#"))
        return node(name.isEmpty ? "\(host):\(port)" : name, out)
    }

    private static func parseSocks(_ link: String) -> Node? {
        let fixed = before(link, "#").replacingOccurrences(of: "socks5://", with: "socks://")
        guard let comps = URLComponents(string: fixed), let host = comps.host, let port = comps.port else { return nil }
        var out: JSON = ["type": "socks", "server": host, "server_port": port]
        if let user = comps.percentEncodedUser {
            let combined = comps.percentEncodedPassword.map { "\(user):\($0)" } ?? user
            let creds = decodeBase64(combined).flatMap { $0.contains(":") ? $0 : nil } ?? decode(combined)
            out["username"] = before(creds, ":"); out["password"] = after(creds, ":")
        }
        let name = decode(after(link, "#"))
        return node(name.isEmpty ? "\(host):\(port)" : name, out)
    }

    // MARK: - subscription formats

    private static let supportedOutbounds: Set<String> = ["shadowsocks", "vmess", "vless", "trojan", "hysteria2", "tuic", "anytls", "socks", "http", "hysteria", "shadowtls", "ssh"]

    private static func parseSingBox(_ text: String) -> [Node]? {
        guard let root = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? JSON,
              let arr = root["outbounds"] as? [JSON] else { return nil }
        return arr.filter { supportedOutbounds.contains($0["type"] as? String ?? "") }.compactMap { o in
            var o = o
            let name = (o["tag"] as? String) ?? (o["server"] as? String) ?? "server"
            o.removeValue(forKey: "tag"); o.removeValue(forKey: "detour")
            return node(name, o)
        }
    }

    private static func parseClash(_ text: String) -> [Node] {
        guard let root = (try? Yams.load(yaml: text)) as? [String: Any],
              let proxies = root["proxies"] as? [[String: Any]] else { return [] }
        return proxies.compactMap(clashProxy)
    }

    private static func clashProxy(_ m: [String: Any]) -> Node? {
        func s(_ k: String) -> String { m[k].map { "\($0)" } ?? "" }
        func b(_ k: String) -> Bool { (m[k] as? Bool) == true || s(k) == "true" }
        let server = s("server")
        guard let port = Int(before(before(s("port"), ","), "-")) else { return nil }
        let sni = !s("servername").isEmpty ? s("servername") : (!s("sni").isEmpty ? s("sni") : server)
        let alpn = (m["alpn"] as? [Any])?.map { "\($0)" }.joined(separator: ",") ?? ""
        let fp = s("client-fingerprint")
        var out: JSON = ["server": server, "server_port": port]
        switch s("type") {
        case "ss":
            out["type"] = "shadowsocks"; out["method"] = s("cipher"); out["password"] = s("password")
            let opts = m["plugin-opts"] as? [String: Any]
            switch s("plugin") {
            case "obfs":
                out["plugin"] = "obfs-local"
                out["plugin_opts"] = "obfs=\(opts?["mode"] ?? "");obfs-host=\(opts?["host"] ?? "")"
            case "v2ray-plugin":
                var parts = ["mode=websocket"]
                if (opts?["tls"] as? Bool) == true { parts.append("tls") }
                if let h = opts?["host"] { parts.append("host=\(h)") }
                if let p = opts?["path"] { parts.append("path=\(p)") }
                out["plugin"] = "v2ray-plugin"; out["plugin_opts"] = parts.joined(separator: ";")
            default: break
            }
        case "vmess":
            out["type"] = "vmess"; out["uuid"] = s("uuid"); out["alter_id"] = Int(s("alterId")) ?? 0
            out["security"] = s("cipher").isEmpty ? "auto" : s("cipher")
            if b("tls") { out["tls"] = tlsObject(sni: sni, insecure: b("skip-cert-verify"), alpn: alpn, fp: fp) }
            if let t = clashTransport(m) { out["transport"] = t }
        case "vless":
            out["type"] = "vless"; out["uuid"] = s("uuid")
            if !s("flow").isEmpty { out["flow"] = s("flow") }
            if b("tls") || m["reality-opts"] != nil {
                var tls = tlsObject(sni: sni, insecure: b("skip-cert-verify"), alpn: alpn, fp: fp)
                if let r = m["reality-opts"] as? [String: Any] {
                    tls["reality"] = ["enabled": true, "public_key": realityKey(r["public-key"].map { "\($0)" } ?? ""),
                                      "short_id": r["short-id"].map { "\($0)" } ?? ""]
                    if tls["utls"] == nil { tls["utls"] = ["enabled": true, "fingerprint": "chrome"] }
                }
                out["tls"] = tls
            }
            if let t = clashTransport(m) { out["transport"] = t }
        case "trojan":
            out["type"] = "trojan"; out["password"] = s("password")
            out["tls"] = tlsObject(sni: sni, insecure: b("skip-cert-verify"), alpn: alpn, fp: fp)
            if let t = clashTransport(m) { out["transport"] = t }
        case "hysteria2":
            out["type"] = "hysteria2"; out["password"] = s("password").isEmpty ? s("auth") : s("password")
            out["tls"] = tlsObject(sni: sni, insecure: b("skip-cert-verify"), alpn: alpn, fp: "")
            if s("obfs") == "salamander" { out["obfs"] = ["type": "salamander", "password": s("obfs-password")] }
        case "tuic":
            out["type"] = "tuic"; out["uuid"] = s("uuid"); out["password"] = s("password")
            out["congestion_control"] = s("congestion-controller").isEmpty ? "bbr" : s("congestion-controller")
            out["udp_relay_mode"] = s("udp-relay-mode").isEmpty ? "native" : s("udp-relay-mode")
            out["tls"] = tlsObject(sni: sni, insecure: b("skip-cert-verify"), alpn: alpn.isEmpty ? "h3" : alpn, fp: "")
        case "anytls":
            out["type"] = "anytls"; out["password"] = s("password")
            out["tls"] = tlsObject(sni: sni, insecure: b("skip-cert-verify"), alpn: alpn, fp: fp)
        case "socks5":
            out["type"] = "socks"
            if !s("username").isEmpty { out["username"] = s("username"); out["password"] = s("password") }
        case "http":
            out["type"] = "http"
            if !s("username").isEmpty { out["username"] = s("username"); out["password"] = s("password") }
            if b("tls") { out["tls"] = tlsObject(sni: sni, insecure: b("skip-cert-verify"), alpn: "", fp: "") }
        default:
            return nil
        }
        return node(s("name").isEmpty ? "\(server):\(port)" : s("name"), out)
    }

    private static func clashTransport(_ m: [String: Any]) -> JSON? {
        switch m["network"].map({ "\($0)" }) {
        case "ws":
            let o = m["ws-opts"] as? [String: Any]
            let host = (o?["headers"] as? [String: Any])?["Host"].map { "\($0)" } ?? ""
            return transport(net: "ws", path: o?["path"].map { "\($0)" } ?? "", host: host, serviceName: "", headerType: "")
        case "grpc":
            let name = (m["grpc-opts"] as? [String: Any])?["grpc-service-name"].map { "\($0)" } ?? ""
            return transport(net: "grpc", path: "", host: "", serviceName: name, headerType: "")
        case "h2", "http":
            let o = (m["h2-opts"] ?? m["http-opts"]) as? [String: Any]
            func first(_ v: Any?) -> String { (v as? [Any])?.first.map { "\($0)" } ?? v.map { "\($0)" } ?? "" }
            return transport(net: "http", path: first(o?["path"]), host: first(o?["host"]), serviceName: "", headerType: "")
        default:
            return nil
        }
    }

    // MARK: - helpers

    private static func node(_ name: String, _ out: JSON) -> Node? {
        guard let data = try? JSONSerialization.data(withJSONObject: out, options: [.sortedKeys]),
              let s = String(data: data, encoding: .utf8) else { return nil }
        return Node(name: name, outbound: s)
    }

    /// sing-box wants unpadded URL-safe base64; many links carry padding or the standard alphabet.
    private static func realityKey(_ k: String) -> String {
        k.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "="))
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
    }

    private static func tlsObject(sni: String, insecure: Bool, alpn: String, fp: String) -> JSON {
        var tls: JSON = ["enabled": true, "server_name": sni]
        if insecure { tls["insecure"] = true }
        let a = alpn.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if !a.isEmpty { tls["alpn"] = a }
        if !fp.isEmpty && fp != "none" { tls["utls"] = ["enabled": true, "fingerprint": fp] }
        return tls
    }

    /// sing-box transport from v2ray-style fields. "tcp" without an http header means no transport.
    private static func transport(net: String, path: String, host: String, serviceName: String, headerType: String) -> JSON? {
        switch net.lowercased() {
        case "ws":
            var cleanPath = path.isEmpty ? "/" : path
            var o: JSON = ["type": "ws"]
            if let r = cleanPath.range(of: "[?&]ed=(\\d+)", options: .regularExpression) {
                let ed = cleanPath[r].filter(\.isNumber)
                cleanPath.removeSubrange(r)
                o["max_early_data"] = Int(ed) ?? 0
                o["early_data_header_name"] = "Sec-WebSocket-Protocol"
            }
            o["path"] = cleanPath
            if !host.isEmpty { o["headers"] = ["Host": host] }
            return o
        case "grpc":
            return ["type": "grpc", "service_name": serviceName.isEmpty ? path : serviceName]
        case "h2", "http":
            var o: JSON = ["type": "http"]
            if !path.isEmpty { o["path"] = path }
            if !host.isEmpty { o["host"] = host.split(separator: ",").map(String.init) }
            return o
        case "httpupgrade":
            var o: JSON = ["type": "httpupgrade", "path": path.isEmpty ? "/" : path]
            if !host.isEmpty { o["host"] = host }
            return o
        case "tcp", "":
            guard headerType == "http" else { return nil }
            var o: JSON = ["type": "http"]
            if !path.isEmpty { o["path"] = path }
            if !host.isEmpty { o["host"] = host.split(separator: ",").map(String.init) }
            return o
        default:
            return nil
        }
    }

    private static func params(_ query: String) -> [String: String] {
        var out: [String: String] = [:]
        for pair in query.split(separator: "&") where pair.contains("=") {
            let k = decode(before(String(pair), "=")), v = decode(after(String(pair), "="))
            out[k] = v
        }
        return out
    }

    /// Percent-decoding that keeps a literal "+" (it is common inside passwords).
    private static func decode(_ s: String) -> String {
        s.replacingOccurrences(of: "+", with: "%2B").removingPercentEncoding ?? s
    }

    static func decodeBase64(_ s: String) -> String? {
        var clean = s.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            .components(separatedBy: .whitespacesAndNewlines).joined()
        clean += String(repeating: "=", count: (4 - clean.count % 4) % 4)
        guard let data = Data(base64Encoded: clean), let out = String(data: data, encoding: .utf8), !out.isEmpty else { return nil }
        return out
    }

    private static func before(_ s: String, _ sep: String) -> String {
        s.range(of: sep).map { String(s[..<$0.lowerBound]) } ?? s
    }

    private static func after(_ s: String, _ sep: String) -> String {
        s.range(of: sep).map { String(s[$0.upperBound...]) } ?? ""
    }

    private static func splitHostPort(_ hp: String) -> (String, Int) {
        guard let colon = hp.range(of: ":", options: .backwards) else { return (hp, 0) }
        let host = String(hp[..<colon.lowerBound]).trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        return (host, Int(hp[colon.upperBound...]) ?? 0)
    }
}
