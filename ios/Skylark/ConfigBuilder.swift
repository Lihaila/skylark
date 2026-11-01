import Foundation

/// Builds a complete sing-box config from the app's simple settings. Port of the Android ConfigBuilder
/// (without ad blocking, as on Google Play, and without per-app proxy, which iOS doesn't allow).
enum ConfigBuilder {
    typealias JSON = [String: Any]
    private static let bundled = ["geosite-cn", "geoip-cn"]

    /// Copies the bundled rule sets into the App Group so the tunnel extension can read them.
    static func prepareRules() {
        let fm = FileManager.default
        try? fm.createDirectory(at: AppGroup.rulesDir, withIntermediateDirectories: true)
        for name in bundled {
            let dest = AppGroup.rulesDir.appendingPathComponent("\(name).srs")
            if !fm.fileExists(atPath: dest.path), let src = Bundle.main.url(forResource: name, withExtension: "srs") {
                try? fm.copyItem(at: src, to: dest)
            }
        }
    }

    static func build(_ state: AppState) -> String {
        prepareRules()
        let node = state.selected
        let s = state.settings
        let mode: Mode = node == nil ? .direct : state.mode
        let usingRules = mode == .rule
        let home = Country.effective(s)
        // In mainland China, "my country" is exactly what the bundled China lists cover.
        let homeIsChina = home == "CN"
        let bypassChina = s.bypassChina || (s.bypassHome && homeIsChina)
        let homeGeoip: URL? = {
            guard s.bypassHome, !home.isEmpty, !homeIsChina else { return nil }
            let f = Country.geoipFile(home)
            return FileManager.default.fileExists(atPath: f.path) ? f : nil
        }()

        // outbounds
        var outbounds: [JSON] = []
        if let node, var o = (try? JSONSerialization.jsonObject(with: Data(node.outbound.utf8))) as? JSON {
            o["tag"] = "proxy"
            outbounds.append(o)
        }
        outbounds.append(["type": "direct", "tag": "direct"])
        let hasProxy = outbounds.count > 1

        // rule sets
        var ruleSets: [JSON] = []
        func local(_ tag: String, _ url: URL) {
            ruleSets.append(["type": "local", "tag": tag, "format": "binary", "path": url.path])
        }
        let customGeoip = Array(Set(state.rules.filter { $0.type == .geoip && $0.value.uppercased() != "LAN" }.map { $0.value.lowercased() }))
        if usingRules {
            if bypassChina || customGeoip.contains("cn") {
                local("geosite-cn", AppGroup.rulesDir.appendingPathComponent("geosite-cn.srs"))
                local("geoip-cn", AppGroup.rulesDir.appendingPathComponent("geoip-cn.srs"))
            }
            if let homeGeoip { local("geoip-home", homeGeoip) }
            for code in customGeoip where code != "cn" {
                ruleSets.append(["type": "remote", "tag": "geoip-\(code)", "format": "binary",
                                 "url": "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-\(code).srs",
                                 "download_detour": hasProxy ? "proxy" : "direct"])
            }
        }

        // route
        var rules: [JSON] = [["action": "sniff"], ["protocol": "dns", "action": "hijack-dns"], ["ip_is_private": true, "outbound": "direct"]]
        var dnsRules: [JSON] = []
        if usingRules {
            for r in state.rules {
                var rule: JSON
                switch r.type {
                case .domainSuffix: rule = ["domain_suffix": [r.value]]
                case .domain: rule = ["domain": [r.value]]
                case .domainKeyword: rule = ["domain_keyword": [r.value]]
                case .ipCidr: rule = ["ip_cidr": [r.value]]
                case .geoip: rule = r.value.uppercased() == "LAN" ? ["ip_is_private": true] : ["rule_set": "geoip-\(r.value.lowercased())"]
                }
                switch r.action {
                case .proxy: rule["outbound"] = hasProxy ? "proxy" : "direct"
                case .direct: rule["outbound"] = "direct"
                case .reject: rule["action"] = "reject"
                }
                rules.append(rule)
                // Domains sent direct also resolve with the direct DNS so CDNs pick a nearby server.
                if r.action == .direct, r.type != .ipCidr, r.type != .geoip {
                    var d = rule; d.removeValue(forKey: "outbound"); d["server"] = "dns-direct"
                    dnsRules.append(d)
                }
            }
            if s.bypassHome, !home.isEmpty, !homeIsChina {
                let tld = ["." + Country.ccTld(home)]
                rules.append(["domain_suffix": tld, "outbound": "direct"])
                if homeGeoip != nil { rules.append(["rule_set": "geoip-home", "outbound": "direct"]) }
                dnsRules.append(["domain_suffix": tld, "server": "dns-direct"])
            }
            if bypassChina {
                rules.append(["rule_set": ["geosite-cn", "geoip-cn"], "outbound": "direct"])
                dnsRules.append(["rule_set": "geosite-cn", "server": "dns-direct"])
            }
        }
        let route: JSON = ["rules": rules, "rule_set": ruleSets, "final": mode == .direct ? "direct" : "proxy",
                           "auto_detect_interface": true, "default_domain_resolver": "dns-direct"]

        // dns
        var dnsServers: [JSON] = [["type": "udp", "tag": "dns-direct", "server": s.directDns]]
        if hasProxy { dnsServers.append(["type": "https", "tag": "dns-remote", "server": s.remoteDns, "detour": "proxy"]) }
        let dns: JSON = ["servers": dnsServers, "rules": dnsRules,
                         "final": (mode == .direct || !hasProxy) ? "dns-direct" : "dns-remote",
                         "strategy": s.ipv6 ? "prefer_ipv4" : "ipv4_only"]

        // tun
        var address = ["172.19.0.1/30"]
        if s.ipv6 { address.append("fdfe:dcba:9876::1/126") }
        // macOS tunnels max out far below the 9000 bytes iPhone and iPad accept.
        let mtu = ProcessInfo.processInfo.isiOSAppOnMac ? 1500 : 9000
        let tun: JSON = ["type": "tun", "tag": "tun-in", "address": address, "mtu": mtu,
                         "auto_route": true, "strict_route": true, "stack": "gvisor"]

        let config: JSON = [
            "log": ["level": "info"],
            // Reality rejects clients whose clock is off; don't trust the device clock.
            "ntp": ["enabled": true, "server": "ntp.aliyun.com", "server_port": 123, "interval": "30m", "domain_resolver": "dns-direct"],
            "dns": dns,
            "inbounds": [tun],
            "outbounds": outbounds,
            "route": route,
            "experimental": ["cache_file": ["enabled": true]],
        ]
        let data = (try? JSONSerialization.data(withJSONObject: config, options: [.prettyPrinted, .sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// Writes the config where the tunnel extension reads it.
    static func write(_ state: AppState) {
        try? build(state).write(to: AppGroup.configURL, atomically: true, encoding: .utf8)
    }
}
