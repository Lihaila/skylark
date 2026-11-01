import Libbox
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    @EnvironmentObject var language: LanguageSettings
    @State private var remoteDns = ""
    @State private var directDns = ""
    @Environment(\.openURL) var openURL

    private let sourceURL = URL(string: "https://github.com/Lihaila/skylark")!

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Proxy DNS (DoH server)") {
                        TextField("1.1.1.1", text: $remoteDns).multilineTextAlignment(.trailing)
                            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .onSubmit { saveDns() }
                    }
                    LabeledContent("Direct DNS") {
                        TextField("1.1.1.1", text: $directDns).multilineTextAlignment(.trailing)
                            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .onSubmit { saveDns() }
                    }
                    Toggle(isOn: Binding(get: { store.state.settings.ipv6 }, set: { v in store.update { $0.settings.ipv6 = v }; vpn.reload() })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("IPv6")
                            Text("Turn on only if your network and server support IPv6").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } header: { Text("DNS") } footer: { Text("Changes apply immediately while connected.") }

                Section("Language") {
                    Picker("Language", selection: $language.current) {
                        ForEach(AppLanguage.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section("About") {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    LabeledContent("Core", value: "sing-box \(LibboxVersion())")
                    Link(destination: sourceURL) {
                        LabeledContent("Source code", value: "github.com/Lihaila/skylark")
                    }
                    Text("Free software under the GNU GPLv3. Includes sing-box.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .onAppear {
                remoteDns = store.state.settings.remoteDns
                directDns = store.state.settings.directDns
            }
            .onDisappear { saveDns() }
        }
    }

    private func saveDns() {
        let r = remoteDns.trimmingCharacters(in: .whitespaces), d = directDns.trimmingCharacters(in: .whitespaces)
        guard !r.isEmpty, !d.isEmpty, r != store.state.settings.remoteDns || d != store.state.settings.directDns else { return }
        store.update { $0.settings.remoteDns = r; $0.settings.directDns = d }
        vpn.reload()
    }
}
