import SwiftUI

/// PageReset (ui/settings/PageReset.java): the rows of one page back to their defaults - the switches, lists, text rows and sliders
/// by the defaults of preferences.xml, «Стиль снимка» to «Обычный»; the rows of the page's sub-pages and of hidden rows stay.
enum PageReset {
    private static var store: SettingsStore { SettingsStore.shared }

    /// The rows of `node` (not of its sub-pages) back to their defaults; how many changed
    static func reset(_ node: SettingsNode) -> Int {
        var changed = 0
        for c in node.children ?? [] {
            if c.type == "screen" { continue }
            if c.type == "category" { changed += reset(c); continue }
            if resetRow(c) { changed += 1 }
        }
        return changed
    }

    static func resetRow(_ n: SettingsNode) -> Bool {
        guard let key = n.key, !SettingsAvailability(store.values).hidden(key) else { return false }
        if n.type == "custom:PhotoStylePreference" {
            if PhotoStyle.current(store) == PhotoStyle.normal { return false }
            PhotoStyle.apply(store, PhotoStyle.normal)
            return true
        }
        guard let def = n.defaultValue else { return false }
        switch n.type {
        case "switch":
            if store.bool(key, def.bool) == def.bool { return false }
            store.set(key, def.bool)
            return true
        case "list":
            let values = SettingsCatalog.shared.array(n.values)
            if store.string(key, def.text) == def.text || !values.contains(def.text) { return false }
            store.set(key, def.text)
            return true
        case "text":
            if store.string(key, def.text) == def.text { return false }
            store.set(key, def.text)
            return true
        case "slider":
            let d = Double(def.text.replacingOccurrences(of: ",", with: ".")) ?? 0
            let now = Double(store.string(key, def.text).replacingOccurrences(of: ",", with: "."))
            if store.values[key] == nil || now == d { return false }
            store.set(key, SliderGrid.text(progress: SliderGrid.progress(of: d, node: n), node: n))
            return true
        default:
            return false
        }
    }

    static func find(_ key: String, in node: SettingsNode? = SettingsCatalog.shared.tree) -> SettingsNode? {
        guard let node else { return nil }
        if node.key == key { return node }
        for c in node.children ?? [] { if let f = find(key, in: c) { return f } }
        return nil
    }

    /// «Сбросить ScamMaster» (OldstyleMode.CATEGORIES): the rows of the group's categories (the version stays)
    static let oldstyleCategories = ["oldstyle_exposure_category", "oldstyle_curve_category", "oldstyle_colour_category",
                                     "oldstyle_dehaze_category", "oldstyle_burst_category", "oldstyle_viewfinder_category"]

    static func resetOldstyle() -> Int {
        oldstyleCategories.compactMap { find($0) }.reduce(0) { $0 + reset($1) }
    }

    static func message(_ n: Int) -> String {
        n == 0 ? Lang.t("Всё уже по умолчанию", "Everything is at its default already") : Lang.t("Сброшено параметров: \(n)", "Settings reset: \(n)")
    }
}

/// The page's reset in its header (PageReset; the root page's reset is «Сбросить настройки»)
struct PageResetButton: View {
    let node: SettingsNode
    @State private var asking = false
    @State private var done: String?

    var body: some View {
        Button { asking = true } label: { Image(systemName: "arrow.counterclockwise") }
            .accessibilityLabel(Lang.t("Сбросить страницу", "Reset the page"))
            .alert(Lang.t("Сбросить эту страницу?", "Reset this page?"), isPresented: $asking) {
                Button(Lang.t("Сбросить", "Reset"), role: .destructive) { done = PageReset.message(PageReset.reset(node)) }
                Button(Lang.t("Отмена", "Cancel"), role: .cancel) {}
            } message: {
                Text(Lang.t("Настройки этой страницы вернутся к значениям по умолчанию. Вложенные страницы не меняются.",
                            "The settings of this page go back to their defaults. The pages inside it stay as they are."))
            }
            .alert(done ?? "", isPresented: Binding(get: { done != nil }, set: { if !$0 { done = nil } })) { Button("OK", role: .cancel) {} }
    }
}

/// «Сбросить ScamMaster» (pref_oldstyle_tone_reset)
struct OldstyleResetRow<Label: View>: View {
    let label: Label
    @State private var asking = false
    @State private var done: String?

    var body: some View {
        Button { asking = true } label: { label }
            .alert(Lang.t("Сбросить ScamMaster?", "Reset ScamMaster?"), isPresented: $asking) {
                Button(Lang.t("Сбросить", "Reset"), role: .destructive) { done = PageReset.message(PageReset.resetOldstyle()) }
                Button(Lang.t("Отмена", "Cancel"), role: .cancel) {}
            } message: {
                Text(Lang.t("Все ползунки и переключатели «ScamMaster Тон» вернутся к значениям по умолчанию. Версия тона и стиль останутся.",
                            "Every slider and switch of “ScamMaster Tone” goes back to its default. The tone version and the style stay."))
            }
            .alert(done ?? "", isPresented: Binding(get: { done != nil }, set: { if !$0 { done = nil } })) { Button("OK", role: .cancel) {} }
    }
}

