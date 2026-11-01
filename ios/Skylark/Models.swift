import Foundation

/// A proxy server. `outbound` is a sing-box outbound object (JSON, without "tag").
struct Node: Codable, Identifiable, Equatable {
    var id = UUID().uuidString
    var name: String
    var outbound: String
    var subId: String?

    private var json: [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(outbound.utf8))) as? [String: Any] ?? [:]
    }
    var type: String { json["type"] as? String ?? "?" }
    var server: String { json["server"] as? String ?? "" }
    var port: Int { json["server_port"] as? Int ?? 0 }
}

struct Subscription: Codable, Identifiable, Equatable {
    var id = UUID().uuidString
    var name: String
    var url: String
    var updatedAt: Date?
}

enum Mode: String, Codable, CaseIterable { case rule = "RULE", global = "GLOBAL", direct = "DIRECT" }

enum RuleType: String, Codable, CaseIterable, Identifiable {
    case domainSuffix = "DOMAIN_SUFFIX", domain = "DOMAIN", domainKeyword = "DOMAIN_KEYWORD", ipCidr = "IP_CIDR", geoip = "GEOIP"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .domainSuffix: "DOMAIN-SUFFIX"
        case .domain: "DOMAIN"
        case .domainKeyword: "DOMAIN-KEYWORD"
        case .ipCidr: "IP-CIDR"
        case .geoip: "GEOIP"
        }
    }
}

enum RuleAction: String, Codable, CaseIterable { case proxy = "PROXY", direct = "DIRECT", reject = "REJECT" }

struct Rule: Codable, Identifiable, Equatable {
    var id = UUID().uuidString
    var type: RuleType
    var value: String
    var action: RuleAction
}

struct Settings: Codable, Equatable {
    /// Smart mode sends sites in the user's own country direct.
    var bypassHome = true
    /// ISO country code; empty means auto-detect.
    var homeCountry = ""
    var bypassChina = false
    var ipv6 = false
    var remoteDns = "1.1.1.1"
    var directDns = "1.1.1.1"
}

struct AppState: Codable, Equatable {
    var nodes: [Node] = []
    var subs: [Subscription] = []
    var selectedId: String?
    var mode: Mode = .rule
    var rules: [Rule] = []
    var settings = Settings()

    var selected: Node? { nodes.first { $0.id == selectedId } ?? nodes.first }
}
