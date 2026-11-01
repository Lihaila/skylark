import Foundation

/// Files shared between the app and the packet tunnel extension.
enum AppGroup {
    static let id = "group.com.lihaila.skylark"
    static let tunnelBundleID = "com.lihaila.skylark.tunnel"

    static var container: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) { return url }
        // Unsigned simulator builds have no App Group; fall back to (and create) Application Support.
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    /// The sing-box config the extension runs. Written by the app before start and on every change.
    static var configURL: URL { container.appendingPathComponent("config.json") }
    static var rulesDir: URL { container.appendingPathComponent("rules", isDirectory: true) }
    static var workDir: URL { container.appendingPathComponent("work", isDirectory: true) }
    static var tempDir: URL { container.appendingPathComponent("tmp", isDirectory: true) }
    /// Last start error from the extension, shown by the app.
    static var errorURL: URL { container.appendingPathComponent("last-error.txt") }
}