/// «Модули камеры» (ModuleRows' root_modules) on the iPhone: the lenses AVFoundation lists (ios/Capture LensModules: a physical
/// lens or an in-sensor zoom of one), read-only. The Android cards' copies, Camera IDs, sensor modes and per-module profiles have
/// no counterpart here (the note says why).
struct LensModulesList: View {
    @Environment(\.settingsCamera) private var camera
    @State private var found: [LensModule] = []

    var body: some View {
        let modules = (camera?.modules.isEmpty == false ? camera?.modules : nil) ?? found
        Group {
            if modules.isEmpty {
                Text(L("set.modules.none")).font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(modules) { m in
                HStack(spacing: 12) {
                    Text(m.label).font(.headline.monospacedDigit()).frame(minWidth: 52, alignment: .leading)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(m.deviceName)
                        Text((m.isFront ? L("set.modules.front") : L("set.modules.back")) + " · "
                             + (m.kind == .isz ? L("set.modules.isz") : L("info.lens"))
                             + (m.id == camera?.activeModuleID ? " · " + L("set.modules.active") : ""))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Text(L("set.modules.note")).font(.caption).foregroundStyle(.secondary)
        }
        .task {
            if camera?.modules.isEmpty == false { return }
            found = await Task.detached(priority: .userInitiated) { DeviceLookup.modules() }.value
        }
    }
}

/// The session log (ios/Support/SessionLog: Documents/Log, a file a day) for «Логи»
enum LogFiles {
    static func current() -> URL? { SessionLog.shared.flushedFiles().first }

    static func sizeText() -> String {
        let bytes = (current().flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path)[.size] as? NSNumber })?.int64Value ?? 0
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    /// The earlier days' files go; today's stays (the log keeps writing into it)
    static func clearEarlier() -> Int {
        let files = SessionLog.shared.flushedFiles()
        var n = 0
        for url in files.dropFirst() where (try? FileManager.default.removeItem(at: url)) != nil { n += 1 }
        return n
    }

    /// The last shot's SHDR lines (CameraModel logs a merge's report under the SHDR tag, one block a shot)
    static func lastShdrReport() -> [String] {
        for url in SessionLog.shared.flushedFiles() {
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            var block: [String] = [], last: [String] = []
            for line in text.components(separatedBy: .newlines) {
                if line.contains(" I/SHDR: ") || line.contains(" W/SHDR: ") || line.contains(" E/SHDR: ") {
                    block.append(line)
                } else if !block.isEmpty {
                    last = block
                    block = []
                }
            }
            if !block.isEmpty { last = block }
            if !last.isEmpty { return last }
        }
        return []
    }
}

/// «Отчёт последней съёмки SHDR»: what the merge recorded about the last shot (its lines of the session log)
struct ShdrReportPage: View {
    @State private var lines: [String] = []
    @State private var sharing: [URL]?

    var body: some View {
        List {
            if lines.isEmpty {
                Text(L("set.report.none")).foregroundStyle(.secondary)
            }
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line).font(.caption.monospaced()).textSelection(.enabled)
            }
        }
        .navigationTitle(SettingsCatalog.shared.text(SettingsNode.Text(s: "prefs_shdr_capture_report_title", t: nil)) ?? "SHDR")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { if let url = LogFiles.current() { sharing = [url] } } label: { Image(systemName: "square.and.arrow.up") }
            }
        }
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) { ActivityView(items: sharing ?? []) }
        .onAppear { lines = LogFiles.lastShdrReport() }
    }
}

/// «Очистить текущий лог»: the size on the row; a tap deletes the earlier days' logs (today's is the one being written)
struct LogClearRow<Label: View>: View {
    let label: (String) -> Label
    @State private var size = ""
    @State private var done: String?

    var body: some View {
        Button {
            let n = LogFiles.clearEarlier()
            done = String(format: L("set.log.cleared"), n)
            size = LogFiles.sizeText()
        } label: { label(String(format: L("set.log.size"), size)) }
            .onAppear { size = LogFiles.sizeText() }
            .alert(done ?? "", isPresented: Binding(get: { done != nil }, set: { if !$0 { done = nil } })) { Button("OK", role: .cancel) {} }
    }
}

/// «Поделиться текущим логом»: today's log file through the share sheet
struct LogShareRow<Label: View>: View {
    let label: Label
    @State private var sharing: [URL]?

    var body: some View {
        Button { if let url = LogFiles.current() { sharing = [url] } } label: { label }
            .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) { ActivityView(items: sharing ?? []) }
    }
}

enum SettingsExtras {
    /// A line over a page that means something else on the iPhone
    static func screenNote(_ key: String?) -> String? {
        switch key {
        case "lens_screen": return L("set.lens.note")
        case "shade_transparency_screen": return L("set.na.shade")
        default: return nil
        }
    }

    /// What a category built in code on Android shows here
    @ViewBuilder static func categoryContent(_ key: String?) -> some View {
        if key == "root_modules" {
            LensModulesList()
        }
    }

    /// «Версия»: the version, the build and the phone (version_summary: v%1$s · %3$s, then the source revision)
    static var versionText: String { "v\(BuildInfo.version).\(BuildInfo.build) · \(BuildInfo.machine)\n\(BuildInfo.sha)" }
}
