import SwiftUI
import UniformTypeIdentifiers

/// ConfigXml (settings/ConfigXml.java): the SCAMERA config file - every exported prefs file as typed entries in the element names of
/// Android's own shared_prefs XML (string, boolean, int, long, float, set), so a value comes back with the type it was saved with.
/// A plain shared_prefs file (root <map>) reads as the "main" file. The iPhone writes its settings as "main" (it has no module
/// profiles, per-lens file or lens passports), so a config moves between the iPhone and an Android phone as Android's shared
/// settings do between two phones.
enum ConfigXml {
    static let root = "scamera-config", main = "main", version = 1

    struct Config {
        var attributes: [String: String] = [:]
        var files: [String: [String: Any]] = [:]
        var legacyMap = false
    }

    enum Failure: LocalizedError {
        case bad(String)
        var errorDescription: String? {
            switch self {
            case .bad(let s): return s
            }
        }
    }

    private static func escape(_ s: String, attribute: Bool) -> String {
        var out = ""
        for u in s.unicodeScalars {
            switch u {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"" where attribute: out += "&quot;"
            case "\n" where attribute, "\r", "\t" where attribute: out += "&#\(u.value);"
            default:
                if u.value < 0x20 && u != "\n" && u != "\t" { out += "&#\(u.value);" } else { out.unicodeScalars.append(u) }
            }
        }
        return out
    }

    /// One entry: its element by the value's type (a Bool, an int / long / float NSNumber, a String, a set of Strings)
    private static func entry(_ key: String, _ v: Any) -> String? {
        let name = "name=\"" + escape(key, attribute: true) + "\""
        if PrefValue.isBool(v) { return "<boolean \(name) value=\"\((v as! NSNumber).boolValue ? "true" : "false")\" />" }
        if let s = v as? String { return "<string \(name)>" + escape(s, attribute: false) + "</string>" }
        if let n = v as? NSNumber {
            if CFNumberIsFloatType(n) { return "<float \(name) value=\"\(n.floatValue)\" />" }
            let i = n.int64Value
            return i >= Int64(Int32.min) && i <= Int64(Int32.max) ? "<int \(name) value=\"\(i)\" />" : "<long \(name) value=\"\(i)\" />"
        }
        if let set = v as? [String] {
            return "<set \(name)>" + set.map { "<string>" + escape($0, attribute: false) + "</string>" }.joined() + "</set>"
        }
        return nil
    }

    static func write(attributes: [(String, String)], files: [(String, [String: Any])]) -> Data {
        var s = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<" + root + " version=\"\(version)\""
        for (k, v) in attributes { s += " \(k)=\"" + escape(v, attribute: true) + "\"" }
        s += ">"
        for (file, values) in files {
            s += "\n  <prefs file=\"" + escape(file, attribute: true) + "\">"
            // sorted keys: two configs of the same settings compare line by line
            for key in values.keys.sorted() {
                if let e = entry(key, values[key]!) { s += "\n    " + e }
            }
            s += "\n  </prefs>"
        }
        s += "\n</" + root + ">\n"
        return Data(s.utf8)
    }

    static func read(_ data: Data) throws -> Config {
        let reader = Reader()
        let parser = XMLParser(data: data)
        parser.delegate = reader
        let ok = parser.parse()
        if let e = reader.failure { throw Failure.bad(e) }
        if !ok { throw Failure.bad("XML: " + (parser.parserError?.localizedDescription ?? "unreadable")) }
        if !reader.sawRoot { throw Failure.bad("empty file") }
        return reader.config
    }

    private final class Reader: NSObject, XMLParserDelegate {
        var config = Config()
        var failure: String?
        var sawRoot = false
        private var file: String?
        private var entries: [String: Any] = [:]
        private var leaf: String?
        private var name = ""
        private var text = ""
        private var set: [String]?
        private var skip = 0

        private func fail(_ parser: XMLParser, _ message: String) {
            if failure == nil { failure = message }
            parser.abortParsing()
        }

