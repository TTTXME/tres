import SwiftUI
import UIKit

/// LutTray (SIMPLE_UI §1, P228 / P281 / P319 / P381 / P382 / P509 / P574): the LUT and film tray in the Pro panel's place (they close
/// each other), opened by the top row's LUT button. Its sections are tabs of one segmented control - «LUT · Фильтр · Плёнка · Цвет»
/// (the last one shown is kept while the app runs; a section that is on carries an accent dot; a double tap on a tab's title puts
/// that tab's settings back to their defaults, the hint line's tap undoes it) - and «Все ›» opens the shown section's settings page.
/// LUT: a row of cards with a still thumbnail (the settings' drawn landscape through the look) and the name, the strength slider.
/// Фильтр / Плёнка: the cards and the chosen filter's / film's adjustments (TrayAdjust: parameter chips and one slider, or the
/// choice's options). Цвет: «Тепло» and «Оттенок» (-100..100). Everything is the settings' own keys (pref_film_lut*,
/// pref_lens_filter*, pref_film_sim_*, pref_scam_hybrid_colour_*): the live viewfinder and the photo follow it.
@MainActor
@Observable
final class LutTrayModel {
    static let shared = LutTrayModel()
    static let tabIds = ["lut", "filter", "film", "colour"]
    var open = false
    /// The tab shown last (kept while the app runs)
    var tab = 0
    /// The hint line: its title, its text, what a tap on it does besides closing it
    private(set) var hint: (title: String, text: String)?
    @ObservationIgnored private(set) var hintAction: (() -> Void)?
    @ObservationIgnored private var hintTimer: Task<Void, Never>?
    /// Bumped on every write, so the views read the settings again
    private(set) var revision = 0

    private var store: SettingsStore { SettingsStore.shared }

    func touch() { revision += 1 }

