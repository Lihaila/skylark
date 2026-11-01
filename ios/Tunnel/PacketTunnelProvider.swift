import Foundation
import Libbox
import Network
import NetworkExtension

/// Runs the sing-box engine inside the packet tunnel extension. The app writes the config to the
/// App Group; this reads it on start and again when the app sends "reload".
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var commandServer: LibboxCommandServer?
    private lazy var platform = TunnelPlatform(provider: self)
    private static var setupDone = false

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        // Off the provider's queue: openTun waits for setTunnelNetworkSettings, whose reply needs that queue.
        DispatchQueue.global(qos: .userInitiated).async { self.start(completionHandler) }
    }

    private func start(_ completionHandler: @escaping (Error?) -> Void) {
        do {
            try? FileManager.default.removeItem(at: AppGroup.errorURL)
            for dir in [AppGroup.workDir, AppGroup.tempDir] {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            }
            if !Self.setupDone {
                let o = LibboxSetupOptions()
                o.basePath = AppGroup.container.path
                o.workingPath = AppGroup.workDir.path
                o.tempPath = AppGroup.tempDir.path
                o.logMaxLines = 300
                o.crashReportSource = "tunnel"
                // Keeps the engine inside the extension's memory budget (~50 MB on iOS).
                o.oomKillerEnabled = true
                var err: NSError?
                LibboxSetup(o, &err)
                if let err { throw err }
                Self.setupDone = true
            }
            var err: NSError?
            guard let server = LibboxNewCommandServer(platform, platform, &err) else { throw err ?? TunnelError("command server") }
            try server.start()
            commandServer = server
            let config = try String(contentsOf: AppGroup.configURL, encoding: .utf8)
            try server.startOrReloadService(config, options: LibboxOverrideOptions())
            completionHandler(nil)
        } catch {
            fail(error)
            completionHandler(error)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        shutdown()
        completionHandler()
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        if String(decoding: messageData, as: UTF8.self) == "reload" {
            DispatchQueue.global(qos: .userInitiated).async { self.reload() }
        }
        completionHandler?(nil)
    }

    func reload() {
        guard let server = commandServer, let config = try? String(contentsOf: AppGroup.configURL, encoding: .utf8) else { return }
        do { try server.startOrReloadService(config, options: LibboxOverrideOptions()) } catch { fail(error) }
    }

    func shutdown() {
        try? commandServer?.closeService()
        commandServer?.close()
        commandServer = nil
        platform.reset()
    }

    private func fail(_ error: Error) {
        try? error.localizedDescription.write(to: AppGroup.errorURL, atomically: true, encoding: .utf8)
    }
}

struct TunnelError: LocalizedError {
    let msg: String
    init(_ msg: String) { self.msg = msg }
    var errorDescription: String? { msg }
}

/// sing-box's view of the device: opens the tunnel and reports network changes.
final class TunnelPlatform: NSObject, LibboxPlatformInterfaceProtocol, LibboxCommandServerHandlerProtocol {
    private weak var provider: PacketTunnelProvider?
    private var monitor: NWPathMonitor?
    private var currentPath: Network.NWPath?
    private let queue = DispatchQueue(label: "skylark.path")

    init(provider: PacketTunnelProvider) { self.provider = provider }

    func reset() {
        monitor?.cancel()
        monitor = nil
        currentPath = nil
    }

    // MARK: tunnel

    func openTun(_ options: (any LibboxTunOptionsProtocol)?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        guard let options, let ret0_, let provider else { throw TunnelError("missing tunnel options") }
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        if options.getAutoRoute() {
            // macOS tunnels reject the 9000-byte MTU that iPhone and iPad accept.
            let mtu = ProcessInfo.processInfo.isiOSAppOnMac ? min(options.getMTU(), 1500) : options.getMTU()
            settings.mtu = NSNumber(value: mtu)

            var dns: [String] = []
            if let it = try? options.getDNSServerAddress() { while it.hasNext() { dns.append(it.next()) } }
            if !dns.isEmpty {
                let d = NEDNSSettings(servers: dns)
                d.matchDomains = [""]   // every lookup goes through the tunnel's DNS
                settings.dnsSettings = d
            }

            var v4Addr: [String] = [], v4Mask: [String] = []
            if let it = options.getInet4Address() { while it.hasNext() { if let p = it.next() { v4Addr.append(p.address()); v4Mask.append(p.mask()) } } }
            if !v4Addr.isEmpty {
                let v4 = NEIPv4Settings(addresses: v4Addr, subnetMasks: v4Mask)
                var routes: [NEIPv4Route] = []
                if let it = options.getInet4RouteAddress() { while it.hasNext() { if let p = it.next() { routes.append(NEIPv4Route(destinationAddress: p.address(), subnetMask: p.mask())) } } }
                v4.includedRoutes = routes.isEmpty ? [NEIPv4Route.default()] : routes
                var excluded: [NEIPv4Route] = []
                if let it = options.getInet4RouteExcludeAddress() { while it.hasNext() { if let p = it.next() { excluded.append(NEIPv4Route(destinationAddress: p.address(), subnetMask: p.mask())) } } }
                v4.excludedRoutes = excluded
                settings.ipv4Settings = v4
            }

            var v6Addr: [String] = [], v6Prefix: [NSNumber] = []
            if let it = options.getInet6Address() { while it.hasNext() { if let p = it.next() { v6Addr.append(p.address()); v6Prefix.append(NSNumber(value: p.prefix())) } } }
            if !v6Addr.isEmpty {
                let v6 = NEIPv6Settings(addresses: v6Addr, networkPrefixLengths: v6Prefix)
                var routes: [NEIPv6Route] = []
                if let it = options.getInet6RouteAddress() { while it.hasNext() { if let p = it.next() { routes.append(NEIPv6Route(destinationAddress: p.address(), networkPrefixLength: NSNumber(value: p.prefix()))) } } }
                v6.includedRoutes = routes.isEmpty ? [NEIPv6Route.default()] : routes
                settings.ipv6Settings = v6
            }
        }

        let sem = DispatchSemaphore(value: 0)
        var applyError: Error?
        provider.setTunnelNetworkSettings(settings) { err in applyError = err; sem.signal() }
        sem.wait()
        if let applyError { throw applyError }

        // The utun file descriptor the system created for this tunnel.
        if let fd = provider.packetFlow.value(forKeyPath: "socket.fileDescriptor") as? Int32 {
            ret0_.pointee = fd
            return
        }
        let fd = LibboxGetTunnelFileDescriptor()
        guard fd != -1 else { throw TunnelError("could not find the tunnel file descriptor") }
        ret0_.pointee = fd
    }

