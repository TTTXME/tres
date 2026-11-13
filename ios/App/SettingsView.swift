import SwiftUI

/// The Android settings screen (res/xml/preferences.xml), generated into Resources/android_settings.json by
/// tools/gen_settings_ui.py: the same screens, groups, rows, texts (en / ru), icons and defaults. Values are stored under the
/// Android keys in the Android forms (PostSettings: switches Bool, lists and sliders String as UniversalSeekBarPreference
/// persists them), so the ported pipeline reads them exactly as the Android app reads its SharedPreferences.
struct SettingsNode: Decodable, Identifiable {
    struct Text: Decodable { let s: String?; let t: String? }
    let type: String
    let key: String?
    let title: Text?
    let summary: Text?
    let defaultValue: DefaultValue?
    let dependency: String?
    let icon: String?
    let entries: String?
    let values: String?
    let segmented: Bool?
    let min: Double?
    let max: Double?
    let step: Double?
    let float: Bool?
    let children: [SettingsNode]?
    /// The page a row opens (an <intent android:data> link)
    let url: String?
    let id = UUID()

    enum CodingKeys: String, CodingKey {
        case type, key, title, summary, dependency, icon, entries, values, segmented, min, max, step, float, children, url
        case defaultValue = "default"
    }

    enum DefaultValue: Decodable {
        case bool(Bool), text(String)
        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if let b = try? c.decode(Bool.self) { self = .bool(b) } else { self = .text(try c.decode(String.self)) }
        }
        var text: String { switch self { case .bool(let b): return b ? "true" : "false"; case .text(let s): return s } }
        var bool: Bool { switch self { case .bool(let b): return b; case .text(let s): return s == "true" } }
    }
}

final class SettingsCatalog {
    struct Table: Decodable { let strings: [String: String]; let arrays: [String: [String]] }
    struct Document: Decodable { let tree: SettingsNode; let en: Table; let ru: Table }

    static let shared = SettingsCatalog()
    let tree: SettingsNode?
    private let table: Table?

    private init() {
        guard let url = Bundle.main.url(forResource: "android_settings", withExtension: "json"),
              let data = try? Data(contentsOf: url), let doc = try? JSONDecoder().decode(Document.self, from: data) else {
            tree = nil
            table = nil
            return
        }
        tree = doc.tree
        let russian = (Locale.preferredLanguages.first ?? "en").hasPrefix("ru")
        table = russian ? doc.ru : doc.en
    }

    func text(_ t: SettingsNode.Text?) -> String? {
        guard let t else { return nil }
        if let s = t.s { return table?.strings[s] }
        return t.t
    }

    func array(_ name: String?) -> [String] { name.flatMap { table?.arrays[$0] } ?? [] }
}

/// The stored value of a key, Android's SharedPreferences under PostSettings.
@Observable
final class SettingsStore {
    static let shared = SettingsStore()
    private(set) var values: [String: Any] = UserDefaults.standard.dictionary(forKey: PostSettings.prefsKey) ?? [:]

    func bool(_ key: String, _ fallback: Bool) -> Bool { (values[key] as? NSNumber)?.boolValue ?? fallback }
    func string(_ key: String, _ fallback: String) -> String {
        if let s = values[key] as? String { return s }
        if let n = values[key] as? NSNumber { return n.stringValue }
        return fallback
    }
    func set(_ key: String, _ value: Any?) {
        values[key] = value
        PostSettings.set(key, value)
    }
    func reset() {
        values = [:]
        UserDefaults.standard.removeObject(forKey: PostSettings.prefsKey)
    }
    /// Every value replaced at once (a config loaded: BackupRestoreUtil.replace)
    func replaceAll(_ new: [String: Any]) {
        values = new
        UserDefaults.standard.set(new, forKey: PostSettings.prefsKey)
    }
}