    func showHint(_ title: String, _ text: String, seconds: Double = 8, action: (() -> Void)? = nil) {
        hintAction = action
        withAnimation(.easeOut(duration: 0.2)) { hint = (title, text) }
        hintTimer?.cancel()
        hintTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
            guard !Task.isCancelled else { return }
            self?.hideHint()
        }
    }

    func hideHint() {
        hintTimer?.cancel()
        hintAction = nil
        withAnimation(.easeOut(duration: 0.2)) { hint = nil }
    }

    func tapHint() {
        let action = hintAction
        hintAction = nil
        action?()
        hideHint()
    }

    // MARK: the state

    var lut: String { store.string("pref_film_lut", "") }
    var lutStrength: Double { Self.number(store.string("pref_film_lut_strength", "100"), 100) }
    var filter: String {
        let f = store.string("pref_lens_filter", "")
        return SCAMFilm.lensFilters().contains(f) ? f : ""
    }
    var filterStrength: Double { Self.number(store.string("pref_lens_filter_strength", "50"), 50) }
    var film: String { FilmCatalog.stock(store.string("pref_film_sim_stock", "")) == nil ? "" : store.string("pref_film_sim_stock", "") }
    func colour(_ key: String) -> Int { Int(max(-100, min(100, Self.number(store.string(key, "0"), 0))).rounded()) }

    static let warmthKey = "pref_scam_hybrid_colour_warmth", tintKey = "pref_scam_hybrid_colour_tint"

    /// The sections that are on: a LUT, a filter, a film, a colour shift (the tabs' dots, the button's accent)
    var tabsOn: [Bool] {
        _ = revision
        return [!lut.isEmpty && lutStrength > 0, !filter.isEmpty && filterStrength > 0, !film.isEmpty,
                colour(Self.warmthKey) != 0 || colour(Self.tintKey) != 0]
    }
    var anyOn: Bool { tabsOn.contains(true) }

    static func number(_ text: String, _ fallback: Double) -> Double {
        Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")) ?? fallback
    }

    func set(_ key: String, _ value: String?) {
        store.set(key, value)
        touch()
    }

    func chooseLut(_ v: String) {
        set("pref_film_lut", v)
        if !v.isEmpty && lutStrength <= 0 { set("pref_film_lut_strength", "100") }
    }

    func chooseFilter(_ id: String) {
        set("pref_lens_filter", id)
        if !id.isEmpty && filterStrength <= 0 { set("pref_lens_filter_strength", "50") }
    }

    func chooseFilm(_ id: String) { set("pref_film_sim_stock", id) }

    /// The swipe on the viewfinder: the previous (-1) or next (+1) LUT; returns the message («LUT: Кино 500»)
    func step(_ direction: Int) -> String {
        let values = FilmCatalog.luts()
        let n = values.count
        guard n > 0 else { return "" }
        let i = values.firstIndex(of: lut) ?? 0
        let next = values[((i + direction % n) + n) % n]
        chooseLut(next)
        return "LUT: " + FilmCatalog.lutName(next)
    }

    // MARK: P382: a tab's reset and its undo

    static let filmKeys = ["pref_film_sim_stock", "pref_film_sim_print", "pref_film_sim_format", "pref_film_sim_grain", "pref_film_sim_halation",
                           "pref_film_sim_softness", "pref_film_sim_exposure", "pref_film_sim_print_ev", "pref_film_sim_tone", "pref_film_sim_print_m",
                           "pref_film_sim_print_y", "pref_film_sim_strength", "pref_film_sim_diffusion", "pref_film_sim_diffusion_strength",
                           "pref_film_sim_dir"]

    func owned(_ t: Int) -> [String] {
        switch t {
        case 0: return ["pref_film_lut", "pref_film_lut_strength"]
        case 1: return ["pref_lens_filter", "pref_lens_filter_strength"] + store.values.keys.filter { $0.hasPrefix("pref_lens_filter_tune_") }
        case 2: return Self.filmKeys
        default: return [Self.warmthKey, Self.tintKey]
        }
    }

    func resetTab(_ t: Int, title: String) {
        let keys = owned(t)
        var before: [String: Any] = [:]
        for k in keys { if let v = store.values[k] { before[k] = v } }
        let changed = tabsOn[t] || (t == 0 && abs(lutStrength - 100) > 0.5)
        switch t {
        case 0:
            store.set("pref_film_lut", "")
            store.set("pref_film_lut_strength", "100")
        case 3:
            store.set(Self.warmthKey, "0")
            store.set(Self.tintKey, "0")
        default:
            for k in keys { store.set(k, nil) }
        }
        touch()
        SessionLog.i("LutTray", "tab \(Self.tabIds[t]) reset to defaults" + (changed ? "" : " (it was)"))
        if changed {
            showHint(title, L("lut.reset_undo"), seconds: 5) { [weak self] in
                guard let self else { return }
                for k in keys { self.store.set(k, before[k]) }
                self.touch()
                SessionLog.i("LutTray", "tab \(Self.tabIds[t]) reset undone")
            }
        } else {
            showHint(title, L("lut.reset_none"), seconds: 2.5)
        }
    }

    /// What a setting does: its row's description in the settings (ParamHelp's role), nil when it has none
    static func help(_ key: String) -> String? {
        func find(_ n: SettingsNode) -> SettingsNode? {
            if n.key == key { return n }
            for c in n.children ?? [] { if let f = find(c) { return f } }
            return nil
        }
        guard let tree = SettingsCatalog.shared.tree, let node = find(tree) else { return nil }
        return SettingsCatalog.shared.text(node.summary)
    }
}

/// The still thumbnails of the tray's cards (LookThumbs): the settings' drawn landscape (SampleScene) through a LUT or a film stock's
/// table at full strength, 112 px, made once per item off the main thread and kept in memory; never from the camera.
@MainActor
final class LookThumbs {
    static let shared = LookThumbs()
    nonisolated static let size = 112
    private var cache: [String: CGImage] = [:]
    private var pending: Set<String> = []
    private var base: Data?
    var revision = 0
    var onReady: (() -> Void)?

