import SwiftUI
import UniformTypeIdentifiers

/// The film / LUT / lens-filter catalogues of the engine (SCAMFilm: FilmSim, FilmLooks, LensFilters - the Android tables) with
/// their interface names (FilmSim.typeName / formatName / formatHint, FilmLooks.label, LensFilters' tune controls).
enum FilmCatalog {
    struct Stock: Identifiable { let id, brand, name: String; let type, iso: Int; let reversal, measured: Bool }
    struct Print: Identifiable { let id, brand, name: String; let film: Bool }
    struct Format: Identifiable { let id, name: String; let longMm: Double }

    static let stocks: [Stock] = SCAMFilm.stocks().map {
        Stock(id: $0["id"] as? String ?? "", brand: $0["brand"] as? String ?? "", name: $0["name"] as? String ?? "",
              type: ($0["type"] as? NSNumber)?.intValue ?? 0, iso: ($0["iso"] as? NSNumber)?.intValue ?? 0,
              reversal: ($0["reversal"] as? NSNumber)?.boolValue ?? false, measured: ($0["measured"] as? NSNumber)?.boolValue ?? false)
    }
    static let prints: [Print] = SCAMFilm.prints().map {
        Print(id: $0["id"] as? String ?? "", brand: $0["brand"] as? String ?? "", name: $0["name"] as? String ?? "",
              film: ($0["film"] as? NSNumber)?.boolValue ?? false)
    }
    static let formats: [Format] = SCAMFilm.formats().map {
        Format(id: $0["id"] as? String ?? "", name: $0["name"] as? String ?? "", longMm: ($0["longMm"] as? NSNumber)?.doubleValue ?? 0)
    }

    static func stock(_ id: String) -> Stock? { stocks.first { $0.id == id } }

    /// FilmSim.typeName
    static func typeName(_ s: Stock) -> String {
        let key: String
        switch s.type {
        case 1: key = "film.type.cine"
        case 2: key = "film.type.slide"
        case 3: key = "film.type.bw_negative"
        case 4: key = "film.type.bw_reversal"
        default: key = "film.type.negative"
        }
        return L(key) + " · ISO \(s.iso)"
    }

    /// FilmSim.formatName / formatHint
    static func formatName(_ f: Format) -> String { f.longMm <= 0 ? L("film.format.off") : f.name }
    static func formatHint(_ f: Format) -> String {
        f.longMm <= 0 ? L("film.format.off_hint") : String(format: L("film.format.hint"), locale: Locale(identifier: "en_US_POSIX"), f.longMm)
    }

    /// FilmLooks.label, or the file's name without ".cube" (LutTray.name); "" is «None»
    static func lutName(_ value: String) -> String {
        if value.isEmpty { return L("film.none") }
        if value.hasPrefix("scam:") { return L("look." + value.dropFirst(5)) }
        return value.lowercased().hasSuffix(".cube") ? String(value.dropLast(5)) : value
    }

    /// The LUTs offered: «None», our five looks, the imported .cube files
    static func luts() -> [String] { [""] + SCAMFilm.looks() + SCAMFilm.lutFiles() }

    /// LensFilters.name ("" None)
    static func lensName(_ id: String) -> String { id.isEmpty ? L("film.none") : SCAMFilm.lensFilterName(id) }

    /// The .cube files are kept in Documents/LUT (the Files app shows it)
    static var lutFolder: URL { URL(fileURLWithPath: SCAMFilm.lutFolder()) }

    /// «Импорт .cube»: the file copied into the LUT folder when it is a 3D LUT FilmLut.parse reads; its stored name, nil otherwise
    static func importCube(_ url: URL) -> String? {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard url.pathExtension.lowercased() == "cube", let data = try? Data(contentsOf: url), SCAMFilm.validCube(data) else { return nil }
        let name = url.lastPathComponent
        guard !name.contains("/") else { return nil }
        let target = lutFolder.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: target)
        guard (try? data.write(to: target)) != nil else { return nil }
        SCAMFilm.setLutFolder(lutFolder.path) // the parsed file of that name is read again
        return name
    }
}

