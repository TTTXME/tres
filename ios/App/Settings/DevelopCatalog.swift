import Foundation

/// P665 (DevelopCatalog.java): the camera's processing settings as the gallery developer offers them - every row of the settings
/// that changes the photo after the merge, in the settings' own order and names, read from the settings tree
/// (android_settings.json; the groups' parts from DevelopCatalog.GROUPS by gen_settings_ui.py), grouped as the camera's settings:
/// Тон, Света, Резкость, Шумоподавление, Цвет, Плёнка, LUT, Портрет, Oldstyle (a shot of that mode), and Склейка - the merge's rows,
/// greyed: the DNG holds the merge's result. Each row is drawn by the settings screen's own row (SettingsRow).
enum DevelopCatalog {
    static let tone = "tone", lights = "lights", sharp = "sharp", noise = "noise", colour = "colour", film = "film", lut = "lut",
               portrait = "portrait", oldstyle = "oldstyle", merge = "merge"
    /// ScamLabFragment.CURVE: the tone curve (a setting of its own page)
    static let curveKey = "pref_scam_hybrid_why_curve_points"

    enum Kind { case toggle, list, slider, custom, text }

    struct Row: Identifiable {
        let key: String
        let title: String
        /// the caption of the part it is in (a category or a page of the group), nil for the group's own rows
        let section: String?
        let kind: Kind
        /// the settings row that draws it (the tree's, or one made for the film / LUT / filter lists)
        let node: SettingsNode
        /// the setting's default as the camera stores it (Bool or String)
        let def: Any
        /// why it never changes a develop (the merge's, fixed at the shot, edited on the camera's page), nil: it can
        let fixed: String?
        var id: String { key }
    }

    struct Group: Identifiable {
        let id: String
        let title: String
        let rows: [Row]
    }

    /// The group's name.
    static func title(_ id: String) -> String {
        switch id {
        case lut: return "LUT"
        case oldstyle: return "Oldstyle"
        default: return L("develop.group." + id)
        }
    }

    /// The groups for a shot whose settings were `shot` (the record's); the Oldstyle group only for a shot of that mode.
    static func groups(shot: [String: Any]) -> [Group] {
        guard let table = SettingsCatalog.shared.develop, let tree = SettingsCatalog.shared.tree else { return [] }
        let availability = SettingsAvailability(shot)
        let oldstyleShot = PrefValue.bool(shot["pref_oldstyle_mode"], false)
        var seen = Set<String>()
        var out: [Group] = []
        for g in table.groups {
            guard let id = g.first else { continue }
            if id == oldstyle && !oldstyleShot { continue }
            let isMerge = id == merge
            var rows: [Row] = []
            func add(_ row: Row?) {
                guard let row, !seen.contains(row.key) else { return }
                seen.insert(row.key)
                rows.append(row)
            }
            func walk(_ group: SettingsNode, _ section: String?) {
                for n in group.children ?? [] {
                    if let k = n.key, availability.hidden(k) { continue }
                    if n.type == "screen" || n.type == "category" {
                        walk(n, SettingsCatalog.shared.text(n.title))
                    } else if n.type == "custom:FilmSimPreference" {
                        for r in filmRows(section, isMerge, table) { add(r) }
                    } else {
                        add(row(n, section, isMerge, table))
                    }
                }
            }
            if id == film {
                for r in filmRows(nil, false, table) { add(r) }
            } else {
                for i in 1..<g.count {
                    let key = g[i]
                    if availability.hidden(key) { continue }
                    if let n = find(tree, key) {
                        if n.type == "screen" || n.type == "category" {
                            walk(n, i == 1 && g.count == 2 ? nil : SettingsCatalog.shared.text(n.title))
                        } else {
                            add(row(n, nil, isMerge, table))
                        }
                    } else if key == "pref_lens_filter_strength" {
                        add(made(key, L("develop.filter_strength"), nil, .slider, min: 0, max: 100, step: 1, def: "50", isMerge, table))
                    }
                }
            }
            if !rows.isEmpty { out.append(Group(id: id, title: title(id), rows: rows)) }
        }
        return out
    }