    // Sockets opened inside a network extension already bypass its own tunnel.
    func usePlatformAutoDetectControl() -> Bool { false }
    func autoDetectControl(_ fd: Int32) throws {}
    func underNetworkExtension() -> Bool { true }
    func includeAllNetworks() -> Bool { false }

    // MARK: network monitoring

    func startDefaultInterfaceMonitor(_ listener: (any LibboxInterfaceUpdateListenerProtocol)?) throws {
        guard let listener else { return }
        let m = NWPathMonitor()
        monitor = m
        let first = DispatchSemaphore(value: 0)
        var signalled = false
        m.pathUpdateHandler = { [weak self] path in
            self?.currentPath = path
            if path.status == .unsatisfied {
                listener.updateDefaultInterface("", interfaceIndex: -1, isExpensive: false, isConstrained: false)
            } else if let iface = path.availableInterfaces.first {
                listener.updateDefaultInterface(iface.name, interfaceIndex: Int32(iface.index),
                                                isExpensive: path.isExpensive, isConstrained: path.isConstrained)
            }
            if !signalled { signalled = true; first.signal() }
        }
        m.start(queue: queue)
        _ = first.wait(timeout: .now() + 2)
    }

    func closeDefaultInterfaceMonitor(_ listener: (any LibboxInterfaceUpdateListenerProtocol)?) throws {
        monitor?.cancel()
        monitor = nil
    }

    func getInterfaces() throws -> any LibboxNetworkInterfaceIteratorProtocol {
        let ifaces = (currentPath?.availableInterfaces ?? []).map { i -> LibboxNetworkInterface in
            let n = LibboxNetworkInterface()
            n.name = i.name
            n.index = Int32(i.index)
            switch i.type {
            case .wifi: n.type = LibboxInterfaceTypeWIFI
            case .cellular: n.type = LibboxInterfaceTypeCellular
            case .wiredEthernet: n.type = LibboxInterfaceTypeEthernet
            default: n.type = LibboxInterfaceTypeOther
            }
            return n
        }
        return InterfaceIterator(ifaces)
    }

    // MARK: unused platform features

    func localDNSTransport() -> (any LibboxLocalDNSTransportProtocol)? { nil }
    func findConnectionOwner(_ ipProtocol: Int32, sourceAddress: String?, sourcePort: Int32, destinationAddress: String?, destinationPort: Int32) throws -> LibboxConnectionOwner {
        throw TunnelError("not supported")
    }
    func readWIFIState() -> LibboxWIFIState? { nil }
    func clearDNSCache() {}
    func send(_ notification: LibboxNotification?) throws {}
    func cancelNotification(_ identifier: String?, typeID: Int32) throws {}
    func startNeighborMonitor(_ listener: (any LibboxNeighborUpdateListenerProtocol)?) throws {}
    func closeNeighborMonitor(_ listener: (any LibboxNeighborUpdateListenerProtocol)?) throws {}
    func registerMyInterface(_ name: String?) {}
    func usePlatformShell() -> Bool { false }
    func checkPlatformShell() throws { throw TunnelError("not supported") }
    func openShellSession(_ user: LibboxPlatformUser?, command: String?, environ: (any LibboxStringIteratorProtocol)?, term: String?, rows: Int32, cols: Int32) throws -> any LibboxShellSessionProtocol {
        throw TunnelError("not supported")
    }
    func lookupUser(_ username: String?) throws -> LibboxPlatformUser { throw TunnelError("not supported") }
    func lookupSFTPServer(_ error: NSErrorPointer) -> String { "" }
    func readSystemSSHHostKey(_ error: NSErrorPointer) -> String { "" }
    func tailscaleHostname() -> String { "" }
    func usePlatformBridge() -> Bool { false }
    func createBridge(_ options: LibboxBridgeOptions?) throws -> any LibboxBridgeSessionProtocol { throw TunnelError("not supported") }

    // MARK: command server handler

    func serviceStop() throws {
        provider?.shutdown()
        provider?.cancelTunnelWithError(nil)
    }
    func serviceReload() throws { provider?.reload() }
    func getSystemProxyStatus() throws -> LibboxSystemProxyStatus { LibboxSystemProxyStatus() }
    func setSystemProxyEnabled(_ enabled: Bool) throws {}
    func triggerNativeCrash() throws {}
    func writeDebugMessage(_ message: String?) {}
    func connectSSHAgent(_ ret0_: UnsafeMutablePointer<Int32>?) throws { throw TunnelError("not supported") }
}

private final class InterfaceIterator: NSObject, LibboxNetworkInterfaceIteratorProtocol {
    private var items: [LibboxNetworkInterface]
    init(_ items: [LibboxNetworkInterface]) { self.items = items }
    func hasNext() -> Bool { !items.isEmpty }
    func next() -> LibboxNetworkInterface? { items.isEmpty ? nil : items.removeFirst() }
}
