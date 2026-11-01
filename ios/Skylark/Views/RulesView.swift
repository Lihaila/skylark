import SwiftUI

struct RulesView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    @State private var adding = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    let home = Country.effective(store.state.settings)
                    Toggle(isOn: Binding(get: { store.state.settings.bypassHome }, set: { v in
                        store.update { $0.settings.bypassHome = v }
                        Task { if v { await Country.ensureGeoip(home) }; vpn.reload() }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Direct for \(Country.displayName(home))")
                            Text("Sites in your country or region skip the proxy.").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    NavigationLink {
                        CountryPicker()
                    } label: {
                        HStack {
                            Text("Country or region")
                            Spacer()
                            Text(store.state.settings.homeCountry.isEmpty
                                 ? String(localized: "Automatic (\(Country.displayName(Country.detect())))", bundle: .app)
                                 : Country.displayName(store.state.settings.homeCountry))
                                .foregroundStyle(.secondary)
                        }
                    }
                    Toggle(isOn: Binding(get: { store.state.settings.bypassChina }, set: { v in
                        store.update { $0.settings.bypassChina = v }; vpn.reload()
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Bypass mainland China")
                            Text("Chinese sites and apps connect directly. Handy for WeChat, Bilibili and banking apps from abroad.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    if store.state.rules.isEmpty {
                        Text("No custom rules").foregroundStyle(.secondary)
                    }
                    ForEach(store.state.rules) { r in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.value)
                                Text(r.type.label).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            actionBadge(r.action)
                        }
                    }
                    .onDelete { idx in
                        for i in idx { store.deleteRule(store.state.rules[i].id) }
                        vpn.reload()
                    }
                } header: {
                    Text("Custom rules")
                } footer: {
                    Text("Rules apply only in Smart mode, from top to bottom.")
                }
            }
            .navigationTitle("Rules")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }.accessibilityLabel("Add rule")
                }
            }
            .sheet(isPresented: $adding) { AddRuleSheet() }
        }
    }

    @ViewBuilder private func actionBadge(_ a: RuleAction) -> some View {
        switch a {
        case .proxy: Text("Proxy").font(.subheadline.weight(.semibold)).foregroundStyle(Color.brand)
        case .direct: Text("Direct").font(.subheadline.weight(.semibold)).foregroundStyle(.green)
        case .reject: Text("Block").font(.subheadline.weight(.semibold)).foregroundStyle(.red)
        }
    }
}

struct CountryPicker: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    @Environment(\.dismiss) var dismiss
    @State private var query = ""

    private var all: [(String, String)] {
        Locale.Region.isoRegions.map(\.identifier).filter { $0.count == 2 && $0.allSatisfy(\.isLetter) }
            .map { ($0, Country.displayName($0)) }.sorted { $0.1.localizedCompare($1.1) == .orderedAscending }
    }

    var body: some View {
        List {
            row("", String(localized: "Automatic (\(Country.displayName(Country.detect())))", bundle: .app))
            ForEach(all.filter { query.isEmpty || $0.1.localizedCaseInsensitiveContains(query) || $0.0.caseInsensitiveCompare(query) == .orderedSame }, id: \.0) {
                row($0.0, $0.1)
            }
        }
        .searchable(text: $query)
        .navigationTitle("Country or region")
    }

    private func row(_ code: String, _ label: String) -> some View {
        Button {
            store.update { $0.settings.homeCountry = code; $0.settings.bypassHome = true }
            let home = Country.effective(store.state.settings)
            Task { await Country.ensureGeoip(home); vpn.reload() }
            dismiss()
        } label: {
            HStack {
                Text(label).foregroundStyle(.primary)
                Spacer()
                if store.state.settings.homeCountry == code { Image(systemName: "checkmark").foregroundStyle(Color.brand) }
            }
        }
    }
}

struct AddRuleSheet: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    @State private var type: RuleType = .domainSuffix
    @State private var value = ""
    @State private var action: RuleAction = .proxy

    var body: some View {
        NavigationStack {
            Form {
                Picker("Type", selection: $type) { ForEach(RuleType.allCases) { Text($0.label).tag($0) } }
                TextField(placeholder, text: $value).textInputAutocapitalization(.never).autocorrectionDisabled()
                Picker("Action", selection: $action) {
                    Text("Proxy").tag(RuleAction.proxy); Text("Direct").tag(RuleAction.direct); Text("Block").tag(RuleAction.reject)
                }.pickerStyle(.segmented)
            }
            .navigationTitle("Add rule")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let v = value.trimmingCharacters(in: .whitespaces)
                        store.addRule(Rule(type: type, value: type == .geoip ? v.uppercased() : v.lowercased(), action: action))
                        vpn.reload()
                        dismiss()
                    }.disabled(value.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private var placeholder: String {
        switch type {
        case .ipCidr: String(localized: "e.g. 8.8.8.0/24", bundle: .app)
        case .geoip: String(localized: "Country code, e.g. US, JP, or LAN", bundle: .app)
        default: String(localized: "e.g. google.com", bundle: .app)
        }
    }
}