    func image(lut: String = "", film: String = "") -> CGImage? {
        let key = lut + "|" + film
        if let c = cache[key] { return c }
        guard !pending.contains(key) else { return nil }
        pending.insert(key)
        let base = self.base ?? Self.scene()
        self.base = base
        let s = Self.size
        Task.detached(priority: .utility) {
            let out = (lut.isEmpty && film.isEmpty) ? base : (SCAMFilm.lookThumbnail(base, width: s, height: s, lut: lut, film: film) ?? base)
            let image = Self.cgImage(out, s)
            await MainActor.run {
                self.pending.remove(key)
                if let image { self.cache[key] = image }
                self.revision += 1
                self.onReady?()
            }
        }
        return nil
    }

    nonisolated private static func scene() -> Data {
        let s = size
        var bytes = [UInt8](repeating: 255, count: s * s * 4)
        bytes.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: s, height: s, bitsPerComponent: 8, bytesPerRow: s * 4,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
            ctx.translateBy(x: 0, y: CGFloat(s))
            ctx.scaleBy(x: 1, y: -1)
            SampleScene.draw(ctx, w: Float(s), h: Float(s), seed: 0, zoom: 1)
        }
        return Data(bytes)
    }

    nonisolated private static func cgImage(_ rgba: Data, _ s: Int) -> CGImage? {
        guard rgba.count >= s * s * 4, let provider = CGDataProvider(data: rgba as CFData) else { return nil }
        return CGImage(width: s, height: s, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: s * 4,
                       space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}

/// One adjustment of the tray (TrayAdjust.Param): a slider on the settings' range, step and default, or a choice of options
struct TrayParam: Identifiable {
    let id: String
    let title: String
    let key: String
    var min = 0.0, max = 100.0, step = 1.0, def = 0.0
    var unit = ""
    var options: [String] = []
    var read: () -> Double
    var write: (Double) -> Void

    /// TrayAdjust.Param.text: «+0.4 EV», «120 %», «-15»
    func text(_ v: Double) -> String {
        if !options.isEmpty { return options[Swift.max(0, Swift.min(options.count - 1, Int(v.rounded())))] }
        if step < 1 {
            let f = step < 0.1 ? (min < 0 ? "%+.2f" : "%.2f") : (min < 0 ? "%+.1f" : "%.1f")
            return CameraStyle.decimal(String(format: f, v)) + unit
        }
        let i = Int(v.rounded())
        return (min < 0 && i > 0 ? "+" : "") + "\(i)" + unit
    }
}

struct LutTray: View {
    let model = LutTrayModel.shared
    var onAll: (Int) -> Void
    @State private var thumbs = 0
    @State private var chosenParam: [String: String] = [:]

    var body: some View {
        let _ = model.revision
        let titles = ["LUT", L("lut.tab_filter"), L("lut.tab_film"), L("lut.tab_colour")]
        VStack(spacing: 10) {
            HStack(spacing: 6) {
                CameraSegmented(labels: titles, selected: model.tab, dots: model.tabsOn, onPick: { t in
                    model.hideHint()
                    model.tab = t
                }, onDoubleTap: { t in
                    if t != model.tab { model.tab = t } else { Haptics.tick(); model.resetTab(t, title: titles[t]) }
                })
                Button { onAll(model.tab) } label: {
                    HStack(spacing: 2) {
                        Text(L("lut.all")).font(.app(14, 600))
                        Image("cv_chev_right").renderingMode(.template).resizable().frame(width: 14, height: 14)
                    }
                    .foregroundStyle(CameraStyle.accent)
                    .padding(.leading, 8).padding(.trailing, 6).frame(minHeight: 30)
                }
                .opacity(model.tab == 3 ? 0 : 1)
                .disabled(model.tab == 3)
            }
            Group {
                switch model.tab {
                case 1: filterPage
                case 2: filmPage
                case 3: colourPage
                default: lutPage
                }
            }
            .transition(.opacity)
            if let hint = model.hint {
                (Text(hint.title).bold().foregroundColor(CameraStyle.text) + Text("  " + hint.text).foregroundColor(CameraStyle.muted))
                    .font(.app(12.5)).lineLimit(6).lineSpacing(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12).padding(.top, 9).padding(.bottom, 10)
                    .background(Color.white.opacity(0.09), in: RoundedRectangle(cornerRadius: 14))
                    .onTapGesture { model.tapHint() }
                    .transition(.opacity)
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 24).fill(CameraStyle.tray))
        .animation(CameraMotion.glide, value: model.tab)
        .animation(.easeOut(duration: 0.2), value: model.hint?.text)
        .onAppear { LookThumbs.shared.onReady = { thumbs += 1 } }
    }

    // MARK: the pages

    private var lutPage: some View {
        let values = FilmCatalog.luts()
        return VStack(spacing: 4) {
            cardRow(values, chosen: model.lut, name: { FilmCatalog.lutName($0) }, image: { LookThumbs.shared.image(lut: $0) }) {
                model.chooseLut($0)
            }
            sliderRow(TrayParam(id: "strength", title: L("lut.strength"), key: "pref_film_lut_strength", min: 0, max: 100, step: 1, def: 100,
                                unit: " %", read: { model.lutStrength }, write: { model.set("pref_film_lut_strength", String(Int($0.rounded()))) }))
                .opacity(model.lut.isEmpty ? 0.4 : 1)
        }
    }

    private var filterPage: some View {
        let ids = [""] + SCAMFilm.lensFilters()
        let f = model.filter
        return VStack(spacing: 0) {
            cardRow(ids, chosen: f, name: { $0.isEmpty ? L("lut.no_filter") : FilmCatalog.lensName($0) }, image: { _ in LookThumbs.shared.image() }) {
                model.chooseFilter($0)
            }
            if !f.isEmpty { TrayAdjust(params: filterParams(f), chosen: binding("filter")) }
        }
    }

    private var filmPage: some View {
        let ids = [""] + FilmCatalog.stocks.map(\.id)
        let f = model.film
        return VStack(spacing: 0) {
            cardRow(ids, chosen: f, name: { FilmCatalog.stock($0)?.name ?? L("lut.no_film") }, image: { LookThumbs.shared.image(film: $0) }) {
                model.chooseFilm($0)
            }
            if !f.isEmpty { TrayAdjust(params: filmParams(FilmCatalog.stock(f)), chosen: binding("film")) }
        }
    }

    private var colourPage: some View {
        VStack(spacing: 0) {
            sliderRow(colourParam(L("lut.warmth"), LutTrayModel.warmthKey),
                      track: [Color(red: 0x5A / 255, green: 0xA0 / 255, blue: 1), Color(red: 0xB9 / 255, green: 0xC2 / 255, blue: 0xCC / 255),
                              Color(red: 1, green: 0xB0 / 255, blue: 0x4A / 255)])
            sliderRow(colourParam(L("lut.tint"), LutTrayModel.tintKey),
                      track: [Color(red: 0x57 / 255, green: 0xD0 / 255, blue: 0x7A / 255), Color(red: 0xB9 / 255, green: 0xC2 / 255, blue: 0xCC / 255),
                              Color(red: 0xE4 / 255, green: 0x6A / 255, blue: 0xD6 / 255)])
        }
    }

    private func binding(_ section: String) -> Binding<String?> {
        Binding(get: { chosenParam[section] }, set: { chosenParam[section] = $0 })
    }

    private func colourParam(_ title: String, _ key: String) -> TrayParam {
        TrayParam(id: key, title: title, key: key, min: -100, max: 100, step: 1, def: 0,
                  read: { Double(model.colour(key)) }, write: { model.set(key, String(Int($0.rounded()))) })
    }

    /// A stored number of the film pages (FilmSimPreference.slider: two decimals below a step of 1 times the scale, else a whole
    /// number times the scale)
    private func stored(_ id: String, _ title: String, _ key: String, _ min: Double, _ max: Double, _ step: Double, _ def: Double,
                        _ unit: String, scale: Double = 1) -> TrayParam {
        TrayParam(id: id, title: title, key: key, min: min, max: max, step: step, def: def, unit: unit,
                  read: { LutTrayModel.number(SettingsStore.shared.string(key, ""), def * scale) / scale },
                  write: { v in
                      let s = step * scale < 1 ? String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), v * scale)
                          : String(Int((v * scale).rounded()))
                      model.set(key, s)
                  })
    }

    /// The lens filter's adjustments as its settings page has them: the strength (0..100, 50) and its fine controls (Tune 1..4)
    private func filterParams(_ id: String) -> [TrayParam] {
        var out = [TrayParam(id: "strength", title: L("lut.strength"), key: "pref_lens_filter_strength", min: 0, max: 100, step: 1, def: 50,
                             unit: " %", read: { model.filterStrength }, write: { model.set("pref_lens_filter_strength", String(Int($0.rounded()))) })]
        let tuneKey = "pref_lens_filter_tune_" + id
        let names = ["", L("lens.halo"), L("lens.radius"), L("lens.haze"), L("lens.tone")]
        let defaults = SCAMFilm.lensTune(nil, filter: id).map { $0.doubleValue }
        for i in 1..<5 where i < defaults.count {
            out.append(TrayParam(id: "tune\(i)", title: names[i], key: tuneKey, min: 0, max: 100, step: 1, def: (defaults[i] * 100).rounded(),
                                 read: {
                                     let stored = SettingsStore.shared.values[tuneKey] == nil ? nil : SettingsStore.shared.string(tuneKey, "")
                                     let t = SCAMFilm.lensTune(stored, filter: id).map { $0.doubleValue }
                                     return i < t.count ? t[i] * 100 : 50
                                 },
                                 write: { v in
                                     let stored = SettingsStore.shared.values[tuneKey] == nil ? nil : SettingsStore.shared.string(tuneKey, "")
                                     var t = SCAMFilm.lensTune(stored, filter: id).map { $0.doubleValue }
                                     guard i < t.count else { return }
                                     t[i] = v.rounded() / 100
                                     model.set(tuneKey, SCAMFilm.lensTuneText(t.map { NSNumber(value: $0) }))
                                 }))
        }
        return out
    }

    /// LutTray.filmParams: the strength, the print and the format (choices), the diffusion and its strength, grain, halation,
    /// softness, the exposure, the contrast and the print's exposure (only what acts on this stock)
    private func filmParams(_ st: FilmCatalog.Stock?) -> [TrayParam] {
        var out = [stored("strength", L("lut.strength"), "pref_film_sim_strength", 0, 100, 1, 100, " %")]
        let store = SettingsStore.shared
        if !(st?.reversal ?? false) {
            let prints = FilmCatalog.prints
            out.append(TrayParam(id: "print", title: L("film.tab.print"), key: "pref_film_sim_print", options: prints.map(\.name),
                                 read: {
                                     let id = SCAMFilm.print(stored: store.string("pref_film_sim_print", ""), stock: store.string("pref_film_sim_stock", ""))
                                     return Double(prints.firstIndex { $0.id == id } ?? 0)
                                 },
                                 write: { v in
                                     let i = Int(v.rounded())
                                     if i >= 0 && i < prints.count { model.set("pref_film_sim_print", prints[i].id) }
                                 }))
        }
        let formats = FilmCatalog.formats
        out.append(TrayParam(id: "format", title: L("film.tab.format"), key: "pref_film_sim_format", options: formats.map { FilmCatalog.formatName($0) },
                             read: {
                                 let id = SCAMFilm.format(stored: store.string("pref_film_sim_format", "135"))
                                 return Double(formats.firstIndex { $0.id == id } ?? 0)
                             },
                             write: { v in
                                 let i = Int(v.rounded())
                                 if i >= 0 && i < formats.count { model.set("pref_film_sim_format", formats[i].id) }
                             }))
        let diffusions = SCAMFilm.diffusions()
        out.append(TrayParam(id: "diffusion", title: L("film.diffusion"), key: "pref_film_sim_diffusion", options: diffusions.map { FilmCatalog.lensName($0) },
                             read: { Double(diffusions.firstIndex(of: store.string("pref_film_sim_diffusion", "")) ?? 0) },
                             write: { v in
                                 let i = Int(v.rounded())
                                 if i >= 0 && i < diffusions.count { model.set("pref_film_sim_diffusion", diffusions[i]) }
                             }))
        out.append(stored("diffusion_strength", L("film.diffusion_strength"), "pref_film_sim_diffusion_strength", 0, 2, 0.05, 0.5, "", scale: 100))
        out.append(stored("grain", L("film.grain"), "pref_film_sim_grain", 0, 200, 1, 0, " %"))
        out.append(stored("halation", L("film.halation"), "pref_film_sim_halation", 0, 400, 1, 0, " %"))
        out.append(stored("softness", L("film.softness"), "pref_film_sim_softness", 0, 300, 1, 0, " %"))
        out.append(stored("exposure", L("lut.exposure"), "pref_film_sim_exposure", -2, 2, 0.1, 0, " EV"))
        out.append(stored("contrast", L("film.contrast"), "pref_film_sim_tone", -100, 100, 1, 0, ""))
        if st == nil || (st!.type != 2 && st!.type != 4) {
            out.append(stored("print_ev", L("film.print_ev"), "pref_film_sim_print_ev", -1, 1, 0.05, 0, " EV"))
        }
        return out
    }

    // MARK: the parts

    /// A section's row of cards: a 53 pt still thumbnail (14 pt corners) with the name under it; the chosen card on #3A3A3C with a
    /// 1.5 pt accent edge and its name in the accent; the row scrolls to the chosen card
    private func cardRow(_ values: [String], chosen: String, name: @escaping (String) -> String, image: @escaping (String) -> CGImage?,
                         pick: @escaping (String) -> Void) -> some View {
        let _ = thumbs
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(values, id: \.self) { v in
                        let on = v == chosen
                        VStack(spacing: 5) {
                            Group {
                                if let img = image(v) {
                                    Image(decorative: img, scale: 1).resizable().scaledToFill()
                                } else {
                                    Color.white.opacity(0.09)
                                }
                            }
                            .frame(width: 53, height: 53)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                            Text(name(v)).font(.app(11, 600)).lineLimit(1).truncationMode(.tail)
                                .foregroundStyle(on ? CameraStyle.accent : CameraStyle.text)
                                .frame(width: 53)
                        }
                        .padding(.horizontal, 4).padding(.top, 4).padding(.bottom, 6)
                        .background(RoundedRectangle(cornerRadius: 18).fill(on ? CameraStyle.chosen : Color.clear))
                        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(on ? CameraStyle.accent : Color.clear, lineWidth: 1.5))
                        .contentShape(Rectangle())
                        .onTapGesture {
                            Haptics.tick()
                            withAnimation(CameraMotion.glide) { pick(v) }
                        }
                        .id(v)
                        .accessibilityLabel(name(v))
                        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
                    }
                }
            }
            .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.05),
                                         .init(color: .black, location: 0.95), .init(color: .clear, location: 1)],
                                 startPoint: .leading, endPoint: .trailing))
            .onAppear { proxy.scrollTo(chosen, anchor: .center) }
            .onChange(of: chosen) { withAnimation { proxy.scrollTo(chosen, anchor: .center) } }
        }
        .padding(.top, 4)
    }

    /// «label · slider · value»: the name 13 pt muted in a fixed column, the slider, the value 13 pt semibold in the accent
    private func sliderRow(_ p: TrayParam, track: [Color]? = nil) -> some View {
        HStack(spacing: 0) {
            Text(p.title).font(.app(13)).foregroundStyle(Color.white.opacity(0.75)).lineLimit(1)
                .frame(minWidth: 86, maxWidth: 100, alignment: .leading).padding(.leading, 4).padding(.trailing, 10)
                .onLongPressGesture(minimumDuration: 0.5) {
                    if let h = LutTrayModel.help(p.key) { model.showHint(p.title, h) }
                }
            TraySlider(param: p, track: track)
            Text(p.text(p.read())).font(.app(13, 600)).monospacedDigit().foregroundStyle(CameraStyle.accent)
                .frame(minWidth: 58, alignment: .trailing)
        }
    }
}