/// UniversalSeekBarPreference's grid and text (PreferenceNumber.gridDecimals / gridText / format).
enum SliderGrid {
    static func decimals(step: Double, min: Double) -> Int {
        let s = step > 1 ? Int((log10(step) - 1e-9).rounded(.up)) : 0
        return Swift.max(2, Swift.max(s, decimalsOf(javaFloat(Float(min)))))
    }

    static func decimalsOf(_ text: String) -> Int {
        guard let d = Decimal(string: text) else { return 0 }
        var v = d
        var scale = 0
        while scale < 12 {
            var r = Decimal()
            var x = v
            NSDecimalRound(&r, &x, 0, .plain)
            if r == v { return scale }
            v *= 10
            scale += 1
        }
        return scale
    }

    /// Java's Float.toString for the values a slider's attributes hold
    static func javaFloat(_ f: Float) -> String {
        if f == f.rounded() && abs(f) < 1e7 { return String(format: "%.1f", f) }
        return "\(f)"
    }

    static func gridText(_ value: Double, decimals: Int) -> String {
        let scale = Swift.max(2, decimals)
        var x = Decimal(value.isFinite ? value : 0)
        var r = Decimal()
        NSDecimalRound(&r, &x, scale, .plain)
        var text = NSDecimalNumber(decimal: r).stringValue
        // trailing zeros trimmed down to two decimals
        if !text.contains(".") { text += "." }
        var parts = text.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        while parts[1].count < 2 { parts[1] += "0" }
        while parts[1].count > 2 && parts[1].hasSuffix("0") { parts[1].removeLast() }
        return parts[0] + "." + parts[1]
    }

    static func text(progress: Int, node: SettingsNode) -> String {
        let step = node.float == true ? (node.step ?? 1) : 1
        let value = Double(progress) / step + (node.min ?? 0)
        if node.float == true { return gridText(value, decimals: decimals(step: step, min: node.min ?? 0)) }
        return String(Int((value).rounded(.toNearestOrAwayFromZero)))
    }

    static func progress(of value: Double, node: SettingsNode) -> Int {
        let step = node.float == true ? (node.step ?? 1) : 1
        let p = Int(((value - (node.min ?? 0)) * step).rounded(.toNearestOrAwayFromZero))
        return Swift.min(Swift.max(0, p), steps(node))
    }

    static func steps(_ node: SettingsNode) -> Int {
        let step = node.float == true ? (node.step ?? 1) : 1
        return Swift.max(1, Int((((node.max ?? 100) - (node.min ?? 0)) * step).rounded()))
    }
}


struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    /// The camera the settings were opened from: the format and size cards switch it (nil: they show what is stored)
    var camera: CameraModel? = nil

    var body: some View {
        NavigationStack {
            if let tree = SettingsCatalog.shared.tree {
                SettingsScreen(node: tree, root: true)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("settings.done")) { dismiss() } } }
            } else {
                Text(L("settings.missing")).padding()
            }
        }
        .environment(\.settingsCamera, camera)
        .preferredColorScheme(.dark)
    }
}

struct SettingsScreen: View {
    let node: SettingsNode
    var root = false
    private var store: SettingsStore { SettingsStore.shared }
    private var catalog: SettingsCatalog { SettingsCatalog.shared }

    var body: some View {
        let availability = SettingsAvailability(store.values)
        let shown = { (nodes: [SettingsNode]?) -> [SettingsNode] in (nodes ?? []).filter { !($0.key.map(availability.hidden) ?? false) } }
        let children = shown(node.children)
        List {
            if let note = SettingsExtras.screenNote(node.key) {
                Section { Text(note).font(.footnote).foregroundStyle(.secondary) }
            }
            // rows before the first group stand in a group of their own
            let loose = children.filter { $0.type != "category" }
            if !loose.isEmpty {
                Section { ForEach(loose) { SettingsRow(node: $0) } }
            }
            ForEach(children.filter { $0.type == "category" }) { category in
                // SettingsAvailability.groupReason: a group whose rows do not act now is greyed with its reason
                let reason = category.key.flatMap { availability.groupReason($0) }
                Section {
                    ForEach(shown(category.children)) { SettingsRow(node: $0) }
                    SettingsExtras.categoryContent(category.key)
                } header: {
                    Text(catalog.text(category.title) ?? "")
                } footer: {
                    if let reason { Text(reason) }
                }
                .disabled(reason != nil)
            }
        }
        .navigationTitle(catalog.text(node.title) ?? "SCAMERA")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if !root { PageResetButton(node: node) }
            }
        }
    }
}