    static func find(_ n: SettingsNode, _ key: String) -> SettingsNode? {
        if n.key == key { return n }
        for c in n.children ?? [] { if let f = find(c, key) { return f } }
        return nil
    }

    /// ScamLabFragment.comparable + spec: the row of a tree's node, nil for one the develop does not offer
    private static func row(_ n: SettingsNode, _ section: String?, _ isMerge: Bool, _ table: SettingsCatalog.DevelopTable) -> Row? {
        guard let key = n.key else { return nil }
        let title = SettingsCatalog.shared.text(n.title) ?? key
        let kind: Kind
        let def: Any
        var node = n
        switch n.type {
        case "switch":
            kind = .toggle
            def = n.defaultValue?.bool ?? false
        case "list":
            kind = .list
            def = n.defaultValue?.text ?? SettingsCatalog.shared.array(n.values).first ?? ""
        case "slider":
            kind = .slider
            def = n.defaultValue?.text ?? SliderGrid.javaFloat(Float(n.min ?? 0))
        case "text":
            kind = .text
            def = n.defaultValue?.text ?? ""
        case "custom:ToneCurvePreference" where key == curveKey:
            kind = .custom
            def = ""
        case "custom:FilmLutPreference":
            kind = .list
            def = ""
            let values = FilmCatalog.luts()
            node = list(key, title, values, values.map { FilmCatalog.lutName($0) })
        case "custom:LensFilterPreference":
            kind = .list
            def = ""
            let values = [""] + SCAMFilm.lensFilters()
            node = list(key, title, values, values.map { FilmCatalog.lensName($0) })
        default:
            return nil
        }
        return Row(key: key, title: title, section: section, kind: kind, node: node, def: def,
                   fixed: fixedReason(key, kind, isMerge, table))
    }

    private static func list(_ key: String, _ title: String, _ values: [String], _ entries: [String]) -> SettingsNode {
        var n = SettingsNode(type: "list", key: key, title: SettingsNode.Text(s: nil, t: title), summary: nil, defaultValue: .text(values.first ?? ""),
                             dependency: nil, icon: nil, entries: nil, values: nil, segmented: nil, min: nil, max: nil, step: nil, float: nil,
                             children: nil, url: nil)
        n.inlineEntries = entries
        n.inlineValues = values
        return n
    }

    private static func made(_ key: String, _ title: String, _ section: String?, _ kind: Kind, min: Double = 0, max: Double = 0, step: Double = 1,
                             def: String, _ isMerge: Bool, _ table: SettingsCatalog.DevelopTable,
                             values: [String] = [], entries: [String] = []) -> Row {
        let node: SettingsNode
        if kind == .list {
            node = list(key, title, values, entries)
        } else {
            node = SettingsNode(type: "slider", key: key, title: SettingsNode.Text(s: nil, t: title), summary: nil, defaultValue: .text(def),
                                dependency: nil, icon: nil, entries: nil, values: nil, segmented: nil, min: min, max: max,
                                step: step < 1 ? (1 / step).rounded() : 1, float: step < 1, children: nil, url: nil)
        }
        return Row(key: key, title: title, section: section, kind: kind, node: node, def: def, fixed: fixedReason(key, kind, isMerge, table))
    }