/// TrayAdjust: a row of small parameter chips and one slider (or a choice's options) for the chosen parameter
struct TrayAdjust: View {
    let params: [TrayParam]
    @Binding var chosen: String?
    private var model: LutTrayModel { LutTrayModel.shared }

    var body: some View {
        let now = params.first { $0.id == chosen } ?? params.first
        VStack(spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(params) { p in
                        let on = p.id == now?.id
                        Text(p.title).font(.app(12, on ? 600 : 400)).lineLimit(1)
                            .foregroundStyle(on ? CameraStyle.text : CameraStyle.muted)
                            .padding(.horizontal, 10).frame(height: 28)
                            .background(on ? Color.white.opacity(0.18) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
                            .contentShape(Rectangle())
                            .onTapGesture { Haptics.tick(); withAnimation(CameraMotion.glide) { chosen = p.id } }
                            .onLongPressGesture(minimumDuration: 0.5) {
                                if let h = LutTrayModel.help(p.key) { model.showHint(p.title, h) }
                            }
                    }
                }
            }
            .padding(.top, 6)
            if let p = now {
                if p.options.isEmpty {
                    HStack(spacing: 8) {
                        TraySlider(param: p, track: nil)
                        Text(p.text(p.read())).font(.app(13, 600)).monospacedDigit().foregroundStyle(CameraStyle.accent)
                            .frame(minWidth: 58, alignment: .trailing)
                    }
                    .frame(height: 34)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            let sel = Int(p.read().rounded())
                            ForEach(p.options.indices, id: \.self) { i in
                                Text(p.options[i]).font(.app(12, i == sel ? 600 : 400)).lineLimit(1)
                                    .foregroundStyle(i == sel ? CameraStyle.accent : CameraStyle.text)
                                    .padding(.horizontal, 10).frame(height: 28)
                                    .background(i == sel ? CameraStyle.chosen : Color.white.opacity(0.09), in: RoundedRectangle(cornerRadius: 12))
                                    .onTapGesture { Haptics.tick(); p.write(Double(i)) }
                            }
                        }
                    }
                    .frame(height: 34)
                }
            }
        }
    }
}

