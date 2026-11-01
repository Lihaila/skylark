import Foundation

/// Single source of truth, persisted as one JSON file in the App Group container.
@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var state: AppState
    private let file = AppGroup.container.appendingPathComponent("state.json")

    private init() {
        if let data = try? Data(contentsOf: file), let s = try? JSONDecoder().decode(AppState.self, from: data) {
            state = s
        } else {
            // First run: China bypass and the Alibaba DNS only make sense for people in mainland China.
            var s = AppState()
            let inChina = Country.detect() == "CN"
            s.settings.bypassChina = inChina
            s.settings.directDns = inChina ? "223.5.5.5" : "1.1.1.1"
            state = s
            save()
        }
    }

    func update(_ change: (inout AppState) -> Void) {
        change(&state)
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(state) { try? data.write(to: file, options: .atomic) }
    }

    func select(_ id: String) { update { $0.selectedId = id } }
    func setMode(_ m: Mode) { update { $0.mode = m } }

    func addNodes(_ nodes: [Node]) {
        update { s in
            s.nodes += nodes
            if s.selectedId == nil { s.selectedId = s.nodes.first?.id }
        }
    }

    func upsert(_ node: Node) {
        update { s in
            if let i = s.nodes.firstIndex(where: { $0.id == node.id }) { s.nodes[i] = node } else { s.nodes.append(node) }
            if s.selectedId == nil { s.selectedId = node.id }
        }
    }

    func deleteNode(_ id: String) { update { $0.nodes.removeAll { $0.id == id } } }

    /// Replace all nodes of `sub`, keeping the selection if a node with the same name survives.
    func replaceSubNodes(_ sub: Subscription, _ nodes: [Node]) {
        update { s in
            let selectedName = s.selected?.subId == sub.id ? s.selected?.name : nil
            let tagged = nodes.map { var n = $0; n.subId = sub.id; return n }
            s.nodes = s.nodes.filter { $0.subId != sub.id } + tagged
            if let name = selectedName, let n = tagged.first(where: { $0.name == name }) { s.selectedId = n.id }
            if let i = s.subs.firstIndex(where: { $0.id == sub.id }) { s.subs[i] = sub } else { s.subs.append(sub) }
            if s.selected == nil || !s.nodes.contains(where: { $0.id == s.selectedId }) { s.selectedId = s.nodes.first?.id }
        }
    }

    func deleteSub(_ id: String) {
        update { s in
            s.subs.removeAll { $0.id == id }
            s.nodes.removeAll { $0.subId == id }
        }
    }

    func addRule(_ r: Rule) { update { $0.rules.insert(r, at: 0) } }
    func deleteRule(_ id: String) { update { $0.rules.removeAll { $0.id == id } } }
}
