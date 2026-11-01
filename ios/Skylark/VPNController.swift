import Foundation
import NetworkExtension
import Libbox

struct Traffic: Equatable { var up: Int64 = 0, down: Int64 = 0, upTotal: Int64 = 0, downTotal: Int64 = 0 }

/// Installs the VPN profile, starts/stops the packet tunnel and mirrors its state for the UI.
@MainActor
final class VPNController: ObservableObject {
    static let shared = VPNController()

    @Published private(set) var status: NEVPNStatus = .disconnected
    @Published private(set) var connectedDate: Date?
    @Published private(set) var traffic = Traffic()
    @Published var error: String?

    private var manager: NETunnelProviderManager?
    private var commandClient: LibboxCommandClient?
    private var trafficHandler: TrafficHandler?

    private init() {
        NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.refreshStatus() }
        }
        Task { await load() }
    }

    var isOn: Bool { status == .connected }
    var isBusy: Bool { [.connecting, .disconnecting, .reasserting].contains(status) }

    func load() async {
        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = managers.first
        refreshStatus()
    }

    private func ensureManager() async throws -> NETunnelProviderManager {
        if let m = manager { return m }
        let m = NETunnelProviderManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = AppGroup.tunnelBundleID
        proto.serverAddress = "Skylark"
        m.protocolConfiguration = proto
        m.localizedDescription = "Skylark"
        m.isEnabled = true
        try await m.saveToPreferences()   // shows the system "Add VPN Configuration" prompt the first time
        try await m.loadFromPreferences()
        manager = m
        return m
    }

    func toggle() {
        if isOn || status == .connecting { stop() } else { Task { await start() } }
    }

    func start() async {
        error = nil
        try? FileManager.default.removeItem(at: AppGroup.errorURL)
        let state = Store.shared.state
        // Make sure the home country's IP list is there before the first config is built.
        if state.settings.bypassHome {
            let home = Country.effective(state.settings)
            if !home.isEmpty, home != "CN" { await Country.ensureGeoip(home) }
        }
        ConfigBuilder.write(state)
        do {
            let m = try await ensureManager()
            if !m.isEnabled {
                m.isEnabled = true
                try await m.saveToPreferences()
                try await m.loadFromPreferences()
            }
            try m.connection.startVPNTunnel()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func stop() { manager?.connection.stopVPNTunnel() }

    /// Apply new settings to a running connection without reconnecting.
    func reload() {
        ConfigBuilder.write(Store.shared.state)
        guard isOn, let session = manager?.connection as? NETunnelProviderSession else { return }
        try? session.sendProviderMessage(Data("reload".utf8)) { _ in }
    }

    private func refreshStatus() {
        let new = manager?.connection.status ?? .disconnected
        let old = status
        status = new
        if new == .connected {
            connectedDate = manager?.connection.connectedDate ?? Date()
            startTraffic()
        } else if new == .disconnected || new == .invalid {
            connectedDate = nil
            stopTraffic()
            if old != .disconnected, let msg = try? String(contentsOf: AppGroup.errorURL, encoding: .utf8), !msg.isEmpty {
                error = msg
            }
        }
    }

    // MARK: - live traffic from the tunnel's command server (a unix socket in the App Group)

    private static var setupDone = false

    private func startTraffic() {
        guard commandClient == nil else { return }
        if !Self.setupDone {
            let o = LibboxSetupOptions()
            o.basePath = AppGroup.container.path
            o.workingPath = AppGroup.workDir.path
            o.tempPath = AppGroup.tempDir.path
            o.crashReportSource = "app"
            var err: NSError?
            LibboxSetup(o, &err)
            Self.setupDone = err == nil
        }
        let handler = TrafficHandler { [weak self] t in Task { @MainActor in self?.traffic = t } }
        let options = LibboxCommandClientOptions()
        options.addCommand(LibboxCommandStatus)
        options.statusInterval = 1_000_000_000
        guard let client = LibboxNewCommandClient(handler, options) else { return }
        trafficHandler = handler
        commandClient = client
        Task.detached {
            // The command server starts with the tunnel; give it a moment on the first connect.
            for _ in 0..<10 {
                if (try? client.connect()) != nil { return }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    private func stopTraffic() {
        try? commandClient?.disconnect()
        commandClient = nil
        trafficHandler = nil
        traffic = Traffic()
    }
}

private final class TrafficHandler: NSObject, LibboxCommandClientHandlerProtocol {
    let onTraffic: (Traffic) -> Void
    init(_ onTraffic: @escaping (Traffic) -> Void) { self.onTraffic = onTraffic }

    func writeStatus(_ message: LibboxStatusMessage?) {
        guard let m = message else { return }
        // A config reload resets the engine's counters; never show negative values.
        onTraffic(Traffic(up: max(0, m.uplink), down: max(0, m.downlink), upTotal: max(0, m.uplinkTotal), downTotal: max(0, m.downlinkTotal)))
    }
    func clearLogs() {}
    func connected() {}
    func disconnected(_ message: String?) {}
    func initializeClashMode(_ modeList: (any LibboxStringIteratorProtocol)?, currentMode: String?) {}
    func setDefaultLogLevel(_ level: Int32) {}
    func updateClashMode(_ newMode: String?) {}
    func write(_ events: LibboxConnectionEvents?) {}
    func writeGroups(_ message: (any LibboxOutboundGroupIteratorProtocol)?) {}
    func writeLogs(_ messageList: (any LibboxLogIteratorProtocol)?) {}
    func writeOutbounds(_ message: (any LibboxOutboundGroupItemIteratorProtocol)?) {}
}