        func parser(_ parser: XMLParser, didStartElement el: String, namespaceURI: String?, qualifiedName: String?,
                    attributes a: [String: String] = [:]) {
            if !sawRoot {
                sawRoot = true
                if el == "map" {
                    config.legacyMap = true
                    file = ConfigXml.main
                    entries = [:]
                } else if el == ConfigXml.root {
                    config.attributes = a
                } else {
                    fail(parser, "not a SCAMERA config: <\(el)>")
                }
                return
            }
            if skip > 0 { skip += 1; return }
            guard file != nil else {
                if el == "prefs" {
                    guard let f = a["file"], !f.isEmpty else { return fail(parser, "<prefs> without file") }
                    file = f
                    entries = [:]
                } else {
                    skip = 1
                }
                return
            }
            if set != nil {
                if el == "string" { leaf = "set-string"; text = "" } else { skip = 1 }
                return
            }
            guard let n = a["name"] else { skip = 1; return }
            let value = a["value"]
            switch el {
            case "boolean": entries[n] = (value ?? "").lowercased() == "true"; leaf = el
            case "int":
                guard let v = value.flatMap({ Int32($0) }) else { return fail(parser, "bad value of \(n): \(value ?? "null")") }
                entries[n] = NSNumber(value: Int(v)); leaf = el
            case "long":
                guard let v = value.flatMap({ Int64($0) }) else { return fail(parser, "bad value of \(n): \(value ?? "null")") }
                entries[n] = NSNumber(value: v); leaf = el
            case "float":
                guard let v = value.flatMap({ Float($0) }) else { return fail(parser, "bad value of \(n): \(value ?? "null")") }
                entries[n] = NSNumber(value: v); leaf = el
            case "string": name = n; text = ""; leaf = el
            case "set": name = n; set = []
            default: skip = 1
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if leaf == "string" || leaf == "set-string" { text += string }
        }

        func parser(_ parser: XMLParser, didEndElement el: String, namespaceURI: String?, qualifiedName: String?) {
            if skip > 0 { skip -= 1; return }
            if leaf == "set-string" && el == "string" { set?.append(text); leaf = nil; return }
            if el == "set", let s = set { entries[name] = s; set = nil; return }
            if leaf == "string" && el == "string" { entries[name] = text; leaf = nil; return }
            if leaf != nil { leaf = nil; return }
            if (el == "prefs" || el == "map"), let f = file {
                config.files[f] = entries
                file = nil
            }
        }
    }

    /// An old JSON backup's main settings ({"main_preferences": {...}}): BackupRestoreUtil.putJsonValueToEditor's types
    static func readJson(_ data: Data) throws -> Config {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.bad("not a JSON object") }
        var config = Config()
        guard let main = root["main_preferences"] as? [String: Any] else { return config }
        var out: [String: Any] = [:]
        for (k, v) in main {
            if PrefValue.isBool(v) { out[k] = (v as! NSNumber).boolValue } else if let n = v as? NSNumber {
                out[k] = CFNumberIsFloatType(n) ? NSNumber(value: n.floatValue) : n
            } else if let s = v as? String {
                if (k.hasPrefix("pref_tunable_") || k.hasPrefix("pref_sensorconfig_")), let d = Double(s) {
                    out[k] = s.contains(".") ? NSNumber(value: Float(d)) : NSNumber(value: Int(d))
                } else {
                    out[k] = s
                }
            } else if let a = v as? [Any] {
                out[k] = a.map { "\($0)" }
            } else if let data = try? JSONSerialization.data(withJSONObject: v), let s = String(data: data, encoding: .utf8) {
                out[k] = s
            }
        }
        config.files[ConfigXml.main] = out
        return config
    }
}

/// ConfigState (settings/ConfigState.java): the config the settings came from - kept apart from the settings, so a config never
/// carries it; set when a config is saved or loaded, cleared by the reset to defaults.
enum ConfigState {
    private static let activeKey = "scam.config_state.active", detachedKey = "scam.config_state.detached"

    static func active() -> String? {
        let name = UserDefaults.standard.string(forKey: activeKey)?.trimmingCharacters(in: .whitespaces) ?? ""
        return name.isEmpty ? nil : name
    }

    static func label() -> String {
        if let a = active() { return a }
        return UserDefaults.standard.bool(forKey: detachedKey) ? Lang.t("Без профиля", "No profile") : Lang.t("Стандартный", "Default")
    }