/// The camera screen's slider (IosSlider): a 4 pt track (or the colour gradient), the accent fill from the default, a 22 pt knob;
/// steps of the parameter, a tick per step; a double tap puts the default back
struct TraySlider: View {
    let param: TrayParam
    let track: [Color]?
    @State private var dragging = false

    var body: some View {
        GeometryReader { g in
            let w = g.size.width, knob: CGFloat = 22
            let v = min(max(param.read(), param.min), param.max)
            let t = CGFloat((v - param.min) / max(1e-9, param.max - param.min))
            let d = CGFloat((min(max(param.def, param.min), param.max) - param.min) / max(1e-9, param.max - param.min))
            let x = knob / 2 + t * (w - knob)
            let x0 = knob / 2 + d * (w - knob)
            ZStack(alignment: .leading) {
                if let track {
                    Capsule().fill(LinearGradient(colors: track, startPoint: .leading, endPoint: .trailing)).frame(height: 4)
                } else {
                    Capsule().fill(Color.white.opacity(0.18)).frame(height: 4)
                    Capsule().fill(CameraStyle.accent).frame(width: abs(x - x0), height: 4).offset(x: min(x, x0))
                }
                Circle().fill(Color.white).frame(width: knob, height: knob)
                    .shadow(color: .black.opacity(0.3), radius: 3, y: 1)
                    .scaleEffect(dragging ? 1.15 : 1)
                    .offset(x: x - knob / 2)
            }
            .frame(width: w, height: g.size.height)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { gesture in
                    dragging = true
                    let p = min(max((gesture.location.x - knob / 2) / max(1, w - knob), 0), 1)
                    var nv = param.min + Double(p) * (param.max - param.min)
                    nv = (nv / param.step).rounded() * param.step
                    if abs(nv - v) >= param.step / 2 {
                        Haptics.tick()
                        param.write(nv)
                    }
                }
                .onEnded { _ in dragging = false })
            .simultaneousGesture(TapGesture(count: 2).onEnded { Haptics.soft(); param.write(param.def) })
        }
        .frame(height: 34)
        .animation(.easeOut(duration: 0.12), value: dragging)
        .accessibilityElement()
        .accessibilityLabel(param.title)
        .accessibilityValue(param.text(param.read()))
        .accessibilityAdjustableAction { dir in
            let v = param.read()
            switch dir {
            case .increment: param.write(min(param.max, v + param.step))
            case .decrement: param.write(max(param.min, v - param.step))
            @unknown default: break
            }
        }
    }
}