struct SettingsRow: View {
    let node: SettingsNode
    private var store: SettingsStore { SettingsStore.shared }
    private var catalog: SettingsCatalog { SettingsCatalog.shared }

    private var availability: SettingsAvailability { SettingsAvailability(store.values) }

    /// SettingsAvailability: why the row (a group's: groupReason) does not act now
    private var reason: String? {
        guard let key = node.key else { return nil }
        return node.type == "screen" || node.type == "category" ? availability.groupReason(key) : availability.reason(key)
    }

    private var dependencyOn: Bool {
        guard let dep = node.dependency else { return true }
        return store.bool(dep, true)
    }

    /// A page stays open to explain its inactive rows unless the iPhone cannot serve it at all
    private var enabled: Bool {
        guard dependencyOn else { return false }
        if node.type == "screen" { return node.key.flatMap { availability.iPhoneReason($0) } == nil }
        return reason == nil
    }

    var body: some View {
        Group {
            switch node.type {
            case "screen":
                NavigationLink { SettingsScreen(node: node) } label: { label(detail: nil) }
            case "category":
                // a group inside a group: its rows in place, its title as a caption
                VStack(alignment: .leading) {
                    Text(catalog.text(node.title) ?? "").font(.caption).foregroundStyle(.secondary)
                    ForEach(node.children ?? []) { SettingsRow(node: $0) }
                }
            case "switch":
                if let key = node.key {
                    Toggle(isOn: Binding(get: { store.bool(key, node.defaultValue?.bool ?? false) }, set: { store.set(key, $0) })) {
                        label(detail: nil)
                    }
                }
            case "list":
                listRow
            case "slider":
                sliderRow
            case "text":
                if let key = node.key {
                    VStack(alignment: .leading, spacing: 4) {
                        label(detail: nil)
                        TextField("", text: Binding(get: { store.string(key, node.defaultValue?.text ?? "") }, set: { store.set(key, $0) }))
                            .textFieldStyle(.roundedBorder).font(.footnote.monospaced())
                    }
                }
            case "row":
                plainRow
            case "custom:FilmSimPreference":
                NavigationLink { FilmSimPage() } label: {
                    label(detail: FilmCatalog.stock(store.string("pref_film_sim_stock", "")).map { $0.brand + " " + $0.name } ?? L("film.off"))
                }
            case "custom:FilmLutPreference":
                NavigationLink { FilmLutPage() } label: { label(detail: FilmCatalog.lutName(store.string("pref_film_lut", ""))) }
            case "custom:LensFilterPreference":
                NavigationLink { LensFilterPage() } label: {
                    let id = store.string("pref_lens_filter", "")
                    label(detail: FilmCatalog.lensName(SCAMFilm.lensFilters().contains(id) ? id : ""))
                }
            case "custom:WatermarkPreviewPreference":
                WatermarkPreview()
            case "custom:ToneCurvePreference":
                if let key = node.key {
                    VStack(alignment: .leading, spacing: 6) {
                        label(detail: nil)
                        ToneCurveEditor(key: key, defaultPoints: node.defaultValue?.text, enabled: enabled)
                    }
                }
            case "custom:PhotoStylePreference":
                PhotoStylePicker()
            case "custom:LutChangesPreference":
                LutChangesView()
            case "custom:RawCropPreference":
                VStack(alignment: .leading, spacing: 6) {
                    label(detail: nil)
                    RawCropCard(enabled: enabled)
                }
            case "custom:FormatPickerPreference":
                VStack(alignment: .leading, spacing: 6) {
                    label(detail: nil)
                    FormatPickerCard()
                }
            case "custom:SizePickerPreference":
                SizePickerCard()
            case "custom:ShadeTransparencyPreference":
                ShadeTransparencyCard()
            case "custom:ProfilesPreference":
                ProfilesCard()
            case "custom:BackupPreferences":
                BackupRow(label: label(detail: nil))
            case "custom:RestorePreference":
                RestoreRow(label: label(detail: nil))
            case "custom:ResetPreferences":
                ResetRow(label: label(detail: nil))
            default:
                label(detail: L("settings.android_only")).opacity(0.5)
            }
        }
        .disabled(!enabled)
    }