    static func set(_ fileName: String?) {
        let name = base(fileName)
        if name.isEmpty { clear(); return }
        UserDefaults.standard.set(name, forKey: activeKey)
        UserDefaults.standard.removeObject(forKey: detachedKey)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: activeKey)
        UserDefaults.standard.removeObject(forKey: detachedKey)
    }

    static func detach() {
        UserDefaults.standard.removeObject(forKey: activeKey)
        UserDefaults.standard.set(true, forKey: detachedKey)
    }

    /// «SCAMERA/XML/Night_1.xml» -> «Night_1»
    static func base(_ fileName: String?) -> String {
        var name = (fileName ?? "").trimmingCharacters(in: .whitespaces)
        if let cut = name.lastIndex(where: { $0 == "/" || $0 == ":" }) { name = String(name[name.index(after: cut)...]) }
        return name.replacingOccurrences(of: "\\.(xml|json)$", with: "", options: [.regularExpression, .caseInsensitive])
            .trimmingCharacters(in: .whitespaces)
    }
}

/// BackupRestoreUtil / ConfigFolder on the iPhone: the configs in Documents/XML (the Files app: SCAMERA › XML; Android:
/// Download/SCAMERA/XML), saved from the settings, loaded from there or from any file the document picker gives, shared through the
/// share sheet; the reset to defaults.
@Observable
final class ProfileLibrary {
    static let shared = ProfileLibrary()
    var files: [URL] = []
    var message: String?

