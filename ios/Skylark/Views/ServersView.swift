import SwiftUI

/// Latency results survive tab switches but not app restarts. -1 = timeout, -2 = testing.
@MainActor
final class Latency: ObservableObject {
    static let shared = Latency()
    @Published var ms: [String: Int] = [:]

    func testAll(_ nodes: [Node]) {
        for n in nodes { ms[n.id] = -2 }
        for n in nodes {
            Task { let r = await NetworkUtil.tcpPing(n); ms[n.id] = r }
        }
    }
}

struct ServersView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    @EnvironmentObject var toast: Toast
    @StateObject private var latency = Latency.shared
    @State private var sheet: ServerSheet?
    @State private var updating = false

    enum ServerSheet: Identifiable {
        case subscription, manual, scan, edit(Node)
        var id: String {
            switch self {
            case .subscription: "sub"
            case .manual: "manual"
            case .scan: "scan"
            case .edit(let n): "edit-\(n.id)"
            }
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if store.state.nodes.isEmpty {
                    ContentUnavailableCompat(text: String(localized: "No servers. Tap + to add one.", bundle: .app))
                } else {
                    List {
                        let manual = store.state.nodes.filter { $0.subId == nil }
                        if !manual.isEmpty {
                            Section { ForEach(manual) { row($0) } } header: { Text("My servers · \(manual.count)") }
                        }
                        ForEach(store.state.subs) { sub in
                            let nodes = store.state.nodes.filter { $0.subId == sub.id }
                            Section {
                                ForEach(nodes) { row($0) }
                            } header: {
                                HStack {
                                    Text("\(sub.name) · \(nodes.count)")
                                    Spacer()
                                    Menu {
                                        Button("Update") { Task { await update(sub) } }
                                        Button("Delete", role: .destructive) { store.deleteSub(sub.id) }
                                    } label: { Image(systemName: "ellipsis.circle") }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Servers")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { latency.testAll(store.state.nodes) } label: { Image(systemName: "speedometer") }
                        .accessibilityLabel("Test speed")
                    if !store.state.subs.isEmpty {
                        Button { Task { await updateAll() } } label: {
                            if updating { ProgressView() } else { Image(systemName: "arrow.clockwise") }
                        }
                        .disabled(updating)
                        .accessibilityLabel("Update subscriptions")
                    }
                    Menu {
                        Button { ImportActions.paste() } label: { Label("Paste from clipboard", systemImage: "doc.on.clipboard") }
                        Button { sheet = .scan } label: { Label("Scan QR code", systemImage: "qrcode.viewfinder") }
                        Button { sheet = .subscription } label: { Label("Add subscription", systemImage: "link") }
                        Button { sheet = .manual } label: { Label("Add manually", systemImage: "pencil") }
                    } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add")
                }
            }
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .subscription: SubscriptionSheet()
            case .manual: ManualAddSheet()
            case .scan: QRScannerSheet()
            case .edit(let n): EditNodeSheet(node: n)
            }
        }
    }

    private func row(_ n: Node) -> some View {
        Button {
            store.select(n.id)
            vpn.reload()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: n.id == store.state.selected?.id ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(n.id == store.state.selected?.id ? Color.brand : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(n.name).lineLimit(1).foregroundStyle(.primary)
                    Text(typeLabel(n.type)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                latencyView(latency.ms[n.id])
            }
            .contentShape(Rectangle())   // the whole row is tappable
        }
        .buttonStyle(.plain)             // keep names in normal text colour, not link blue
        .swipeActions {
            Button("Delete", role: .destructive) { store.deleteNode(n.id) }
            Button("Edit") { sheet = .edit(n) }.tint(.brand)
        }
        .contextMenu {
            Button("Edit") { sheet = .edit(n) }
            Button("Delete", role: .destructive) { store.deleteNode(n.id) }
        }
    }

    @ViewBuilder private func latencyView(_ ms: Int?) -> some View {
        if let ms {
            if ms == -2 { ProgressView().controlSize(.small) }
            else if ms < 0 { Text("Timeout").font(.caption.weight(.medium)).foregroundStyle(.red) }
            else {
                Text("\(ms) ms").font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(ms < 150 ? .green : (ms < 400 ? .orange : .red))
            }
        }
    }

    private func update(_ sub: Subscription) async {
        do {
            _ = try await NetworkUtil.updateSubscription(sub)
            toast.show(String(localized: "Updated \(1) subscription(s)", bundle: .app))
            vpn.reload()
        } catch { toast.show(String(localized: "Subscription failed: \(error.localizedDescription)", bundle: .app)) }
    }

    private func updateAll() async {
        updating = true
        let (ok, err) = await Importer.updateAll()
        updating = false
        toast.show(err.map { String(localized: "Subscription failed: \($0)", bundle: .app) } ?? String(localized: "Updated \(ok) subscription(s)", bundle: .app))
        vpn.reload()
    }
}

/// ContentUnavailableView needs iOS 17; this works on 16 too.
struct ContentUnavailableCompat: View {
    let text: String
    var body: some View {
        Text(text).foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct SubscriptionSheet: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var toast: Toast
    @State private var url = ""
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Subscription URL", text: $url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Name (optional)", text: $name)
            }
            .navigationTitle("Add subscription")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let u = url, n = name
                        dismiss()
                        Task { toast.show(await Importer.addSubscription(u, name: n).message) }
                    }.disabled(!url.lowercased().hasPrefix("http"))
                }
            }
        }
    }
}

