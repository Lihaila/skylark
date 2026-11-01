import Foundation
import Network

enum NetworkUtil {
    /// TCP handshake time to the server in ms, or -1 if unreachable within 3 s.
    static func tcpPing(_ node: Node) async -> Int {
        guard node.port > 0, let port = NWEndpoint.Port(rawValue: UInt16(node.port)) else { return -1 }
        let conn = NWConnection(host: NWEndpoint.Host(node.server), port: port, using: .tcp)
        let start = Date()
        return await withCheckedContinuation { cont in
            let lock = NSLock()
            var done = false
            func finish(_ v: Int) {
                lock.lock(); defer { lock.unlock() }
                guard !done else { return }
                done = true
                conn.cancel()
                cont.resume(returning: v)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(Int(Date().timeIntervalSince(start) * 1000))
                case .failed, .cancelled: finish(-1)
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + 3) { finish(-1) }
        }
    }

    static func fetchSubscription(_ url: String) async throws -> [Node] {
        guard let u = URL(string: url) else { throw ImportError.message("Invalid URL") }
        var req = URLRequest(url: u)
        req.timeoutInterval = 20
        req.setValue("Skylark/1.0 (sing-box 1.14; v2ray links)", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let code = (resp as? HTTPURLResponse)?.statusCode, !(200..<300).contains(code) { throw ImportError.message("HTTP \(code)") }
        let nodes = LinkParser.parseText(String(decoding: data, as: UTF8.self))
        if nodes.isEmpty { throw ImportError.message(String(localized: "No servers found in subscription", bundle: .app)) }
        return nodes
    }

    /// Downloads `sub` and replaces its nodes. Returns the node count.
    @MainActor
    static func updateSubscription(_ sub: Subscription) async throws -> Int {
        let nodes = try await fetchSubscription(sub.url)
        var s = sub
        s.updatedAt = Date()
        Store.shared.replaceSubNodes(s, nodes)
        return nodes.count
    }
}

enum ImportError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case let .message(m) = self { return m }; return nil }
}