    static var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("XML", isDirectory: true)
    }

    /// Newest (dated) names first
    func refresh() {
        let all = (try? FileManager.default.contentsOfDirectory(at: Self.folder, includingPropertiesForKeys: nil)) ?? []
        files = all.filter { ["xml", "json"].contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedDescending }
    }

    static var device: String { "Apple/" + BuildInfo.machine }

    /// Main-prefs keys that describe this phone, never another one: module slots, sensor configs, camera ids, spoof
    static func deviceOnly(_ key: String) -> Bool {
        key.hasPrefix("module_") || key.hasPrefix("pref_sensorconfig_") || key.hasPrefix("lens_") || key == "camera_id"
            || key == "user_camera_ids" || key == "hidden_camera_ids" || key == "pref_camera_package_spoof_enabled"
            || key.hasSuffix("_spoof_package_key") || key == "device_defaults_version"
    }

    /// BackupPreferences: the name offered (backup_file_name: SCAMERA_yyyyMMdd_HHmmss)
    static func offeredName() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return "SCAMERA_" + f.string(from: Date())
    }

    /// BackupPreferences.isAcceptedCharacter: letters, digits and «_»
    static func accepted(_ typed: String) -> String {
        String(typed.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == "_" }.map(Character.init))
    }

    /// BackupRestoreUtil.backupSettings
    @discardableResult
    func save(_ typed: String) -> URL? {
        let base = typed.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "\\.(xml|json)$", with: "",
                                                                                   options: [.regularExpression, .caseInsensitive])
        guard !base.isEmpty else {
            message = Lang.t("Ошибка сохранения: ", "Save failed: ") + Lang.t("Название не может быть пустым", "File name cannot be an empty string")
            return nil
        }
        let created = { () -> String in
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
            return f.string(from: Date())
        }()
        let data = ConfigXml.write(attributes: [("device", Self.device), ("model", BuildInfo.machine),
                                                ("app", "iOS \(BuildInfo.version) (\(BuildInfo.build))"), ("created", created)],
                                   files: [(ConfigXml.main, SettingsStore.shared.values)])
        do {
            try FileManager.default.createDirectory(at: Self.folder, withIntermediateDirectories: true)
            let url = Self.folder.appendingPathComponent(base + ".xml")
            try data.write(to: url, options: .atomic)
            ConfigState.set(base)
            message = Lang.t("Сохранено: ", "Saved: ") + "SCAMERA/XML/" + url.lastPathComponent
            refresh()
            return url
        } catch {
            message = Lang.t("Ошибка сохранения: ", "Save failed: ") + error.localizedDescription
            return nil
        }
    }

    /// BackupRestoreUtil.restorePreferences / restoreFromUri: an XML config (or a plain shared_prefs map), or an old JSON backup.
    /// The phone that saved it gets everything verbatim; from another phone (any Android one) this phone's own keys stay.
    func load(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let label = url.lastPathComponent
        do {
            let data = try Data(contentsOf: url)
            var start = data.startIndex
            if data.count >= 3 && data[start] == 0xEF && data[start + 1] == 0xBB && data[start + 2] == 0xBF { start += 3 }
            while start < data.endIndex && [0x20, 0x09, 0x0A, 0x0D].contains(data[start]) { start += 1 }
            let json = start < data.endIndex && data[start] == UInt8(ascii: "{")
            let config = try (json ? ConfigXml.readJson(data.subdata(in: start..<data.endIndex)) : ConfigXml.read(data))
            guard let main = config.files[ConfigXml.main] else { throw ConfigXml.Failure.bad("the config has no main settings") }
            let from = config.attributes["device"]
            let same = from == Self.device
            var merged: [String: Any] = [:]
            if same {
                merged = main
            } else {
                for (k, v) in main where !Self.deviceOnly(k) { merged[k] = v }
                for (k, v) in SettingsStore.shared.values where Self.deviceOnly(k) { merged[k] = v }
            }
            SettingsStore.shared.replaceAll(merged)
            ConfigState.set(label)
            if json {
                message = Lang.t("Загружено (старый JSON): ", "Imported (old JSON): ") + label
            } else if same {
                message = Lang.t("Загружено: ", "Imported: ") + label
            } else {
                let who = from ?? Lang.t("неизвестного телефона", "an unknown phone")
                message = Lang.t("Загружено с " + who + " (профили модулей на iPhone не используются). Файл: ",
                                 "Imported from " + who + " (module profiles are not used on the iPhone). File: ") + label
            }
            SessionLog.i("Settings", "config \(label) imported: \(merged.count) keys, from \(from ?? "?")")
        } catch {
            message = Lang.t("Ошибка загрузки: ", "Import failed: ") + error.localizedDescription
        }
    }

    func delete(_ url: URL) {
        do {
            try FileManager.default.removeItem(at: url)
            if ConfigState.base(url.lastPathComponent) == ConfigState.active() { ConfigState.detach() }
            message = Lang.t("Удалён «" + url.lastPathComponent + "»", "Deleted «" + url.lastPathComponent + "»")
        } catch {
            message = Lang.t("Не удалось удалить «" + url.lastPathComponent + "»", "Could not delete «" + url.lastPathComponent + "»")
        }
        refresh()
    }

    /// BackupRestoreUtil.resetPreferences: every setting back to its default; the developer lines (scam_dev.txt on Android, the
    /// app's own store here) set aside as scam.dev.bak, so they can be taken back
    func reset() {
        ConfigState.clear()
        SettingsStore.shared.reset()
        let defaults = UserDefaults.standard
        if let dev = defaults.dictionary(forKey: PostSettings.devKey) {
            defaults.set(dev, forKey: PostSettings.devKey + ".bak")
            defaults.removeObject(forKey: PostSettings.devKey)
        }
        message = Lang.t("Настройки сброшены", "Settings reset")
        SessionLog.i("Settings", "reset to defaults")
    }
}