private let ssMethods = ["2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
                         "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305"]

struct ManualAddSheet: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var store: Store
    @State private var type = "shadowsocks"
    @State private var name = ""
    @State private var server = ""
    @State private var port = ""
    @State private var secret = ""
    @State private var method = "aes-256-gcm"
    @State private var tls = true
    @State private var sni = ""
    @State private var transport = "tcp"
    @State private var path = ""
    @State private var host = ""
    @State private var showError = false

    private var usesTlsToggle: Bool { type == "vmess" || type == "vless" }
    private var usesTransport: Bool { ["vmess", "vless", "trojan"].contains(type) }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Type", selection: $type) {
                    ForEach(["shadowsocks", "vmess", "vless", "trojan", "hysteria2"], id: \.self) { Text(typeLabel($0)).tag($0) }
                }
                Section {
                    TextField("Name", text: $name)
                    TextField("Address", text: $server).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Port", text: $port).keyboardType(.numberPad)
                    TextField(type == "vmess" || type == "vless" ? "UUID" : String(localized: "Password", bundle: .app), text: $secret)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    if type == "shadowsocks" {
                        Picker("Encryption", selection: $method) { ForEach(ssMethods, id: \.self) { Text($0).tag($0) } }
                    }
                }
                if type != "shadowsocks" {
                    Section {
                        if usesTlsToggle { Toggle("TLS", isOn: $tls) }
                        if tls || !usesTlsToggle {
                            TextField("SNI (optional)", text: $sni).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                    }
                }
                if usesTransport {
                    Section("Transport") {
                        Picker("Transport", selection: $transport) {
                            Text("TCP").tag("tcp"); Text("WS").tag("ws"); Text("gRPC").tag("grpc")
                        }.pickerStyle(.segmented)
                        if transport != "tcp" {
                            TextField(transport == "grpc" ? String(localized: "Service name", bundle: .app) : String(localized: "Path (optional)", bundle: .app), text: $path)
                                .textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                        if transport == "ws" {
                            TextField("Host (optional)", text: $host).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                    }
                }
                if showError { Text("Please fill in address, port and password/UUID").foregroundStyle(.red) }
            }
            .navigationTitle("Add manually")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save) }
            }
        }
    }

    private func save() {
        let srv = server.trimmingCharacters(in: .whitespaces), sec = secret.trimmingCharacters(in: .whitespaces)
        guard !srv.isEmpty, let p = Int(port), !sec.isEmpty else { showError = true; return }
        var o: [String: Any] = ["type": type, "server": srv, "server_port": p]
        switch type {
        case "shadowsocks": o["method"] = method; o["password"] = sec
        case "vmess": o["uuid"] = sec; o["security"] = "auto"; o["alter_id"] = 0
        case "vless": o["uuid"] = sec
        default: o["password"] = sec
        }
        if type != "shadowsocks" && (tls || !usesTlsToggle) {
            o["tls"] = ["enabled": true, "server_name": sni.isEmpty ? srv : sni]
        }
        if usesTransport {
            if transport == "ws" {
                var t: [String: Any] = ["type": "ws", "path": path.isEmpty ? "/" : path]
                if !host.isEmpty { t["headers"] = ["Host": host] }
                o["transport"] = t
            } else if transport == "grpc" {
                o["transport"] = ["type": "grpc", "service_name": path]
            }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) else { return }
        store.upsert(Node(name: name.isEmpty ? "\(srv):\(p)" : name, outbound: String(decoding: data, as: UTF8.self)))
        dismiss()
    }
}

struct EditNodeSheet: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    let node: Node
    @State private var name = ""
    @State private var json = ""
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                Section("Advanced (sing-box JSON)") {
                    TextEditor(text: $json).font(.system(.footnote, design: .monospaced)).frame(minHeight: 240)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    if invalid { Text("Invalid JSON").foregroundStyle(.red) }
                }
            }
            .navigationTitle("Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard var o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any], o["type"] != nil
                        else { invalid = true; return }
                        o.removeValue(forKey: "tag")
                        guard let data = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) else { invalid = true; return }
                        var n = node
                        n.name = name.isEmpty ? node.name : name
                        n.outbound = String(decoding: data, as: UTF8.self)
                        store.upsert(n)
                        vpn.reload()
                        dismiss()
                    }
                }
            }
            .onAppear {
                name = node.name
                if let o = try? JSONSerialization.jsonObject(with: Data(node.outbound.utf8)),
                   let d = try? JSONSerialization.data(withJSONObject: o, options: [.prettyPrinted, .sortedKeys]) {
                    json = String(decoding: d, as: UTF8.self)
                } else { json = node.outbound }
            }
        }
    }
}
