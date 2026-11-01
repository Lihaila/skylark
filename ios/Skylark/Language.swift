import SwiftUI

/// In-app language choice. On a Mac, iPad apps have no per-app language page in System Settings,
/// so the app switches itself: SwiftUI text follows `locale`, code strings read from `bundle`.
enum AppLanguage: String, CaseIterable, Identifiable {
    case system = "", en = "en", zhHans = "zh-Hans", zhHant = "zh-Hant"
    var id: String { rawValue }
    /// Shown in its own language so people can always find theirs.
    var label: String {
        switch self {
        case .system: String(localized: "Follow system", bundle: .app)
        case .en: "English"
        case .zhHans: "简体中文"
        case .zhHant: "繁體中文"
        }
    }
}

@MainActor
final class LanguageSettings: ObservableObject {
    static let shared = LanguageSettings()
    private static let key = "appLanguage"

    @Published var current: AppLanguage {
        didSet {
            UserDefaults.standard.set(current.rawValue, forKey: Self.key)
            Self.resolvedBundle = Self.bundle(for: current)
            Self.resolvedLocale = locale
        }
    }

    private init() {
        current = AppLanguage(rawValue: UserDefaults.standard.string(forKey: Self.key) ?? "") ?? .system
        Self.resolvedBundle = Self.bundle(for: current)
        Self.resolvedLocale = locale
    }

    var locale: Locale {
        current == .system ? .autoupdatingCurrent : Locale(identifier: current.rawValue)
    }

    /// Bundle for String(localized:) calls outside SwiftUI views; read from any thread.
    nonisolated(unsafe) static var resolvedBundle: Bundle = .main

    private static func bundle(for lang: AppLanguage) -> Bundle {
        switch lang {
        case .system: return .main
        // English is the source language and has no lproj folder: an empty bundle returns the keys.
        case .en: return EmptyBundle.shared
        default:
            return Bundle.main.path(forResource: lang.rawValue, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
        }
    }

    /// Locale for formatting outside views (e.g. country names).
    nonisolated(unsafe) static var resolvedLocale: Locale = .autoupdatingCurrent
}

extension Bundle {
    /// The bundle matching the in-app language choice.
    static var app: Bundle { LanguageSettings.resolvedBundle }
}

/// A bundle with no Localizable.strings, so every lookup returns the English key.
private enum EmptyBundle {
    static let shared: Bundle = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("skylark-en.bundle", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return Bundle(url: dir) ?? .main
    }()
}