/// A choice row of the film pages: a title, a caption, a check mark when chosen
private struct ChoiceRow: View {
    let title: String
    var caption: String? = nil
    let on: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    if let caption, !caption.isEmpty { Text(caption).font(.app(12)).foregroundStyle(.secondary) }
                }
                Spacer()
                if on { Image(systemName: "checkmark").foregroundStyle(.tint) }
            }
        }
    }
}

/// A slider of the film pages stored as FilmSimPreference.slider stores it: two decimals below a step of 1 (x scale), else a whole
/// number (x scale)
private struct StoredSlider: View {
    let key: String
    let title: String
    let min: Double, max: Double, step: Double, def: Double
    var unit = ""
    var scale = 1.0
    private var store: SettingsStore { SettingsStore.shared }

    private var value: Double {
        let text = store.string(key, Self.text(def, step: step, scale: scale)).replacingOccurrences(of: ",", with: ".")
        return (Double(text) ?? def * scale) / scale
    }

    static func text(_ v: Double, step: Double, scale: Double) -> String {
        if step * scale < 1 { return String(format: step * scale < 0.0099 ? "%.3f" : "%.2f", locale: Locale(identifier: "en_US_POSIX"), v * scale) }
        return String(Int((v * scale).rounded(.toNearestOrAwayFromZero)))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text(display).font(.app(13).monospacedDigit()).foregroundStyle(.secondary)
            }
            Slider(value: Binding(get: { Swift.min(max, Swift.max(min, value)) },
                                  set: { store.set(key, Self.text(($0 / step).rounded() * step, step: step, scale: scale)) }),
                   in: min...max, step: step)
        }
    }

    private var display: String {
        let v = value
        if step < 1 { return String(format: step < 0.0099 ? "%.3f" : "%.2f", v) + unit }
        return String(Int(v.rounded(.toNearestOrAwayFromZero))) + unit
    }

    func reset() { store.set(key, Self.text(def, step: step, scale: scale)) }
}

/// FilmSimPreference: «Плёнка», «Печать», «Формат», «Эффекты» - the stock, the print, the format, the diffusion and the effects'
/// sliders under the Android keys (pref_film_sim_*), the values as the Android page stores them.
struct FilmSimPage: View {
    @State private var tab = 0
    @State private var sample = 0
    private var store: SettingsStore { SettingsStore.shared }
    private var stockId: String { store.string("pref_film_sim_stock", "") }

    /// P281: the before / after preview on the three samples - the stock through its first-phase table with its default print and
    /// no grain (EditLookThumbs' film picture), mixed by the film's strength
    @ViewBuilder private var preview: some View {
        let s = sample, stock = FilmCatalog.stock(stockId)?.id ?? ""
        let before = SampleCache.shared.get("lut|\(s)|480") { LutSamples.draw(s, 480, 360) }
        let after = stock.isEmpty ? before : SampleCache.shared.get("film|\(s)|480|" + stock) { LutSamples.look(LutSamples.draw(s, 480, 360), lut: "", film: stock) }
        let strength = PrefValue.number(store.string("pref_film_sim_strength", "100"), 100) / 100
        VStack(alignment: .leading, spacing: 12) {
            LutCompareView(before: before?.image, after: after.flatMap { a in before?.mixed(with: a, strength).image })
            SampleChips(names: (0..<3).map(LutSamples.name), selected: $sample)
        }
        .buttonStyle(.borderless)
    }

