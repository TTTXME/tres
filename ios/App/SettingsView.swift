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
    /// The rows of a page or group (SettingsLayout: in the user's order once arranged)
    var children: [SettingsNode]?
    /// The page a row opens (an <intent android:data> link)
    let url: String?
    let id = UUID()
    /// P665: a list's entries and values given in place (the developer's rows of the film, the LUT, the lens filter), else the
    /// arrays named by entries / values
    var inlineEntries: [String]? = nil
    var inlineValues: [String]? = nil

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
    /// P665: the gallery developer's tables (gen_settings_ui.py: DevelopCatalog.GROUPS, the rows fixed at the shot, the merge's keys)
    struct DevelopTable: Decodable { let groups: [[String]]; let atShot: [String]; let burstFrames: [String]; let mergeTuning: [String] }
    /// SettingHelp's map: the help string of a key (EXACT), of a key family (FAMILIES: a pattern and a string), the rows whose summary
    /// is their state (STATE_KEYS)
    struct HelpTable: Decodable { let exact: [String: String]; let families: [[String]]; let stateKeys: [String] }
    /// DevKeys.Key: a scam_dev.txt line of the «Для разработчика» page (n: a number, else a switch; d: the code's default, nil when
    /// the app decides)
    struct DevKey: Decodable {
        struct Words: Decodable { let ru: String; let en: String }
        let g: Words
        let t: Words
        let k: String
        let n: Bool
        let d: String?
        var group: String { Lang.t(g.ru, g.en) }
        var text: String { Lang.t(t.ru, t.en) }
        var otherText: String { Lang.russian ? t.en : t.ru }
    }
    struct Document: Decodable {
        let tree: SettingsNode
        let en: Table
        let ru: Table
        let develop: DevelopTable?
        let help: HelpTable?
        let devKeys: [DevKey]?
    }

    static let shared = SettingsCatalog()
    let tree: SettingsNode?
    let develop: DevelopTable?
    let help: HelpTable?
    let devKeys: [DevKey]
    private let table: Table?
    /// The other interface language's table: the search finds a row by its Russian and its English words
    private let other: Table?
    private let families: [(NSRegularExpression, String)]

    private init() {
        guard let url = Bundle.main.url(forResource: "android_settings", withExtension: "json"),
              let data = try? Data(contentsOf: url), let doc = try? JSONDecoder().decode(Document.self, from: data) else {
            tree = nil
            table = nil
            other = nil
            develop = nil
            help = nil
            devKeys = []
            families = []
            return
        }
        tree = doc.tree
        develop = doc.develop
        help = doc.help
        devKeys = doc.devKeys ?? []
        let russian = (Locale.preferredLanguages.first ?? "en").hasPrefix("ru")
        table = russian ? doc.ru : doc.en
        other = russian ? doc.en : doc.ru
        families = (doc.help?.families ?? []).compactMap { f in
            guard f.count == 2, let re = try? NSRegularExpression(pattern: "^(?:" + f[0] + ")$") else { return nil }
            return (re, f[1])
        }
    }

    func text(_ t: SettingsNode.Text?) -> String? {
        guard let t else { return nil }
        if let s = t.s { return table?.strings[s] }
        return t.t
    }

    /// The text in the other interface language
    func otherText(_ t: SettingsNode.Text?) -> String? {
        guard let t else { return nil }
        if let s = t.s { return other?.strings[s] }
        return t.t
    }

    func array(_ name: String?) -> [String] { name.flatMap { table?.arrays[$0] } ?? [] }

    /// SettingHelp.helpRes: the string of the key's own help text (exact keys first, then the families), nil without one
    func helpName(_ key: String?) -> String? {
        guard let key, let help else { return nil }
        if let name = help.exact[key] { return name }
        let range = NSRange(key.startIndex..., in: key)
        return families.first { $0.0.firstMatch(in: key, range: range) != nil }?.1
    }

    func helpText(_ key: String?, otherLanguage: Bool = false) -> String? {
        guard let name = helpName(key) else { return nil }
        return otherLanguage ? other?.strings[name] : table?.strings[name]
    }

    /// SettingHelp.STATE_KEYS: a row whose summary is its state (the log's size, the version, the credits)
    func summaryIsState(_ key: String?) -> Bool { key.map { help?.stateKeys.contains($0) ?? false } ?? false }
}

/// Where a settings row reads and writes its values: the camera's settings (SettingsStore), or the gallery developer's (P665: the
/// shot's settings with the editor's changes over them).
protocol SettingsValues: AnyObject {
    var values: [String: Any] { get }
    func bool(_ key: String, _ fallback: Bool) -> Bool
    func string(_ key: String, _ fallback: String) -> String
    func set(_ key: String, _ value: Any?)
}