    /// The film simulation's rows (FilmSim.KEYS as the iPhone's film page offers them: the stock, print, format, effects; the
    /// advanced DIR / halation / development rows are not on the iPhone's page)
    private static func filmRows(_ section: String?, _ isMerge: Bool, _ table: SettingsCatalog.DevelopTable) -> [Row] {
        let stocks = [""] + FilmCatalog.stocks.map(\.id)
        let stockNames = [L("film.none")] + FilmCatalog.stocks.map { $0.brand + " " + $0.name }
        let diffusions = SCAMFilm.diffusions().contains("") ? SCAMFilm.diffusions() : [""] + SCAMFilm.diffusions()
        func slider(_ key: String, _ title: String, _ min: Double, _ max: Double, _ step: Double, _ def: String) -> Row {
            made(key, title, section, .slider, min: min, max: max, step: step, def: def, isMerge, table)
        }
        return [
            made("pref_film_sim_stock", L("develop.film.stock"), section, .list, def: "", isMerge, table, values: stocks, entries: stockNames),
            made("pref_film_sim_print", L("develop.film.print"), section, .list, def: FilmCatalog.prints.first?.id ?? "", isMerge, table,
                 values: FilmCatalog.prints.map(\.id), entries: FilmCatalog.prints.map { $0.brand + " " + $0.name }),
            made("pref_film_sim_format", L("develop.film.format"), section, .list, def: "135", isMerge, table,
                 values: FilmCatalog.formats.map(\.id), entries: FilmCatalog.formats.map { FilmCatalog.formatName($0) }),
            slider("pref_film_sim_grain", L("develop.film.grain"), 0, 200, 1, "0"),
            slider("pref_film_sim_halation", L("develop.film.halation"), 0, 400, 1, "0"),
            slider("pref_film_sim_softness", L("develop.film.softness"), 0, 300, 1, "0"),
            slider("pref_film_sim_exposure", L("develop.film.exposure"), -2, 2, 0.1, "0"),
            slider("pref_film_sim_print_ev", L("develop.film.print_ev"), -1, 1, 0.05, "0"),
            slider("pref_film_sim_tone", L("develop.film.contrast"), -100, 100, 1, "0"),
            slider("pref_film_sim_print_m", L("develop.film.print_m"), -30, 30, 1, "0"),
            slider("pref_film_sim_print_y", L("develop.film.print_y"), -30, 30, 1, "0"),
            slider("pref_film_sim_strength", L("develop.film.strength"), 0, 100, 1, "100"),
            made("pref_film_sim_diffusion", L("develop.film.diffusion"), section, .list, def: "", isMerge, table, values: diffusions,
                 entries: diffusions.map { FilmCatalog.lensName($0) }),
            slider("pref_film_sim_diffusion_strength", L("develop.film.diffusion_strength"), 0, 200, 1, "50"),
            slider("pref_film_sim_dir", L("develop.film.dir"), 0, 100, 1, "0"),
        ]
    }

    /// Why a row never changes a develop, nil: it can.
    static func fixedReason(_ key: String, _ kind: Kind, _ isMerge: Bool, _ table: SettingsCatalog.DevelopTable) -> String? {
        if isMerge || table.mergeTuning.contains(key) { return L("develop.fixed.merge") }
        if table.atShot.contains(key) || table.burstFrames.contains(key) { return L("develop.fixed.at_shot") }
        if kind == .custom || kind == .text { return L("develop.fixed.own_page") }
        return nil
    }

    /// Why a row changes nothing with these values (the shot's with the changes; SettingsAvailability, as the settings grey a
    /// row), its fixed reason first; nil: it acts.
    static func inert(_ row: Row, _ values: [String: Any]) -> String? {
        row.fixed ?? SettingsAvailability(values).reason(row.key)
    }

    /// The row's value: the change, else the shot's stored value, else the default.
    static func value(_ row: Row, shot: [String: Any], changes: [String: DevValue]) -> Any {
        if let c = changes[row.key] { return c.json }
        return shot[row.key] ?? row.def
    }

    /// A switch's state of v (a Bool, "true" / "1").
    static func on(_ v: Any?) -> Bool {
        if PrefValue.isBool(v) { return (v as! NSNumber).boolValue }
        if let b = v as? Bool { return b }
        guard let v else { return false }
        let s = PrefValue.text(v)
        return s.lowercased() == "true" || s == "1"
    }

    /// The value's text as the rows show it ("as shot: …").
    static func shown(_ row: Row, _ v: Any) -> String {
        switch row.kind {
        case .toggle: return on(v) ? L("develop.on") : L("develop.off")
        case .list:
            let text = PrefValue.text(v)
            let values = row.node.inlineValues ?? SettingsCatalog.shared.array(row.node.values)
            let entries = row.node.inlineEntries ?? SettingsCatalog.shared.array(row.node.entries)
            if let i = values.firstIndex(of: text), i < entries.count { return entries[i] }
            return text
        default: return PrefValue.text(v)
        }
    }
}