    /// A plain row: the rows Android opens a page or runs an action from, the about lines and the notes
    @ViewBuilder private var plainRow: some View {
        switch node.key ?? "" {
        case "pref_oldstyle_tone_reset":
            OldstyleResetRow(label: label(detail: nil))
        case "shdr_capture_report":
            NavigationLink { ShdrReportPage() } label: { label(detail: nil) }
        case "scamera_log_clear":
            LogClearRow(label: { label(detail: $0) })
        case "scamera_log_share":
            LogShareRow(label: label(detail: nil))
        case "pref_app_key":
            label(detail: "iOS")
        case "pref_version_key":
            label(detail: SettingsExtras.versionText)
        default:
            if let url = node.url.flatMap({ URL(string: $0) }) {
                Link(destination: url) { label(detail: nil) }
            } else {
                label(detail: nil)
            }
        }
    }

    @ViewBuilder private func label(detail: String?) -> some View {
        HStack(spacing: 12) {
            if let icon = node.icon { Image(icon).resizable().frame(width: 22, height: 22) }
            VStack(alignment: .leading, spacing: 2) {
                // a note row has no title: its text alone
                if let title = catalog.text(node.title), !title.isEmpty { Text(title) }
                if let summary = catalog.text(node.summary), !summary.isEmpty {
                    Text(summary).font(.caption).foregroundStyle(.secondary).lineLimit(node.title == nil ? nil : 4)
                }
                if let detail { Text(detail).font(.caption2).foregroundStyle(.tertiary) }
                if let reason { Text(reason).font(.caption2).foregroundStyle(.orange) }
                if let key = node.key, let note = availability.note(key) { Text(note).font(.caption2).foregroundStyle(.orange) }
            }
        }
    }

    @ViewBuilder private var listRow: some View {
        if let key = node.key {
            let entries = catalog.array(node.entries), values = catalog.array(node.values)
            let current = store.string(key, node.defaultValue?.text ?? values.first ?? "")
            if node.segmented == true {
                VStack(alignment: .leading, spacing: 6) {
                    label(detail: nil)
                    Picker("", selection: Binding(get: { current }, set: { store.set(key, $0) })) {
                        ForEach(Array(zip(entries, values)), id: \.1) { Text($0.0).tag($0.1) }
                    }
                    .pickerStyle(.segmented)
                }
            } else {
                Picker(selection: Binding(get: { current }, set: { store.set(key, $0) })) {
                    ForEach(Array(zip(entries, values)), id: \.1) { Text($0.0).tag($0.1) }
                } label: { label(detail: nil) }
                .pickerStyle(.navigationLink)
            }
        }
    }

    @ViewBuilder private var sliderRow: some View {
        if let key = node.key {
            let stored = store.string(key, node.defaultValue?.text ?? "0")
            let value = Double(stored.replacingOccurrences(of: ",", with: ".")) ?? Double(node.defaultValue?.text ?? "0") ?? 0
            VStack(alignment: .leading, spacing: 4) {
                HStack { label(detail: nil); Spacer(); Text(stored).font(.footnote.monospacedDigit()).foregroundStyle(.secondary) }
                Slider(value: Binding(get: { Double(SliderGrid.progress(of: value, node: node)) },
                                      set: { store.set(key, SliderGrid.text(progress: Int($0.rounded()), node: node)) }),
                       in: 0...Double(SliderGrid.steps(node)), step: 1)
            }
        }
    }
}