/// The stored value of a key, Android's SharedPreferences under PostSettings.
@Observable
final class SettingsStore: SettingsValues {
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

    /// PreferenceNumber.format: a decimal as Float.toString without trailing zeros ("1.5", "0.0005", "2"), a whole number rounded
    static func exact(_ value: Double, decimal: Bool) -> String {
        guard value.isFinite else { return "0" }
        if !decimal { return String(Int(value.rounded(.toNearestOrAwayFromZero))) }
        return NSDecimalNumber(string: "\(Float(value))", locale: Locale(identifier: "en_US_POSIX")).stringValue
    }

    static func text(progress: Int, node: SettingsNode) -> String {
        let step = node.float == true ? (node.step ?? 1) : 1
        let value = Double(progress) / step + (node.min ?? 0)
        if node.float == true { return gridText(value, decimals: decimals(step: step, min: node.min ?? 0)) }
        return String(Int((value).rounded(.toNearestOrAwayFromZero)))
    }

    /// A value as the slider shows it (its grid's decimals, a whole number otherwise)
    static func shown(_ value: Double, node: SettingsNode) -> String {
        if node.float == true { return gridText(value, decimals: decimals(step: node.step ?? 1, min: node.min ?? 0)) }
        return String(Int(value.rounded(.toNearestOrAwayFromZero)))
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

    /// A stored slider text as its number
    static func number(_ text: String, fallback: Double) -> Double { Double(text.replacingOccurrences(of: ",", with: ".")) ?? fallback }
}

/// The row a search hit opened (SettingsActivity's search_target): its title in the accent, the page scrolled to it
private struct SettingsHighlightKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

extension EnvironmentValues {
    var settingsHighlight: String? {
        get { self[SettingsHighlightKey.self] }
        set { self[SettingsHighlightKey.self] = newValue }
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
        .tint(AccentPalette.settingsColor)
        .preferredColorScheme(.dark)
        .onAppear { SettingsTips.tipOnce() }
        // the modules' names, ratios, buttons and copies (ModuleSettings) reach the camera when the settings close
        .onDisappear { if let camera { Task { await camera.reloadModules() } } }
        .overlay(alignment: .bottom) { SettingsTips.Toast() }
    }
}

/// The page a row opens: the developer page builds its rows from DevKeys, every other page is its part of the tree
enum SettingsPages {
    @ViewBuilder static func destination(_ node: SettingsNode, highlight: String? = nil) -> some View {
        if node.key == DeveloperPage.screen {
            DeveloperPage(node: node).environment(\.settingsHighlight, highlight)
        } else {
            SettingsScreen(node: node).environment(\.settingsHighlight, highlight)
        }
    }
}

struct SettingsScreen: View {
    let node: SettingsNode
    var root = false
    @Environment(\.settingsHighlight) private var highlight
    @State private var query = ""
    private var store: SettingsStore { SettingsStore.shared }
    private var catalog: SettingsCatalog { SettingsCatalog.shared }

    var body: some View {
        let availability = SettingsAvailability(store.values)
        let hidden = { (n: SettingsNode) -> Bool in
            guard let key = n.key else { return SettingsRow.emptyNote(n) }
            return availability.hidden(key) || SettingsExtras.codeHidden.contains(key) || SettingsRow.emptyNote(n)
        }
        let shown = { (nodes: [SettingsNode]?) -> [SettingsNode] in (nodes ?? []).filter { !hidden($0) } }
        // P385: the page in the user's order (SettingsLayout); a group left without rows by it is not shown
        let page = SettingsLayout.arranged(node, stored: store.string(SettingsLayout.key, ""))
        let children = shown(page.children)
        ScrollViewReader { proxy in
            List {
                if root && !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    SettingsSearchResults(query: query)
                } else {
                    if let note = SettingsExtras.screenNote(node.key) {
                        Section { Text(note).font(.app(13)).foregroundStyle(.secondary) }
                    }
                    // rows before the first group stand in a group of their own
                    let loose = children.filter { $0.type != "category" }
                    if !loose.isEmpty {
                        Section { ForEach(loose) { SettingsRow(node: $0).id($0.key ?? $0.id.uuidString) } }
                    }
                    ForEach(children.filter { $0.type == "category" }) { category in
                        // SettingsAvailability.groupReason: a group whose rows do not act now is greyed with its reason
                        let reason = category.key.flatMap { availability.groupReason($0) }
                        Section {
                            ForEach(shown(category.children)) { SettingsRow(node: $0).id($0.key ?? $0.id.uuidString) }
                            SettingsExtras.categoryContent(category.key)
                        } header: {
                            Text(catalog.text(category.title) ?? "")
                        } footer: {
                            if let reason { Text(reason) }
                        }
                        .disabled(reason != nil)
                    }
                }
            }
            .onAppear {
                guard let highlight else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    withAnimation { proxy.scrollTo(highlight, anchor: .center) }
                }
            }
        }
        .modifier(SettingsSearchField(enabled: root, query: $query))
        .navigationTitle(catalog.text(node.title) ?? "SCAMERA")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                // P385: the page's order (a page with something to arrange)
                if SettingsLayout.editable(node) {
                    NavigationLink { SettingsLayoutPage(page: node) } label: {
                        Image("cs_ic_arrange").renderingMode(.template).resizable().frame(width: 20, height: 20)
                    }
                    .accessibilityLabel(L("set.layout.title"))
                }
                if !root { PageResetButton(node: node) }
            }
        }
    }
}

