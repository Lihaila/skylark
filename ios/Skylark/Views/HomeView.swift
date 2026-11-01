import SwiftUI

struct HomeView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    @EnvironmentObject var toast: Toast
    var openServers: () -> Void
    @State private var scanning = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if store.state.nodes.isEmpty {
                        welcome
                    } else {
                        connectButton.padding(.top, 8)
                        statusText
                        serverCard
                        modeCard
                        if vpn.isOn { trafficRow }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Skylark")
        }
        .sheet(isPresented: $scanning) { QRScannerSheet() }
        .onChange(of: vpn.error) { err in if let err { toast.show(String(localized: "Error: \(err)")) } }
    }

    private var welcome: some View {
        VStack(spacing: 16) {
            Image(systemName: "power").font(.system(size: 48, weight: .semibold)).foregroundStyle(Color.brand)
                .frame(width: 120, height: 120).background(Color.brand.opacity(0.1), in: Circle())
                .padding(.top, 32)
            Text("Add a server to get started").font(.title2.weight(.semibold)).multilineTextAlignment(.center)
            Text("Copy a link (ss://, vmess://, vless://, trojan://, hy2://…) or a subscription URL from your provider, then tap Paste.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button { ImportActions.paste() } label: {
                Label("Paste from clipboard", systemImage: "doc.on.clipboard").frame(maxWidth: .infinity).frame(height: 44)
            }.buttonStyle(.borderedProminent)
            Button { scanning = true } label: {
                Label("Scan QR code", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity).frame(height: 44)
            }.buttonStyle(.bordered)
        }
    }

    private var connectButton: some View {
        let on = vpn.isOn
        return Button { vpn.toggle() } label: {
            ZStack {
                Circle().fill((on ? Color.brand : Color.gray).opacity(0.15)).frame(width: 200, height: 200)
                Circle().fill(on ? Color.brand : Color.gray.opacity(0.7)).frame(width: 150, height: 150)
                Image(systemName: "power").font(.system(size: 60, weight: .semibold)).foregroundStyle(.white)
            }
            .scaleEffect(vpn.isBusy ? 1.06 : 1)
            .animation(vpn.isBusy ? .easeInOut(duration: 0.7).repeatForever(autoreverses: true) : .default, value: vpn.isBusy)
        }
        .buttonStyle(.plain)
        .disabled(vpn.isBusy)
        .accessibilityLabel(on ? Text("Disconnect") : Text("Connect"))
    }

    @ViewBuilder private var statusText: some View {
        VStack(spacing: 4) {
            switch vpn.status {
            case .connected: Text("Connected").font(.headline)
            case .connecting, .reasserting: Text("Connecting…").font(.headline)
            case .disconnecting: Text("Disconnecting…").font(.headline)
            default: Text("Tap to connect").font(.headline)
            }
            if vpn.isOn, let since = vpn.connectedDate {
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    let s = max(0, Int(ctx.date.timeIntervalSince(since)))
                    Text(String(format: "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60))
                        .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var serverCard: some View {
        Button(action: openServers) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Server").font(.caption).foregroundStyle(.secondary)
                    Text(store.state.selected?.name ?? String(localized: "No server yet")).font(.headline).lineLimit(1)
                    if let n = store.state.selected { Text(typeLabel(n.type)).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(16)
            .background(Color.card, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    private var modeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Mode").font(.caption).foregroundStyle(.secondary)
            Picker("Mode", selection: Binding(get: { store.state.mode }, set: { store.setMode($0); vpn.reload() })) {
                Text("Smart").tag(Mode.rule)
                Text("Global").tag(Mode.global)
                Text("Direct").tag(Mode.direct)
            }
            .pickerStyle(.segmented)
            Group {
                switch store.state.mode {
                case .rule: Text("Sites in your country connect directly, everything else uses the proxy. Best for daily use.")
                case .global: Text("Everything goes through the proxy.")
                case .direct: Text("Nothing goes through the proxy.")
                }
            }
            .font(.caption).foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Color.card, in: RoundedRectangle(cornerRadius: 16))
    }

    private var trafficRow: some View {
        HStack(spacing: 12) {
            trafficCard("arrow.up", String(localized: "Upload"), vpn.traffic.up, vpn.traffic.upTotal)
            trafficCard("arrow.down", String(localized: "Download"), vpn.traffic.down, vpn.traffic.downTotal)
        }
    }

    private func trafficCard(_ icon: String, _ label: String, _ speed: Int64, _ total: Int64) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(label, systemImage: icon).font(.caption).foregroundStyle(.secondary)
            Text("\(formatBytes(speed))/s").font(.headline.monospacedDigit())
            Text(formatBytes(total)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.card, in: RoundedRectangle(cornerRadius: 16))
    }
}