/// ProfilesPreference: «Сейчас» - the active profile; «Сохранённые» - the configs of the folder, newest first, each with «Загрузить»,
/// «Поделиться» and «Удалить файл» in its panel.
struct ProfilesCard: View {
    private var library: ProfileLibrary { ProfileLibrary.shared }
    @State private var open: URL?
    @State private var sharing: [URL]?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Lang.t("Сейчас", "Now")).font(.footnote.weight(.semibold)).foregroundStyle(Color.accentColor)
            HStack(spacing: 12) {
                Image("cs_ic_layers").resizable().frame(width: 22, height: 22)
                VStack(alignment: .leading) {
                    Text(ConfigState.label()).font(.body.weight(.semibold))
                    Text(Lang.t("Активный профиль", "The active profile")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
            }
            Text(Lang.t("Сохранённые", "Saved")).font(.footnote.weight(.semibold)).foregroundStyle(Color.accentColor).padding(.top, 8)
            if library.files.isEmpty {
                Text(Lang.t("Пока ничего не сохранено: «Сохранить текущие как…» ниже.", "Nothing saved yet: «Save the current as…» below."))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(library.files, id: \.self) { url in
                let base = ConfigState.base(url.lastPathComponent), active = base == ConfigState.active()
                VStack(alignment: .leading, spacing: 6) {
                    Button { open = open == url ? nil : url } label: {
                        HStack(spacing: 12) {
                            Image("cs_ic_layers").resizable().frame(width: 22, height: 22)
                            VStack(alignment: .leading) {
                                Text(base).font(.body.weight(.semibold)).foregroundStyle(.primary)
                                Text(url.lastPathComponent).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(active ? Lang.t("Сейчас", "Now") : Lang.t("Загрузить", "Load")).font(.footnote)
                                .foregroundStyle(active ? Color.secondary : Color.accentColor)
                        }
                    }
                    if open == url {
                        Text(Lang.t("Текущие настройки заменятся.", "The current settings are replaced.")).font(.caption).foregroundStyle(.secondary)
                        HStack(spacing: 8) {
                            Button { library.load(url); open = nil } label: {
                                Text(Lang.t("Загрузить", "Load")).font(.footnote.weight(.semibold)).padding(.horizontal, 16).padding(.vertical, 8)
                                    .background(Color.accentColor, in: Capsule()).foregroundStyle(.black)
                            }
                            Button { sharing = [url] } label: {
                                Text(Lang.t("Поделиться", "Share")).font(.footnote.weight(.semibold)).padding(.horizontal, 16).padding(.vertical, 8)
                                    .overlay(Capsule().stroke(Color.accentColor))
                            }
                            Button(role: .destructive) { library.delete(url); open = nil } label: {
                                Text(Lang.t("Удалить файл", "Delete the file")).font(.footnote.weight(.semibold)).padding(.horizontal, 16).padding(.vertical, 8)
                                    .overlay(Capsule().stroke(Color.red))
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .buttonStyle(.borderless)
        .padding(.vertical, 6)
        .onAppear { library.refresh() }
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) { ActivityView(items: sharing ?? []) }
        .alert(library.message ?? "", isPresented: Binding(get: { library.message != nil }, set: { if !$0 { library.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }
}

/// BackupPreferences: «Сохранить текущие как…» - the name (letters, digits, «_»), then the config in the folder; the saved file
/// can go to the share sheet at once.
struct BackupRow<Label: View>: View {
    let label: Label
    @State private var asking = false
    @State private var name = ""
    @State private var sharing: [URL]?

    var body: some View {
        Button { name = ProfileLibrary.offeredName(); asking = true } label: { label }
            .alert(Lang.t("Сохранить текущие как…", "Save the current as…"), isPresented: $asking) {
                TextField(Lang.t("Название", "Name"), text: Binding(get: { name }, set: { name = ProfileLibrary.accepted($0) }))
                Button(Lang.t("Сохранить", "Save")) { ProfileLibrary.shared.save(name) }
                Button(Lang.t("Сохранить и поделиться", "Save and share")) {
                    if let url = ProfileLibrary.shared.save(name) { sharing = [url] }
                }
                Button(Lang.t("Отмена", "Cancel"), role: .cancel) {}
            } message: {
                Text(Lang.t("Все настройки сохраняются в XML-файл в папке ", "All settings are saved to an XML file in the folder ") + "SCAMERA/XML")
            }
            .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) { ActivityView(items: sharing ?? []) }
    }
}

/// RestorePreference: a config from any folder (another phone's, a shared file) through the document picker
struct RestoreRow<Label: View>: View {
    let label: Label
    @State private var picking = false

    var body: some View {
        Button { picking = true } label: { label }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.xml, .json, .data]) { result in
                if case .success(let url) = result { ProfileLibrary.shared.load(url) }
            }
    }
}

/// ResetPreferences: «Сбросить все настройки?» - «Отмена» / red «Сбросить»
struct ResetRow<Label: View>: View {
    let label: Label
    @State private var asking = false

    var body: some View {
        Button(role: .destructive) { asking = true } label: { label }
            .alert(Lang.t("Сбросить все настройки?", "Reset all settings?"), isPresented: $asking) {
                Button(Lang.t("Сбросить", "Reset"), role: .destructive) { ProfileLibrary.shared.reset() }
                Button(Lang.t("Отмена", "Cancel"), role: .cancel) {}
            } message: {
                Text(Lang.t("Это сбросит настройки на стандартные, продолжить?", "This will reset all settings to their default values. Continue?"))
            }
    }
}
