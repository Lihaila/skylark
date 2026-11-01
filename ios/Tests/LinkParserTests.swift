import XCTest
@testable import Skylark

/// Real-world link shapes for every supported scheme and subscription format.
final class LinkParserTests: XCTestCase {
    private func json(_ n: Node) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(n.outbound.utf8))) as? [String: Any] ?? [:]
    }
    private func one(_ text: String, file: StaticString = #filePath, line: UInt = #line) throws -> (Node, [String: Any]) {
        let nodes = LinkParser.parseText(text)
        XCTAssertEqual(nodes.count, 1, "expected one node from: \(text)", file: file, line: line)
        let n = try XCTUnwrap(nodes.first, file: file, line: line)
        return (n, json(n))
    }

    // MARK: Shadowsocks

    func testShadowsocksSIP002() throws {
        // userinfo = base64("aes-256-gcm:secret")
        let (n, o) = try one("ss://YWVzLTI1Ni1nY206c2VjcmV0@1.2.3.4:8388#My%20SS")
        XCTAssertEqual(n.name, "My SS")
        XCTAssertEqual(o["type"] as? String, "shadowsocks")
        XCTAssertEqual(o["server"] as? String, "1.2.3.4")
        XCTAssertEqual(o["server_port"] as? Int, 8388)
        XCTAssertEqual(o["method"] as? String, "aes-256-gcm")
        XCTAssertEqual(o["password"] as? String, "secret")
    }

    func testShadowsocksLegacyFullBase64() throws {
        // base64("chacha20-ietf-poly1305:pass@example.com:443")
        let b64 = Data("chacha20-ietf-poly1305:pass@example.com:443".utf8).base64EncodedString()
        let (n, o) = try one("ss://\(b64)#Legacy")
        XCTAssertEqual(n.name, "Legacy")
        XCTAssertEqual(o["server"] as? String, "example.com")
        XCTAssertEqual(o["server_port"] as? Int, 443)
        XCTAssertEqual(o["method"] as? String, "chacha20-ietf-poly1305")
        XCTAssertEqual(o["password"] as? String, "pass")
    }

    func testShadowsocks2022PlainUserinfoAndPlugin() throws {
        let (_, o) = try one("ss://2022-blake3-aes-128-gcm:abc%2Bdef%3D%3D@h.example:8443/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#x")
        XCTAssertEqual(o["method"] as? String, "2022-blake3-aes-128-gcm")
        XCTAssertEqual(o["password"] as? String, "abc+def==")
        XCTAssertEqual(o["plugin"] as? String, "obfs-local")
        XCTAssertEqual(o["plugin_opts"] as? String, "obfs=http;obfs-host=bing.com")
    }

    // MARK: VMess

    func testVmessWebSocketTLS() throws {
        let cfg: [String: Any] = ["v": "2", "ps": "日本 01", "add": "jp.example.com", "port": "443", "id": "11111111-2222-3333-4444-555555555555",
                                  "aid": "0", "scy": "auto", "net": "ws", "host": "cdn.example.com", "path": "/ray?ed=2048", "tls": "tls", "sni": "jp.example.com"]
        let b64 = try JSONSerialization.data(withJSONObject: cfg).base64EncodedString()
        let (n, o) = try one("vmess://\(b64)")
        XCTAssertEqual(n.name, "日本 01")
        XCTAssertEqual(o["type"] as? String, "vmess")
        XCTAssertEqual(o["server_port"] as? Int, 443)
        XCTAssertEqual(o["uuid"] as? String, "11111111-2222-3333-4444-555555555555")
        let tls = try XCTUnwrap(o["tls"] as? [String: Any])
        XCTAssertEqual(tls["server_name"] as? String, "jp.example.com")
        let t = try XCTUnwrap(o["transport"] as? [String: Any])
        XCTAssertEqual(t["type"] as? String, "ws")
        XCTAssertEqual(t["path"] as? String, "/ray")
        XCTAssertEqual(t["max_early_data"] as? Int, 2048)
        XCTAssertEqual((t["headers"] as? [String: Any])?["Host"] as? String, "cdn.example.com")
    }

    // MARK: VLESS

    func testVlessRealityNormalizesPaddedKey() throws {
        // A padded, standard-alphabet key must become unpadded URL-safe (sing-box rejects the padded form).
        let (n, o) = try one("vless://uuid-1@203.0.113.9:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=ab%2Bcd%2Fef%3D&sid=0123abcd&type=tcp#Reality")
        XCTAssertEqual(n.name, "Reality")
        XCTAssertEqual(o["flow"] as? String, "xtls-rprx-vision")
        let tls = try XCTUnwrap(o["tls"] as? [String: Any])
        XCTAssertEqual(tls["server_name"] as? String, "www.microsoft.com")
        let reality = try XCTUnwrap(tls["reality"] as? [String: Any])
        XCTAssertEqual(reality["public_key"] as? String, "ab-cd_ef")
        XCTAssertEqual(reality["short_id"] as? String, "0123abcd")
        XCTAssertEqual((tls["utls"] as? [String: Any])?["fingerprint"] as? String, "chrome")
        XCTAssertNil(o["transport"], "plain tcp needs no transport")
    }

    func testVlessGrpcNoTLS() throws {
        let (_, o) = try one("vless://u@h.example:80?type=grpc&serviceName=svc&security=none#g")
        XCTAssertNil(o["tls"])
        XCTAssertEqual((o["transport"] as? [String: Any])?["service_name"] as? String, "svc")
    }

    // MARK: Trojan, Hysteria2, TUIC, AnyTLS, SOCKS

    func testTrojanDefaultsToTLS() throws {
        let (_, o) = try one("trojan://p%40ss@t.example.com:443?sni=t.example.com&type=ws&path=%2Fws#T")
        XCTAssertEqual(o["type"] as? String, "trojan")
        XCTAssertEqual(o["password"] as? String, "p@ss")
        XCTAssertEqual((o["tls"] as? [String: Any])?["enabled"] as? Bool, true)
        XCTAssertEqual((o["transport"] as? [String: Any])?["path"] as? String, "/ws")
    }

    func testHysteria2WithObfsAndPortHopping() throws {
        let (_, o) = try one("hysteria2://secret@hy.example.com:443,20000-30000/?sni=hy.example.com&obfs=salamander&obfs-password=xyz&insecure=1#HY")
        XCTAssertEqual(o["type"] as? String, "hysteria2")
        XCTAssertEqual(o["server_port"] as? Int, 443)
        XCTAssertEqual(o["password"] as? String, "secret")
        XCTAssertEqual((o["obfs"] as? [String: Any])?["password"] as? String, "xyz")
        XCTAssertEqual((o["tls"] as? [String: Any])?["insecure"] as? Bool, true)
    }

    func testHy2Alias() throws {
        let (_, o) = try one("hy2://pw@1.1.1.2:8443#short")
        XCTAssertEqual(o["type"] as? String, "hysteria2")
        XCTAssertEqual(o["server_port"] as? Int, 8443)
    }

    func testTuic() throws {
        let (_, o) = try one("tuic://uuid-x:pw@tuic.example.com:443?congestion_control=cubic&alpn=h3&sni=tuic.example.com#TU")
        XCTAssertEqual(o["uuid"] as? String, "uuid-x")
        XCTAssertEqual(o["password"] as? String, "pw")
        XCTAssertEqual(o["congestion_control"] as? String, "cubic")
        XCTAssertEqual((o["tls"] as? [String: Any])?["alpn"] as? [String], ["h3"])
    }

    func testAnyTls() throws {
        let (_, o) = try one("anytls://pw@a.example.com:443?sni=a.example.com#A")
        XCTAssertEqual(o["type"] as? String, "anytls")
        XCTAssertEqual(o["password"] as? String, "pw")
    }

    func testSocksWithCredentials() throws {
        let (_, o) = try one("socks5://user:pass@10.0.0.1:1080#S")
        XCTAssertEqual(o["type"] as? String, "socks")
        XCTAssertEqual(o["username"] as? String, "user")
        XCTAssertEqual(o["password"] as? String, "pass")
    }

    // MARK: Subscriptions

    func testBase64LinkListSubscription() {
        let list = ["trojan://a@x.example:443#One", "hy2://b@y.example:443#Two", "not a link", "vless://c@z.example:443?security=tls#Three"]
        let body = Data(list.joined(separator: "\n").utf8).base64EncodedString()
        let nodes = LinkParser.parseText(body)
        XCTAssertEqual(nodes.map(\.name), ["One", "Two", "Three"])
    }

    func testClashYamlSubscription() {
        let yaml = """
        port: 7890
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
        """
        let nodes = LinkParser.parseText(yaml)
        XCTAssertEqual(nodes.map(\.name), ["HK Reality", "SS", "Trojan WS"])
        let reality = ((try? JSONSerialization.jsonObject(with: Data(nodes[0].outbound.utf8))) as? [String: Any])?["tls"] as? [String: Any]
        XCTAssertEqual((reality?["reality"] as? [String: Any])?["public_key"] as? String, "abc")
    }

    func testSingBoxJsonSubscription() {
        let body = #"{"outbounds":[{"type":"selector","tag":"auto"},{"type":"trojan","tag":"US 1","server":"u.example","server_port":443,"password":"x","detour":"z"},{"type":"direct","tag":"direct"}]}"#
        let nodes = LinkParser.parseText(body)
        XCTAssertEqual(nodes.map(\.name), ["US 1"])
        XCTAssertFalse(nodes[0].outbound.contains("detour"), "detour must be stripped")
        XCTAssertFalse(nodes[0].outbound.contains("\"tag\""), "tag is assigned by the config builder")
    }

    func testGarbageGivesNothing() {
        XCTAssertTrue(LinkParser.parseText("hello world").isEmpty)
        XCTAssertTrue(LinkParser.parseText("").isEmpty)
        XCTAssertTrue(LinkParser.parseText("https://example.com/sub").isEmpty, "bare URLs are subscriptions, handled by the importer")
    }
}
