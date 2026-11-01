import Foundation

/// The user's home country/region, used so Smart mode sends local sites direct.
enum Country {
    /// iOS no longer exposes the SIM country to apps, so this is the device's region setting.
    static func detect() -> String {
        let code = Locale.current.region?.identifier ?? ""
        return code.count == 2 ? code.uppercased() : ""
    }

    static func effective(_ s: Settings) -> String { s.homeCountry.isEmpty ? detect() : s.homeCountry }

    static func displayName(_ code: String) -> String {
        code.isEmpty ? "?" : (LanguageSettings.resolvedLocale.localizedString(forRegionCode: code) ?? code)
    }

    /// Country-code top-level domain: almost always the ISO code.
    static func ccTld(_ code: String) -> String { code.uppercased() == "GB" ? "uk" : code.lowercased() }

    static func geoipFile(_ code: String) -> URL { AppGroup.rulesDir.appendingPathComponent("geoip-\(code.lowercased()).srs") }

    /// Downloads the country's IP list once. GitHub first, then jsDelivr mirrors for networks that block GitHub.
    @discardableResult
    static func ensureGeoip(_ code: String) async -> Bool {
        guard !code.isEmpty else { return false }
        let file = geoipFile(code)
        if FileManager.default.fileExists(atPath: file.path) { return true }
        try? FileManager.default.createDirectory(at: AppGroup.rulesDir, withIntermediateDirectories: true)
        let name = "geoip-\(code.lowercased()).srs"
        let urls = ["https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/\(name)",
                    "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/\(name)",
                    "https://fastly.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/\(name)",
                    "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/\(name)"]
        for u in urls {
            var req = URLRequest(url: URL(string: u)!)
            req.timeoutInterval = 10
            guard let (data, resp) = try? await URLSession.shared.data(for: req),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  data.starts(with: Array("SRS".utf8)) else { continue }  // every binary rule set starts with "SRS"
            if (try? data.write(to: file, options: .atomic)) != nil { return true }
        }
        return false
    }
}