/// The root page's search field (P584: the concept searches in place on the main page)
private struct SettingsSearchField: ViewModifier {
    let enabled: Bool
    @Binding var query: String

    func body(content: Content) -> some View {
        if enabled {
            content.searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("set.search.prompt"))
        } else {
            content
        }
    }
}

struct SettingsRow: View {
    let node: SettingsNode
    /// P665: the values the row shows and changes (nil: the camera's settings), a reason that greys it whatever the values (the
    /// developer's: fixed at the shot, the merge's), lists as menus (outside a navigation stack)
    var source: (any SettingsValues)? = nil
    var fixedReason: String? = nil
    var menuLists = false
    @Environment(\.settingsHighlight) private var highlight
    @State private var helpShown = false
    @State private var precise = false
    private var store: any SettingsValues { source ?? (SettingsStore.shared as any SettingsValues) }
    private var catalog: SettingsCatalog { SettingsCatalog.shared }

    private var availability: SettingsAvailability { SettingsAvailability(store.values) }

    /// SettingsStyle.emptyNote (P592): a note without a title is no row any more (its text is in the help of the rows it explains);
    /// one whose summary is a state stays
    static func emptyNote(_ n: SettingsNode) -> Bool {
        n.type == "row" && n.url == nil && (SettingsCatalog.shared.text(n.title) ?? "").isEmpty && !SettingsCatalog.shared.summaryIsState(n.key)
    }

    /// SettingsAvailability: why the row (a group's: groupReason) does not act now
    private var reason: String? {
        if let fixedReason { return fixedReason }
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
                NavigationLink { SettingsPages.destination(node) } label: { label(detail: SettingsExtras.pageValue(node.key)) }
            case "category":
                // a group inside a group: its rows in place, its title as a caption
                VStack(alignment: .leading) {
                    Text(catalog.text(node.title) ?? "").font(.app(12)).foregroundStyle(.secondary)
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
                FilmLutCard()
            case "custom:LensFilterPreference":
                LensFilterCard()
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
        .modifier(SettingHelpGesture(node: node, store: store, enabled: SettingHelp.helps(node) && source == nil, shown: $helpShown))
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
        case AccentPalette.key:
            NavigationLink { AccentPage() } label: { label(detail: AccentPalette.name(AccentPalette.selected(store.values))) }
        case FileNamePattern.key:
            FileNameRow(label: { label(detail: $0) })
        case SettingsLayout.editorKey:
            NavigationLink { SettingsLayoutPage(page: SettingsCatalog.shared.tree ?? node) } label: { label(detail: nil) }
        default:
            if let url = node.url.flatMap({ URL(string: $0) }) {
                Link(destination: url) { label(detail: nil) }
            } else if node.key != nil, !(catalog.text(node.title) ?? "").isEmpty, !catalog.summaryIsState(node.key) {
                // P592: a note (a row that only explains) shows its title; a tap opens its text as the long press does
                Button { helpShown = true } label: { label(detail: nil) }
            } else {
                label(detail: nil)
            }
        }
    }

    @ViewBuilder private func label(detail: String?) -> some View {
        HStack(spacing: 12) {
            if let icon = node.icon { Image(icon).resizable().frame(width: 22, height: 22) }
            VStack(alignment: .leading, spacing: 2) {
                let title = catalog.text(node.title) ?? ""
                if !title.isEmpty {
                    Text(title).foregroundStyle(node.key != nil && node.key == highlight ? AnyShapeStyle(AccentPalette.settingsColor) : AnyShapeStyle(HierarchicalShapeStyle.primary))
                }
                // P592: a row shows its title and its value; what it does is its long press. A row without a title, or one whose
                // summary is its state (the credits, the version), still shows it
                if title.isEmpty || catalog.summaryIsState(node.key), let summary = catalog.text(node.summary), !summary.isEmpty {
                    Text(summary).font(.app(12)).foregroundStyle(.secondary).lineLimit(title.isEmpty ? nil : 4)
                }
                if let detail { Text(detail).font(.app(11)).foregroundStyle(.tertiary) }
                if let reason { Text(reason).font(.app(11)).foregroundStyle(.orange) }
                if let key = node.key, let note = availability.note(key) { Text(note).font(.app(11)).foregroundStyle(.orange) }
            }
        }
    }