    var body: some View {
        List {
            Section { preview }
            Section {
                Picker("", selection: $tab) {
                    Text(L("film.tab.film")).tag(0)
                    Text(L("film.tab.print")).tag(1)
                    Text(L("film.tab.format")).tag(2)
                    Text(L("film.tab.effects")).tag(3)
                }
                .pickerStyle(.segmented)
            }
            switch tab {
            case 1: printTab
            case 2: formatTab
            case 3: effectsTab
            default: filmTab
            }
        }
        .navigationTitle(L("film.title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var filmTab: some View {
        Section {
            ChoiceRow(title: L("film.none"), caption: L("film.no_film"), on: FilmCatalog.stock(stockId) == nil) {
                store.set("pref_film_sim_stock", "")
            }
            ForEach(FilmCatalog.stocks) { s in
                ChoiceRow(title: s.brand + " " + s.name, caption: FilmCatalog.typeName(s), on: s.id == stockId) {
                    store.set("pref_film_sim_stock", s.id)
                }
            }
        }
    }

    private var printTab: some View {
        let chosen = SCAMFilm.print(stored: store.string("pref_film_sim_print", ""), stock: stockId)
        return Section {
            ForEach(FilmCatalog.prints) { p in
                ChoiceRow(title: p.brand + " " + p.name, caption: p.film ? L("film.print.film") : L("film.print.paper"), on: p.id == chosen) {
                    store.set("pref_film_sim_print", p.id)
                }
            }
        } footer: {
            if FilmCatalog.stock(stockId)?.reversal == true { Text(L("film.print.reversal_note")) }
        }
    }

    private var formatTab: some View {
        let chosen = SCAMFilm.format(stored: store.string("pref_film_sim_format", "135"))
        return Section {
            ForEach(FilmCatalog.formats) { f in
                ChoiceRow(title: FilmCatalog.formatName(f), caption: FilmCatalog.formatHint(f), on: f.id == chosen) {
                    store.set("pref_film_sim_format", f.id)
                }
            }
        }
    }

    private var sliders: [StoredSlider] {
        var s: [StoredSlider] = []
        let diffusion = SCAMFilm.diffusions().contains(store.string("pref_film_sim_diffusion", "")) ? store.string("pref_film_sim_diffusion", "") : ""
        if !diffusion.isEmpty {
            s.append(StoredSlider(key: "pref_film_sim_diffusion_strength", title: L("film.diffusion_strength"), min: 0, max: 2, step: 0.05, def: 0.5,
                                  scale: 100))
        }
        s.append(StoredSlider(key: "pref_film_sim_grain", title: L("film.grain"), min: 0, max: 200, step: 1, def: 0, unit: "%"))
        s.append(StoredSlider(key: "pref_film_sim_halation", title: L("film.halation"), min: 0, max: 400, step: 1, def: 0, unit: "%"))
        s.append(StoredSlider(key: "pref_film_sim_softness", title: L("film.softness"), min: 0, max: 300, step: 1, def: 0, unit: "%"))
        s.append(StoredSlider(key: "pref_film_sim_print_ev", title: L("film.print_ev"), min: -1, max: 1, step: 0.05, def: 0, unit: " EV"))
        s.append(StoredSlider(key: "pref_film_sim_tone", title: L("film.contrast"), min: -100, max: 100, step: 1, def: 0))
        s.append(StoredSlider(key: "pref_film_sim_print_m", title: L("film.print_m"), min: -30, max: 30, step: 1, def: 0, unit: " CC"))
        s.append(StoredSlider(key: "pref_film_sim_print_y", title: L("film.print_y"), min: -30, max: 30, step: 1, def: 0, unit: " CC"))
        s.append(StoredSlider(key: "pref_film_sim_strength", title: L("film.strength"), min: 0, max: 100, step: 1, def: 100, unit: "%"))
        return s
    }

    private var effectsTab: some View {
        let diffusion = store.string("pref_film_sim_diffusion", "")
        let list = sliders
        return Section {
            Picker(L("film.diffusion"), selection: Binding(get: { SCAMFilm.diffusions().contains(diffusion) ? diffusion : "" },
                                                           set: { store.set("pref_film_sim_diffusion", $0) })) {
                ForEach(SCAMFilm.diffusions(), id: \.self) { Text(FilmCatalog.lensName($0)).tag($0) }
            }
            ForEach(list.indices, id: \.self) { list[$0] }
            Button(L("film.reset_sliders")) { for s in list { s.reset() } }
        }
    }
}

/// FilmLooks.hint: a look's short hint under its card; «свой · .cube» for a file
enum LookHints {
    static func hint(_ value: String) -> String {
        if value.isEmpty { return L("film.hint.none") }
        if value.hasPrefix("scam:") { return L("film.hint." + value.dropFirst(5)) }
        return L("film.hint.cube")
    }
}

/// FilmLutPreference (P134 / SIMPLE_UI §4c «Плёночный LUT»), on its page as Android draws it: the before / after preview with a
/// draggable cut (the LUT at the chosen strength), the three samples, «Плёнка» - a strip of cards, each the sample through that LUT
/// with its name and hint, in the user's order (pref_film_lut_order, a card held and dragged) - and «Импорт .cube» (the file copied
/// into Documents/LUT). A choice is stored under pref_film_lut (its strength set to 100 when it was 0).
struct FilmLutCard: View {
    @State private var sample = 0
    @State private var importing = false
    @State private var failed = false
    @State private var files: [String] = FilmCatalog.luts()
    private var store: SettingsStore { SettingsStore.shared }
    private static let thumbW = 160, thumbH = 120, previewW = 480, previewH = 360

    private var strength: Double { PrefValue.number(store.string("pref_film_lut_strength", "100"), 100) / 100 }

    private func base(_ w: Int, _ h: Int) -> SamplePixels? {
        let s = sample
        return SampleCache.shared.get("lut|\(s)|\(w)") { LutSamples.draw(s, w, h) }
    }

    private func through(_ value: String, _ w: Int, _ h: Int) -> SamplePixels? {
        let s = sample
        return SampleCache.shared.get("lut|\(s)|\(w)|" + value) { LutSamples.look(LutSamples.draw(s, w, h), lut: value) }
    }

    var body: some View {
        let chosen = store.string("pref_film_lut", "")
        let ordered = ItemOrder.ordered(ItemOrder.lutKey, files)
        VStack(alignment: .leading, spacing: 12) {
            let before = base(Self.previewW, Self.previewH), after = through(chosen, Self.previewW, Self.previewH)
            LutCompareView(before: before?.image, after: after.flatMap { a in before?.mixed(with: a, strength).image })
            SampleChips(names: (0..<3).map(LutSamples.name), selected: $sample)
            Text(L("film.strip")).font(.app(12, 600)).foregroundStyle(.secondary).textCase(.uppercase)
            CardStrip(items: ordered, chosen: chosen, name: FilmCatalog.lutName, hint: LookHints.hint,
                      thumb: { through($0, Self.thumbW, Self.thumbH)?.image }, choose: choose,
                      move: { ItemOrder.move(ItemOrder.lutKey, ordered, from: $0, to: $1) })
            Text(L("film.lut.hint")).font(.app(12)).foregroundStyle(.secondary)
            Button(L("film.lut.import")) { importing = true }
        }
        .padding(.vertical, 6)
        .buttonStyle(.borderless)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            if case .success(let url) = result, let name = FilmCatalog.importCube(url) {
                SampleCache.shared.forget(containing: "|" + name)
                files = FilmCatalog.luts()
                choose(name)
            } else if case .success = result {
                failed = true
            }
        }
        .alert(L("film.lut.unreadable"), isPresented: $failed) { Button("OK", role: .cancel) {} }
        .onAppear { files = FilmCatalog.luts() }
    }

    private func choose(_ v: String) {
        store.set("pref_film_lut", v)
        let strength = Double(store.string("pref_film_lut_strength", "100").replacingOccurrences(of: ",", with: ".")) ?? 100
        if !v.isEmpty && strength <= 0 { store.set("pref_film_lut_strength", "100") }
    }
}

/// LensFilterPreference (P228 «Фильтр объектива»), on its page: the before / after preview of the chosen filter on the samples
/// («Огни», «Пейзаж», «Закат»; the filter's diffusion as the photo's model, DiffusionGauss), the strip of filters with live
/// thumbnails in the user's order (pref_lens_filter_order), «Сила» (pref_lens_filter_strength, 0..100, 50 when a filter is chosen
/// at 0) and the filter's fine controls (pref_lens_filter_tune_<id>: LensFilters.Tune's text).
struct LensFilterCard: View {
    @State private var sample = 0
    private var store: SettingsStore { SettingsStore.shared }
    private static let thumbW = 160, thumbH = 120, previewW = 400, previewH = 300

