import SwiftUI

@main
struct SkylarkApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var vpn = VPNController.shared
    @StateObject private var toast = Toast.shared
    @StateObject private var language = LanguageSettings.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(vpn)
                .environmentObject(toast)
                .environmentObject(language)
                // In-app language: SwiftUI text follows this locale; rebuilding refreshes everything at once.
                .environment(\.locale, language.locale)
                .id(language.current)
                // Share links (ss://, vless://…) tapped in a browser or chat open here.
                .onOpenURL { url in Task { toast.show(await Importer.importText(url.absoluteString).message) } }
        }
    }
}

enum Tab: String, Hashable { case home, servers, rules, settings }

struct RootView: View {
    @EnvironmentObject var toast: Toast
    // SceneStorage survives the rebuild that a language change triggers, so the user stays on Settings.
    @SceneStorage("tab") private var tab: Tab = .home

    var body: some View {
        TabView(selection: $tab) {
            HomeView(openServers: { tab = .servers })
                .tabItem { Label("Home", systemImage: "house.fill") }.tag(Tab.home)
            ServersView()
                .tabItem { Label("Servers", systemImage: "server.rack") }.tag(Tab.servers)
            RulesView()
                .tabItem { Label("Rules", systemImage: "list.bullet.rectangle") }.tag(Tab.rules)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag(Tab.settings)
        }
        .tint(.brand)
        .overlay(alignment: .bottom) {
            if let msg = toast.message {
                Text(msg)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 24).padding(.bottom, 64)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: toast.message)
    }
}

/// Short bottom message, like an Android snackbar.
@MainActor
final class Toast: ObservableObject {
    static let shared = Toast()
    @Published var message: String?
    private var task: Task<Void, Never>?

    func show(_ text: String) {
        message = text
        task?.cancel()
        task = Task { try? await Task.sleep(nanoseconds: 3_000_000_000); if !Task.isCancelled { message = nil } }
    }
}

extension Color {
    static let brand = Color(red: 0x3B / 255, green: 0x5B / 255, blue: 0xFD / 255)
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
}

func typeLabel(_ type: String) -> String {
    switch type {
    case "shadowsocks": "Shadowsocks"
    case "vmess": "VMess"
    case "vless": "VLESS"
    case "trojan": "Trojan"
    case "hysteria2": "Hysteria2"
    case "tuic": "TUIC"
    case "anytls": "AnyTLS"
    case "socks": "SOCKS5"
    case "http": "HTTP"
    default: type
    }
}

func formatBytes(_ b: Int64) -> String {
    switch b {
    case ..<1024: "\(b) B"
    case ..<(1024 * 1024): String(format: "%.1f KB", Double(b) / 1024)
    case ..<(1024 * 1024 * 1024): String(format: "%.1f MB", Double(b) / 1_048_576)
    default: String(format: "%.2f GB", Double(b) / 1_073_741_824)
    }
}

/// Paste and scan, used on Home (empty state) and on Servers.
@MainActor
enum ImportActions {
    static func paste() {
        let text = UIPasteboard.general.string
        Task { Toast.shared.show(await Importer.importText(text).message) }
    }
}