    @ViewBuilder private var listRow: some View {
        if let key = node.key {
            let entries = node.inlineEntries ?? catalog.array(node.entries), values = node.inlineValues ?? catalog.array(node.values)
            // P319 «Звуки»: not stored, it reads and writes the shutter and timer switches (SettingsActivity.setupSounds)
            let selection: Binding<String> = key == Sounds.key
                ? Binding(get: { Sounds.value(store) }, set: { Sounds.set(store, $0) })
                : Binding(get: { store.string(key, node.defaultValue?.text ?? values.first ?? "") }, set: { store.set(key, $0) })
            if node.segmented == true {
                VStack(alignment: .leading, spacing: 6) {
                    label(detail: nil)
                    Picker("", selection: selection) {
                        ForEach(Array(zip(entries, values)), id: \.1) { Text($0.0).tag($0.1) }
                    }
                    .pickerStyle(.segmented)
                }
            } else if menuLists {
                HStack {
                    label(detail: nil)
                    Spacer()
                    Picker("", selection: selection) {
                        ForEach(Array(zip(entries, values)), id: \.1) { Text($0.0).tag($0.1) }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                }
            } else {
                Picker(selection: selection) {
                    ForEach(Array(zip(entries, values)), id: \.1) { Text($0.0).tag($0.1) }
                } label: { label(detail: nil) }
                .pickerStyle(.navigationLink)
            }
        }
    }

    @ViewBuilder private var sliderRow: some View {
        if let key = node.key {
            let stored = store.string(key, node.defaultValue?.text ?? "0")
            let def = SliderGrid.number(node.defaultValue?.text ?? "0", fallback: node.min ?? 0)
            let value = SliderGrid.number(stored, fallback: def)
            let reset = {
                // P357 DoubleTapReset: the default back (stored exactly, as UniversalSeekBarPreference.resetToDefault)
                guard enabled else { return }
                store.set(key, SliderGrid.exact(Swift.min(Swift.max(def, node.min ?? def), node.max ?? def), decimal: node.float == true))
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    label(detail: nil)
                    Spacer()
                    // P574 PrecisionEditor: a tap on the value types it, a double tap puts the default back
                    Text(CameraStyle.decimal(stored)).font(.app(13).monospacedDigit()).foregroundStyle(.secondary)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.secondary.opacity(0.15), in: Capsule())
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2, perform: reset)
                        .onTapGesture { if enabled && source == nil { precise = true } }
                        .accessibilityAddTraits(.isButton)
                        .accessibilityHint(L("set.precise.type"))
                }
                Slider(value: Binding(get: { Double(SliderGrid.progress(of: value, node: node)) },
                                      set: { store.set(key, SliderGrid.text(progress: Int($0.rounded()), node: node)) }),
                       in: 0...Double(SliderGrid.steps(node)), step: 1)
                    .simultaneousGesture(TapGesture(count: 2).onEnded { reset() })
            }
            .sheet(isPresented: $precise) {
                PrecisionEditor(title: catalog.text(node.title) ?? key, min: node.min ?? 0, max: node.max ?? 100,
                                value: value, fallback: def, decimal: node.float == true,
                                step: node.float == true ? 1 / (node.step ?? 1) : 1,
                                decimals: node.float == true ? SliderGrid.decimals(step: node.step ?? 1, min: node.min ?? 0) : 0) { v in
                    store.set(key, SliderGrid.exact(v, decimal: node.float == true))
                }
            }
        }
    }
}

/// «Звуки» (pref_camera_sounds_all, P319): shutter and timer, shutter only, timer only, none - over the two switches
/// (pref_camera_sounds_key, pref_timer_sound_key: their keys, defaults and backups unchanged), which the page hides
enum Sounds {
    static let key = "pref_camera_sounds_all", shutter = "pref_camera_sounds_key", timer = "pref_timer_sound_key"

    private static func def(_ key: String) -> Bool { PageReset.find(key)?.defaultValue?.bool ?? true }

    static func value(_ store: any SettingsValues) -> String {
        let s = store.bool(shutter, def(shutter)), t = store.bool(timer, def(timer))
        return s ? (t ? "all" : "shutter") : (t ? "timer" : "none")
    }

    static func set(_ store: any SettingsValues, _ v: String) {
        store.set(shutter, v == "all" || v == "shutter")
        store.set(timer, v == "all" || v == "timer")
    }
}