    private static func picture(_ s: Int, _ w: Int, _ h: Int) -> SamplePixels {
        s == 0 ? LutSamples.lights(w, h) : LutSamples.draw(s == 1 ? 0 : 1, w, h)
    }

    private func base(_ w: Int, _ h: Int) -> SamplePixels? {
        let s = sample
        return SampleCache.shared.get("lens|\(s)|\(w)") { Self.picture(s, w, h) }
    }

    private func strength() -> Double {
        Double(store.string("pref_lens_filter_strength", "50").replacingOccurrences(of: ",", with: ".")) ?? 50
    }

    private func storedTune(_ id: String) -> String? {
        let key = "pref_lens_filter_tune_" + id
        return store.values[key] == nil ? nil : store.string(key, "")
    }

    private func through(_ id: String, _ w: Int, _ h: Int, strength: Double) -> SamplePixels? {
        guard !id.isEmpty else { return base(w, h) }
        let tune = storedTune(id), s = sample
        return SampleCache.shared.get("lens|\(s)|\(w)|\(id)|\(strength)|\(tune ?? "")") {
            LutSamples.lens(Self.picture(s, w, h), filter: id, strength: strength / 100, tune: tune)
        }
    }

    var body: some View {
        let stored = store.string("pref_lens_filter", "")
        let chosen = SCAMFilm.lensFilters().contains(stored) ? stored : ""
        let ordered = ItemOrder.ordered(ItemOrder.filterKey, [""] + SCAMFilm.lensFilters())
        VStack(alignment: .leading, spacing: 12) {
            LutCompareView(before: base(Self.previewW, Self.previewH)?.image,
                           after: through(chosen, Self.previewW, Self.previewH, strength: strength())?.image)
            SampleChips(names: (0..<3).map(LutSamples.lensName), selected: $sample)
            CardStrip(items: ordered, chosen: chosen, name: FilmCatalog.lensName,
                      hint: { $0.isEmpty ? L("lens.no_filter") : L("lens.hint." + $0) },
                      thumb: { through($0, Self.thumbW, Self.thumbH, strength: $0.isEmpty ? 0 : max(strength(), 50))?.image },
                      choose: choose, move: { ItemOrder.move(ItemOrder.filterKey, ordered, from: $0, to: $1) })
            if !chosen.isEmpty { controls(chosen) }
        }
        .padding(.vertical, 6)
        .buttonStyle(.borderless)
    }

