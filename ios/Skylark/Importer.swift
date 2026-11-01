import Foundation

enum ImportResult {
    case nodes(Int), sub(Int), failed(String), nothing

    var message: String {
        switch self {
        case .nodes(let n): String(localized: "Added \(n) server(s)")
        case .sub(let n): String(localized: "Subscription added: \(n) server(s)")
        case .failed(let m): String(localized: "Subscription failed: \(m)")
        case .nothing: String(localized: "No server link found in the clipboard")
        }
    }
}

/// One entry point for anything the user pastes, scans or opens in the app.
@MainActor
enum Importer {
    static func importText(_ raw: String?) async -> ImportResult {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .nothing }
        if text.lowercased().hasPrefix("skylark://"),
           let url = URLComponents(string: text)?.queryItems?.first(where: { $0.name == "url" })?.value {
            return await addSubscription(url, name: "")
        }
        // A bare http(s) URL is a subscription.
        if text.range(of: "^https?://\\S+$", options: [.regularExpression, .caseInsensitive]) != nil {
            return await addSubscription(text, name: "")
        }
        let nodes = LinkParser.parseText(text)
        if nodes.isEmpty { return .nothing }
        Store.shared.addNodes(nodes)
        return .nodes(nodes.count)
    }

    static func addSubscription(_ url: String, name: String) async -> ImportResult {
        let host = URL(string: url)?.host ?? "Subscription"
        let sub = Subscription(name: name.trimmingCharacters(in: .whitespaces).isEmpty ? host : name, url: url.trimmingCharacters(in: .whitespaces))
        do { return .sub(try await NetworkUtil.updateSubscription(sub)) } catch { return .failed(error.localizedDescription) }
    }

    /// Returns (updated count, last error).
    static func updateAll() async -> (Int, String?) {
        var ok = 0
        var lastError: String?
        for sub in Store.shared.state.subs {
            do { _ = try await NetworkUtil.updateSubscription(sub); ok += 1 } catch { lastError = "\(sub.name): \(error.localizedDescription)" }
        }
        return (ok, lastError)
    }
}
