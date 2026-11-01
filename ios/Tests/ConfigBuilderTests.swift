import XCTest
@testable import Skylark

/// Routing decisions in the generated sing-box config.
final class ConfigBuilderTests: XCTestCase {
    private let node = Node(name: "LA", outbound: #"{"type":"trojan","server":"t.example","server_port":443,"password":"x","tls":{"enabled":true}}"#)

    private func build(_ change: (inout AppState) -> Void = { _ in }) throws -> [String: Any] {
        var s = AppState()
        s.nodes = [node]
        s.selectedId = node.id
        s.settings.homeCountry = "JP"   // deterministic, independent of the simulator's region
        change(&s)
        let text = ConfigBuilder.build(s)
        return try XCTUnwrap((try JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any])
    }
    private func route(_ c: [String: Any]) -> [String: Any] { c["route"] as? [String: Any] ?? [:] }
    private func rules(_ c: [String: Any]) -> [[String: Any]] { route(c)["rules"] as? [[String: Any]] ?? [] }

    func testSmartModeSendsHomeCountryDirect() throws {
        let c = try build()
        XCTAssertEqual(route(c)["final"] as? String, "proxy")
        XCTAssertTrue(rules(c).contains { ($0["domain_suffix"] as? [String]) == [".jp"] && $0["outbound"] as? String == "direct" })
        let outbounds = c["outbounds"] as? [[String: Any]] ?? []
        XCTAssertEqual(outbounds.first?["tag"] as? String, "proxy")
        XCTAssertEqual(outbounds.first?["type"] as? String, "trojan")
    }

    func testUnitedKingdomUsesUkDomain() throws {
        let c = try build { $0.settings.homeCountry = "GB" }
        XCTAssertTrue(rules(c).contains { ($0["domain_suffix"] as? [String]) == [".uk"] })
    }

    func testChinaBypassAddsBundledRuleSets() throws {
        let c = try build { $0.settings.bypassChina = true }
        let sets = (route(c)["rule_set"] as? [[String: Any]] ?? []).compactMap { $0["tag"] as? String }
        XCTAssertTrue(sets.contains("geosite-cn") && sets.contains("geoip-cn"))
    }

    func testGlobalModeHasNoCountryRules() throws {
        let c = try build { $0.mode = .global }
        XCTAssertEqual(route(c)["final"] as? String, "proxy")
        XCTAssertFalse(rules(c).contains { $0["domain_suffix"] != nil })
    }

    func testDirectModeSendsEverythingDirect() throws {
        let c = try build { $0.mode = .direct }
        XCTAssertEqual(route(c)["final"] as? String, "direct")
        XCTAssertEqual((c["dns"] as? [String: Any])?["final"] as? String, "dns-direct")
    }

    func testNoServerFallsBackToDirect() throws {
        let c = try build { $0.nodes = []; $0.selectedId = nil }
        XCTAssertEqual(route(c)["final"] as? String, "direct")
        let servers = ((c["dns"] as? [String: Any])?["servers"] as? [[String: Any]] ?? []).compactMap { $0["tag"] as? String }
        XCTAssertEqual(servers, ["dns-direct"], "no proxy, so no remote DNS through it")
    }

    func testCustomRulesKeepOrderAndActions() throws {
        let c = try build {
            $0.rules = [Rule(type: .domainSuffix, value: "bank.example", action: .direct),
                        Rule(type: .domainKeyword, value: "ads", action: .reject),
                        Rule(type: .ipCidr, value: "8.8.8.0/24", action: .proxy)]
        }
        let r = rules(c)
        let i1 = try XCTUnwrap(r.firstIndex { ($0["domain_suffix"] as? [String]) == ["bank.example"] })
        let i2 = try XCTUnwrap(r.firstIndex { ($0["domain_keyword"] as? [String]) == ["ads"] })
        let i3 = try XCTUnwrap(r.firstIndex { ($0["ip_cidr"] as? [String]) == ["8.8.8.0/24"] })
        XCTAssertTrue(i1 < i2 && i2 < i3)
        XCTAssertEqual(r[i1]["outbound"] as? String, "direct")
        XCTAssertEqual(r[i2]["action"] as? String, "reject")
        XCTAssertEqual(r[i3]["outbound"] as? String, "proxy")
        // Domains sent direct also resolve via the direct DNS.
        let dnsRules = (c["dns"] as? [String: Any])?["rules"] as? [[String: Any]] ?? []
        XCTAssertTrue(dnsRules.contains { ($0["domain_suffix"] as? [String]) == ["bank.example"] && $0["server"] as? String == "dns-direct" })
    }

    func testTunAndClockSettings() throws {
        let c = try build()
        let tun = try XCTUnwrap((c["inbounds"] as? [[String: Any]])?.first)
        XCTAssertEqual(tun["stack"] as? String, "gvisor")
        XCTAssertEqual(tun["mtu"] as? Int, 9000, "iPhone/iPad (and the simulator) use 9000; only the Mac caps it")
        XCTAssertEqual(tun["address"] as? [String], ["172.19.0.1/30"])
        XCTAssertEqual((c["dns"] as? [String: Any])?["strategy"] as? String, "ipv4_only")
        XCTAssertEqual((c["ntp"] as? [String: Any])?["enabled"] as? Bool, true, "Reality needs an accurate clock")
    }

    func testIPv6AddsAddressAndPrefersV4() throws {
        let c = try build { $0.settings.ipv6 = true }
        let tun = try XCTUnwrap((c["inbounds"] as? [[String: Any]])?.first)
        XCTAssertEqual((tun["address"] as? [String])?.count, 2)
        XCTAssertEqual((c["dns"] as? [String: Any])?["strategy"] as? String, "prefer_ipv4")
    }
}