    private func choose(_ id: String) {
        store.set("pref_lens_filter", id)
        if !id.isEmpty && strength() <= 0 { store.set("pref_lens_filter_strength", "50") }
    }

    private func tune(_ id: String) -> [Double] { SCAMFilm.lensTune(storedTune(id), filter: id).map { $0.doubleValue } }

    private func setTune(_ id: String, _ index: Int, _ v: Double) {
        var t = tune(id)
        t[index] = v
        store.set("pref_lens_filter_tune_" + id, SCAMFilm.lensTuneText(t.map { NSNumber(value: $0) }))
    }

    @ViewBuilder private func controls(_ id: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack { Text(L("lens.strength")); Spacer(); Text("\(Int(strength().rounded()))").font(.app(13).monospacedDigit()).foregroundStyle(.secondary) }
            Slider(value: Binding(get: { Swift.min(100, Swift.max(0, strength())) },
                                  set: { store.set("pref_lens_filter_strength", String(Int($0.rounded()))) }), in: 0...100, step: 1)
        }
        DisclosureGroup(L("lens.fine")) {
            let t = tune(id)
            ForEach(1..<5, id: \.self) { i in
                VStack(alignment: .leading, spacing: 4) {
                    HStack { Text(L(["", "lens.halo", "lens.radius", "lens.haze", "lens.tone"][i])); Spacer()
                        Text("\(Int((t[i] * 100).rounded()))").font(.app(13).monospacedDigit()).foregroundStyle(.secondary) }
                    Slider(value: Binding(get: { tune(id)[i] * 100 }, set: { setTune(id, i, $0.rounded() / 100) }), in: 0...100, step: 1)
                    Text(L(["", "lens.halo_hint", "lens.radius_hint", "lens.haze_hint", "lens.tone_hint"][i])).font(.app(12)).foregroundStyle(.secondary)
                }
            }
            Button(L("lens.reset")) {
                var d = SCAMFilm.lensTune(nil, filter: id).map { $0.doubleValue }
                d[0] = tune(id)[0]
                store.set("pref_lens_filter_tune_" + id, SCAMFilm.lensTuneText(d.map { NSNumber(value: $0) }))
            }
        }
    }
}

