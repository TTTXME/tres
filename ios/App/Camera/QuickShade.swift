import SwiftUI
import UniformTypeIdentifiers

/// One setting a tile or a row of the quick-settings shade shows (ShadeCatalog.Entry): a camera control with no settings row
/// (flash, self-timer, the file format, the photo size, the selfie mirror) or a row of the settings tree (switch, list, slider),
/// with the shade's short name and short value labels where the Android catalogue has them (shade_t_*, shade_s_*).
struct ShadeEntry {
    enum Kind { case toggle, list, slider, format, size }
    let key: String
    let kind: Kind
    let title: String
    let longTitle: String
    let icon: String
    var values: [String] = []
    var labels: [String] = []
    var longLabels: [String] = []
    var def = ""
    var node: SettingsNode?
}

@MainActor
@Observable
final class ShadeModel {
    static let shared = ShadeModel()
    enum Level: Int { case hidden, peek, full }
    static let key = "ui_shade_tiles"
    static let flash = FlashMode.key, timer = "pref_countdown_timer_key", format = "pref_save_raw_key", size = "ui_photo_size",
               mirror = "pref_tunable_parameters_disablemirror"
    /// SIMPLE_UI §2, the concept's default pins, in its order (the iPhone has no «Стиль» tile: PhotoStyle is the settings' page)
    static let defaults = [flash, timer, "pref_show_grid_key", format, "pref_scam_hybrid_output", "pref_hdr_frames", "pref_ultrahdr_key",
                           "pref_horizon", "pref_motion_mode", "pref_camera_sounds_key", "pref_face_detect_mode", "pref_antibanding_hz_key",
                           mirror, "pref_wide169_key", "pref_tracking_af_mode", "pref_peak_method_key", "pref_scam_hybrid_bento",
                           "pref_show_afdata_key"]
    /// The FULL level's groups (ShadeCatalog.GROUPS)
    static let groups: [(String, [String])] = [
        ("a.shade_group_shoot", [flash, timer, "pref_scam_hybrid_why_metering", "pref_ettr", "pref_portrait_enabled",
                                 "pref_scam_hybrid_why_face_mask_on"]),
        ("a.shade_group_format", [format, "pref_scam_hybrid_output", "pref_scam_hybrid_resize", "pref_scam_hybrid_downsampler",
                                  "pref_ultrahdr_key", "pref_wide169_key", "pref_show_watermark_key"]),
        ("a.shade_group_hybrid", ["pref_hdr_frames", "pref_scam_hybrid_bento", "pref_scam_hybrid_bento_frames", "pref_scam_hybrid_shasta",
                                  "pref_scam_hybrid_zsl_frames", "pref_scam_hybrid_why_dn_factor", "pref_scam_hybrid_why_ms_chroma",
                                  "pref_scam_hybrid_cdm"]),
        ("a.shade_group_view", ["pref_show_grid_key", "pref_peak_method_key", "pref_live_viewfinder_raw_key", "pref_show_afdata_key",
                                "pref_face_detect_mode", "pref_tracking_af_mode"]),
    ]

    var level = Level.hidden
    var editing = false
    var openSlider: String?
    private(set) var revision = 0
    private var store: SettingsStore { SettingsStore.shared }

    func touch() { revision += 1 }

    /// One level up (HIDDEN -> PEEK -> FULL) or down
    func up() { withAnimation(CameraMotion.sheet) { level = Level(rawValue: min(2, level.rawValue + 1)) ?? .full } }
    func down() {
        withAnimation(CameraMotion.sheet) {
            level = Level(rawValue: max(0, level.rawValue - 1)) ?? .hidden
            if level == .hidden { editing = false; openSlider = nil }
        }
    }
    func set(_ l: Level) {
        withAnimation(CameraMotion.sheet) {
            level = l
            if l == .hidden { editing = false; openSlider = nil }
        }
    }

    /// The pinned keys in tile order (ui_shade_tiles; nothing stored: the defaults); unknown keys and repeats dropped
    var pinned: [String] {
        _ = revision
        let list: [String]
        if let raw = store.values[Self.key] {
            list = "\(raw)".split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        } else {
            list = Self.defaults
        }
        var out: [String] = []
        for k in list where !k.isEmpty && !out.contains(k) && ShadeCatalog.entry(k) != nil { out.append(k) }
        return out
    }

    func save(_ keys: [String]) {
        store.set(Self.key, keys.joined(separator: ","))
        touch()
    }

    func pin(_ key: String) {
        guard !pinned.contains(key) else { return }
        save(pinned + [key])
        MessageCenter.shared.info(String(format: L("a.shade_added"), ShadeCatalog.entry(key)?.title ?? key))
    }

    func unpin(_ key: String) {
        save(pinned.filter { $0 != key })
        if openSlider == key { openSlider = nil }
        MessageCenter.shared.info(String(format: L("a.shade_removed"), ShadeCatalog.entry(key)?.title ?? key))
    }

    /// The drag's drop: `key` takes `target`'s place (after it moving down, before it moving up)
    func move(_ key: String, before target: String) {
        var list = pinned
        guard let from = list.firstIndex(of: key), let to = list.firstIndex(of: target), from != to else { return }
        list.remove(at: from)
        list.insert(key, at: min(to, list.count))
        save(list)
    }
}

/// ShadeCatalog: the entries of the virtual camera controls and of the settings tree, with the shade's short names and labels
@MainActor
enum ShadeCatalog {
    /// shade_t_* of the curated keys (ShadeCatalog.SPECS) and their short value labels (shade_s_*)
    private static let specs: [String: (String, String?)] = [
        "pref_ae_mode_key": ("shade_t_flash", "shade_s_flash"), "pref_countdown_timer_key": ("shade_t_timer", "shade_s_timer"),
        "pref_save_raw_key": ("shade_t_format", nil), "ui_photo_size": ("shade_t_size", nil),
        "pref_tunable_parameters_disablemirror": ("shade_t_mirror", "shade_l_mirror"), "pref_horizon": ("shade_t_horizon", nil),
        "pref_motion_mode": ("shade_t_motion", nil), "pref_ettr": ("shade_t_ettr", nil), "pref_camera_sounds_key": ("shade_t_sound", nil),
        "pref_antibanding_hz_key": ("shade_t_flicker", nil), "pref_scam_hybrid_why_metering": ("shade_t_metering", "shade_s_why_metering"),
        "pref_scam_hybrid_output": ("shade_t_output", "shade_s_output"), "pref_scam_hybrid_resize": ("shade_t_resize", "shade_s_resize"),
        "pref_scam_hybrid_downsampler": ("shade_t_downsampler", "shade_s_downsampler"), "pref_ultrahdr_key": ("shade_t_ultrahdr", nil),
        "pref_hdr_frames": ("shade_t_hdr_frames", nil), "pref_portrait_enabled": ("shade_t_portrait", nil),
        "pref_scam_hybrid_why_face_mask_on": ("shade_t_face_mask", nil), "pref_show_watermark_key": ("shade_t_watermark", nil),
        "pref_scam_hybrid_bento": ("shade_t_bento", "shade_s_bento"), "pref_scam_hybrid_bento_frames": ("shade_t_bento_frames", "shade_s_bento_frames"),
        "pref_scam_hybrid_shasta": ("shade_t_shasta", nil), "pref_scam_hybrid_zsl_frames": ("shade_t_hybrid_frames", nil),
        "pref_scam_hybrid_why_dn_factor": ("shade_t_luma", nil), "pref_scam_hybrid_why_ms_chroma": ("shade_t_chroma", nil),
        "pref_scam_hybrid_cdm": ("shade_t_rejection", nil), "pref_show_grid_key": ("shade_t_grid", "shade_s_grid"),
        "pref_peak_method_key": ("shade_t_peak", "shade_s_peak"), "pref_live_viewfinder_raw_key": ("shade_t_live_raw", nil),
        "pref_show_afdata_key": ("shade_t_debug", "shade_s_debug"), "pref_face_detect_mode": ("shade_t_faces", "shade_s_faces"),
        "pref_tracking_af_mode": ("shade_t_tracking", "shade_s_tracking"),
    ]
    /// The concept's line icon of a tile (ShadeCatalog.CONCEPT_ICONS, as far as the app carries them)
    private static let icons: [String: String] = [
        "pref_ae_mode_key": "cv_bolt", "pref_countdown_timer_key": "cv_timer", "pref_save_raw_key": "cv_image", "ui_photo_size": "cv_res",
        "pref_scam_hybrid_output": "cv_res", "pref_scam_hybrid_downsampler": "cv_res", "pref_scam_hybrid_resize": "cv_res",
        "pref_show_grid_key": "cv_grid", "pref_horizon": "cv_horizon", "pref_ultrahdr_key": "cv_sparkle", "pref_hdr_frames": "cv_sparkle",
        "pref_motion_mode": "cv_run", "pref_film_lut": "cv_lut", "pref_lens_filter": "cv_filter", "pref_wide169_key": "cv_crop",
        "pref_tracking_af_mode": "cv_focus", "pref_peak_method_key": "cv_peak", "pref_scam_hybrid_bento": "cv_sun",
        "pref_scam_hybrid_bento_frames": "cv_layers", "pref_scam_hybrid_shasta": "cv_layers", "pref_scam_hybrid_zsl_frames": "cv_layers",
        "pref_scam_hybrid_why_dn_factor": "cv_wave", "pref_scam_hybrid_why_metering": "cv_target", "pref_ettr": "cv_target",
        "pref_show_afdata_key": "cv_info", "pref_antibanding_hz_key": "cv_bolt", "pref_face_detect_mode": "cv_target",
        "pref_tunable_parameters_disablemirror": "cv_flip", "pref_camera_sounds_key": "cv_wave",
    ]

    private static var cache: [String: ShadeEntry] = [:]
    private static var nodes: [String: SettingsNode]?

    static func node(_ key: String) -> SettingsNode? {
        if nodes == nil {
            var map: [String: SettingsNode] = [:]
            func walk(_ n: SettingsNode) {
                if let k = n.key, ["switch", "list", "slider"].contains(n.type) { map[k] = n }
                n.children?.forEach(walk)
            }
            if let tree = SettingsCatalog.shared.tree { walk(tree) }
            nodes = map
        }
        return nodes?[key]
    }

    /// Every pinnable row of the settings tree (the catalogue), in the tree's order with its page's title
    static func all() -> [(page: String, entries: [ShadeEntry])] {
        var titles: [String] = []
        var byTitle: [String: [ShadeEntry]] = [:]
        var seen = Set<String>()
        let catalog = SettingsCatalog.shared
        func walk(_ n: SettingsNode, page: String) {
            for c in n.children ?? [] {
                if let k = c.key, ["switch", "list", "slider"].contains(c.type), seen.insert(k).inserted, let e = entry(k) {
                    if byTitle[page] == nil { titles.append(page) }
                    byTitle[page, default: []].append(e)
                }
            }
            for c in n.children ?? [] where c.type == "screen" || c.type == "category" {
                walk(c, page: c.type == "screen" ? (catalog.text(c.title) ?? page) : page)
            }
        }
        if let tree = catalog.tree { walk(tree, page: L("a.shade_more")) }
        return titles.map { ($0, byTitle[$0] ?? []) }
    }

    static func label(_ android: String) -> String { L("a." + android) }

    /// An Android string array of the shade (its items "a.<name>.<i>"); an item the tables lack stays its key (checked by the caller)
    static func array(_ android: String, _ count: Int) -> [String] { (0..<count).map { L("a." + android + "." + String($0)) } }

    static func entry(_ key: String) -> ShadeEntry? {
        if let c = cache[key] { return c }
        let spec = specs[key]
        let icon = icons[key] ?? "kb_ic_shade_focus_rest"
        var e: ShadeEntry?
        switch key {
        case ShadeModel.flash:
            e = ShadeEntry(key: key, kind: .list, title: label("shade_t_flash"), longTitle: label("shade_title_flash"), icon: icon,
                           values: ["1", "2", "3", "0"], labels: array("shade_s_flash", 4), longLabels: array("shade_l_flash", 4), def: "1")
        case ShadeModel.timer:
            e = ShadeEntry(key: key, kind: .list, title: label("shade_t_timer"), longTitle: label("shade_title_timer"), icon: icon,
                           values: ["0", "1", "2"], labels: array("shade_s_timer", 3), longLabels: array("shade_l_timer", 3), def: "0")
        case ShadeModel.format:
            e = ShadeEntry(key: key, kind: .format, title: label("shade_t_format"), longTitle: label("shade_title_format"), icon: icon)
        case ShadeModel.size:
            e = ShadeEntry(key: key, kind: .size, title: label("shade_t_size"), longTitle: label("shade_title_size"), icon: icon)
        case ShadeModel.mirror:
            e = ShadeEntry(key: key, kind: .list, title: label("shade_t_mirror"), longTitle: label("shade_t_mirror"), icon: icon,
                           values: ["0", "1"], labels: array("shade_l_mirror", 2), longLabels: array("shade_l_mirror", 2), def: "0")
        default:
            guard let n = node(key) else { return nil }
            let catalog = SettingsCatalog.shared
            let long = catalog.text(n.title) ?? key
            let short = spec.map { label($0.0) } ?? long
            switch n.type {
            case "switch":
                e = ShadeEntry(key: key, kind: .toggle, title: short, longTitle: long, icon: icon,
                               values: ["false", "true"], labels: [label("shade_off"), label("shade_on")],
                               longLabels: [label("shade_off"), label("shade_on")], def: n.defaultValue?.bool == true ? "true" : "false", node: n)
            case "list":
                let values = n.inlineValues ?? catalog.array(n.values)
                let entries = n.inlineEntries ?? catalog.array(n.entries)
                var shorts = entries
                if let s = spec?.1 {
                    let a = array(s, values.count)
                    if !a.contains(where: { $0.hasPrefix("a.") }) { shorts = a }
                }
                e = ShadeEntry(key: key, kind: .list, title: short, longTitle: long, icon: icon, values: values, labels: shorts,
                               longLabels: entries, def: n.defaultValue?.text ?? values.first ?? "", node: n)
            default:
                e = ShadeEntry(key: key, kind: .slider, title: short, longTitle: long, icon: icon, def: n.defaultValue?.text ?? "0", node: n)
            }
        }
        if let e { cache[key] = e }
        return e
    }

    // MARK: values

    static func current(_ e: ShadeEntry, camera: CameraModel) -> String {
        let store = SettingsStore.shared
        switch e.kind {
        case .toggle: return store.bool(e.key, e.def == "true") ? "true" : "false"
        case .format: return FormatState.badge(camera)
        case .size: return FormatState.size(camera)
        case .list, .slider:
            if e.key == ShadeModel.flash { return String(camera.flashMode.rawValue) }
            return store.string(e.key, e.def)
        }
    }

    /// The tile's value line: the short label of the value (the toggle's «Вкл.» / «Выкл.»), a slider's number
    static func valueText(_ e: ShadeEntry, camera: CameraModel, long: Bool = false) -> String {
        let v = current(e, camera: camera)
        switch e.kind {
        case .format, .size: return v
        case .slider: return CameraStyle.decimal(v)
        default:
            if let i = e.values.firstIndex(of: v) {
                let l = long ? e.longLabels : e.labels
                return i < l.count ? l[i] : v
            }
            return v
        }
    }

    /// A value other than the default tints the tile
    static func changed(_ e: ShadeEntry, camera: CameraModel) -> Bool {
        switch e.kind {
        case .format: return FormatState.rawMode(camera) != FormatState.none
        case .size: return camera.fullResolution && camera.supportsFullResolution
        case .slider:
            let a = LutTrayModel.number(current(e, camera: camera), 0), b = LutTrayModel.number(e.def, 0)
            return abs(a - b) > 1e-6
        default: return current(e, camera: camera) != e.def
        }
    }

    /// Why the tile does nothing now (dimmed, the reason on a tap): the settings' availability, a flash on a lens without one
    static func reason(_ e: ShadeEntry, camera: CameraModel) -> String? {
        if e.key == ShadeModel.flash && !camera.lens.hasFlash && !camera.lens.hasTorch && !camera.lens.isFront { return L("a.shade_reason_no_flash") }
        if e.node == nil { return nil }
        return SettingsAvailability(SettingsStore.shared.values).reason(e.key)
    }

    /// A tap on a toggle or a short list: the next value (a long list opens its sheet instead)
    static func next(_ e: ShadeEntry, camera: CameraModel, chrome: CameraChrome) async {
        let store = SettingsStore.shared
        switch e.key {
        case ShadeModel.flash:
            await camera.cycleFlash()
            return
        case ShadeModel.timer:
            let s = chrome.cycleTimer()
            camera.say(s > 0 ? String(format: L("timer.seconds"), s) : L("timer.off"))
            return
        default: break
        }
        switch e.kind {
        case .toggle:
            let on = !store.bool(e.key, e.def == "true")
            store.set(e.key, on)
        case .list:
            let v = current(e, camera: camera)
            let i = e.values.firstIndex(of: v) ?? -1
            store.set(e.key, e.values[(i + 1) % max(1, e.values.count)])
        default: return
        }
        written(e, camera: camera)
    }

    static func setValue(_ e: ShadeEntry, _ value: String, camera: CameraModel) {
        if e.key == ShadeModel.flash, let target = Int(value) {
            Task {
                for _ in 0..<4 where camera.flashMode.rawValue != target { await camera.cycleFlash() }
            }
            return
        }
        SettingsStore.shared.set(e.key, value)
        written(e, camera: camera)
    }

    /// A value written from the shade: its toast («Сетка: 3×3»), the camera's own reads (the mode's ceiling, the faces)
    static func written(_ e: ShadeEntry, camera: CameraModel) {
        MessageCenter.shared.info(String(format: L("a.shade_toast_value"), e.title, valueText(e, camera: camera, long: true)))
        ShadeModel.shared.touch()
        if e.key == "pref_motion_mode" { Task { await camera.modeChanged() } }
    }
}

/// SettingsBarLayout (P25 / P495 / P574): the quick-settings shade over the viewfinder, above the bottom bar, 8 pt from the sides.
/// HIDDEN / PEEK (the handle, the header, the pinned tiles in swiped pages of 4 x 2 with their dots, the open slider's card) / FULL
/// (86 % of the viewfinder: the curated groups with an inline control and a pin per row, the pins outside them, «Добавить в
/// шторку»). One gesture moves one level; in FULL a scrim covers the viewfinder and the top row (a tap lowers the shade to PEEK).
/// A tile: a toggle or a short list steps on a tap, a long list opens its list, a slider opens its card under the grid, the format
/// and the size open their sheet; a long press (or «Изменить») is the edit mode - «×» unpins, a drag reorders.
struct QuickShade: View {
    let camera: CameraModel
    let chrome: CameraChrome
    let maxHeight: CGFloat
    var onFormat: (Bool) -> Void
    var onSettings: () -> Void
    @State private var page = 0
    @State private var catalog = false
    @State private var config = false
    @State private var listOf: ShadeEntry?
    @State private var dragY: CGFloat = 0
    private var model: ShadeModel { ShadeModel.shared }

    var body: some View {
        let _ = model.revision
        let _ = SettingsStore.shared.values.count
        VStack(spacing: 0) {
            handle
            header
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 10) {
                    tiles
                    if let key = model.openSlider, let e = ShadeCatalog.entry(key), e.kind == .slider { sliderCard(e) }
                    if model.level == .full { fullRows }
                }
                .padding(.bottom, 12)
            }
            .scrollDisabled(model.level != .full)
            // PEEK: as tall as the tiles (and the slider card), FULL: the room the level gives
            .fixedSize(horizontal: false, vertical: model.level != .full)
        }
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity)
        .frame(height: model.level == .full ? maxHeight : nil, alignment: .top)
        .background(UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28).fill(CameraStyle.control.opacity(0.96)))
        .overlay(UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28).stroke(CameraStyle.hairline, lineWidth: 0.5))
        .offset(y: max(0, dragY))
        .padding(.horizontal, 8)
        .sheet(isPresented: $catalog) { ShadeCatalogSheet() }
        .sheet(isPresented: $config) { ConfigMenuSheet(camera: camera) }
        .confirmationDialog(listOf?.longTitle ?? "", isPresented: Binding(get: { listOf != nil }, set: { if !$0 { listOf = nil } }),
                            titleVisibility: .visible) {
            if let e = listOf {
                ForEach(e.values.indices, id: \.self) { i in
                    Button(i < e.longLabels.count ? e.longLabels[i] : e.values[i]) { ShadeCatalog.setValue(e, e.values[i], camera: camera) }
                }
            }
        }
    }

    /// The sheet's grip: a tap cycles the levels (HIDDEN -> PEEK -> FULL -> HIDDEN), a drag moves one level
    private var handle: some View {
        RoundedRectangle(cornerRadius: 2).fill(Color(red: 0x4A / 255, green: 0x4F / 255, blue: 0x57 / 255)).frame(width: 40, height: 4)
            .frame(maxWidth: .infinity, minHeight: 20)
            .contentShape(Rectangle())
            .onTapGesture { model.level == .full ? model.set(.hidden) : model.up() }
            .gesture(levelDrag)
            .accessibilityLabel(L("a.shade_quick_title"))
            .accessibilityAddTraits(.isButton)
    }

    private var levelDrag: some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { dragY = $0.translation.height }
            .onEnded { d in
                withAnimation(CameraMotion.sheet) { dragY = 0 }
                if d.translation.height < -40 || d.predictedEndTranslation.height < -120 { model.up() }
                else if d.translation.height > 40 || d.predictedEndTranslation.height > 120 { model.down() }
            }
    }

    /// The header: the active config's name in a frame (its menu), the title, «Изменить» / «Готово», the gear
    private var header: some View {
        HStack(spacing: 8) {
            if !model.editing {
                Button { config = true } label: {
                    HStack(spacing: 4) {
                        Text(ConfigState.label()).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                        Image("cv_chev_down").renderingMode(.template).resizable().frame(width: 12, height: 12)
                    }
                    .foregroundStyle(CameraStyle.text)
                    .padding(.horizontal, 10).frame(height: 28)
                    .overlay(Capsule().stroke(CameraStyle.line, lineWidth: 1))
                }
                .accessibilityLabel(L("shade.config") + ConfigState.label())
            }
            Text(L(model.editing ? "a.shade_drag_title" : "a.shade_quick_title")).font(.system(size: 14, weight: .semibold))
                .foregroundStyle(CameraStyle.text).lineLimit(1)
            Spacer(minLength: 4)
            Button {
                Haptics.tick()
                withAnimation(CameraMotion.glide) { model.editing.toggle() }
            } label: {
                Text(L(model.editing ? "a.shade_done" : "a.shade_edit")).font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(model.editing ? CameraStyle.ink : CameraStyle.accent)
                    .padding(.horizontal, 12).frame(height: 28)
                    .background(model.editing ? CameraStyle.accent : Color.clear, in: Capsule())
            }
            if !model.editing {
                Button(action: onSettings) {
                    Image("cv_gear").renderingMode(.template).resizable().frame(width: 20, height: 20).foregroundStyle(CameraStyle.text)
                        .frame(width: 32, height: 32)
                }
                .accessibilityLabel(L("settings.open"))
            }
        }
        .padding(.horizontal, 6).padding(.bottom, 8)
        .gesture(levelDrag)
    }

    // MARK: tiles

    private var tiles: some View {
        let keys = model.pinned
        let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 4)
        return Group {
            if model.editing || model.level == .full {
                LazyVGrid(columns: columns, spacing: 0) {
                    ForEach(keys, id: \.self) { tile($0) }
                    addTile
                }
            } else {
                let pages = stride(from: 0, to: keys.count + 1, by: 8).map { Array((keys + ["+"])[$0..<min($0 + 8, keys.count + 1)]) }
                VStack(spacing: 6) {
                    TabView(selection: $page) {
                        ForEach(pages.indices, id: \.self) { p in
                            LazyVGrid(columns: columns, spacing: 0) {
                                ForEach(pages[p], id: \.self) { k in
                                    if k == "+" { addTile } else { tile(k) }
                                }
                            }
                            .frame(maxHeight: .infinity, alignment: .top)
                            .tag(p)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                    .frame(height: 2 * 92)
                    if pages.count > 1 {
                        HStack(spacing: 6) {
                            ForEach(pages.indices, id: \.self) { p in
                                Circle().fill(p == page ? CameraStyle.text : CameraStyle.text.opacity(0.3)).frame(width: 6, height: 6)
                            }
                        }
                    }
                }
            }
        }
    }

    /// ShadeTileView: an 84 pt card (13 pt corners) holding the icon (22 pt), the short name (11 pt semibold) and the value (10 pt,
    /// muted); a value other than the default tints the card with the accent (14 %, a 35 % accent edge) and turns the icon and the
    /// value accent; an unavailable setting is dimmed to 45 %; the open slider's tile has an accent outline
    @ViewBuilder private func tile(_ key: String) -> some View {
        if let e = ShadeCatalog.entry(key) {
            let changed = ShadeCatalog.changed(e, camera: camera)
            let reason = ShadeCatalog.reason(e, camera: camera)
            let open = model.openSlider == key
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 2) {
                    Image(e.icon).renderingMode(.template).resizable().scaledToFit().frame(width: 22, height: 22)
                        .foregroundStyle(changed ? CameraStyle.accent : CameraStyle.muted)
                        .turnsWithPhone()
                    Text(e.title).font(.system(size: 11, weight: .semibold)).foregroundStyle(CameraStyle.text).lineLimit(1).minimumScaleFactor(8 / 11)
                    Text(ShadeCatalog.valueText(e, camera: camera)).font(.system(size: 10)).lineLimit(1).minimumScaleFactor(0.8)
                        .foregroundStyle(changed ? CameraStyle.accent : CameraStyle.muted)
                }
                .padding(2)
                .frame(maxWidth: .infinity, minHeight: 84)
                .background(RoundedRectangle(cornerRadius: 13).fill(changed ? CameraStyle.accent.opacity(0.14) : CameraStyle.card.opacity(0.6)))
                .overlay(RoundedRectangle(cornerRadius: 13).stroke(open ? CameraStyle.accent : changed ? CameraStyle.accent.opacity(0.35) : CameraStyle.line,
                                                                   lineWidth: open ? 1.5 : 1))
                .opacity(reason != nil ? 0.45 : 1)
                .rotationEffect(.degrees(model.editing ? 1.2 : 0))
                .animation(model.editing ? .easeInOut(duration: 0.13).repeatForever(autoreverses: true) : .default, value: model.editing)
                if model.editing {
                    Button { Haptics.tick(); withAnimation(CameraMotion.glide) { model.unpin(key) } } label: {
                        Text("×").font(.system(size: 14, weight: .bold)).foregroundStyle(CameraStyle.control)
                            .frame(width: 20, height: 20).background(CameraStyle.badge, in: Circle())
                    }
                    .offset(x: 2, y: -2)
                    .accessibilityLabel(String(format: L("a.shade_remove_description"), e.title))
                }
            }
            .padding(4)
            .contentShape(Rectangle())
            .onTapGesture { tap(e, reason: reason) }
            .onLongPressGesture(minimumDuration: 0.45) {
                Haptics.longPress()
                withAnimation(CameraMotion.glide) { model.editing = true }
            }
            .modifier(EditDrag(enabled: model.editing, key: key))
            .dropDestination(for: String.self) { items, _ in
                guard model.editing, let from = items.first else { return false }
                withAnimation(CameraMotion.glide) { model.move(from, before: key) }
                Haptics.tick()
                return true
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(e.longTitle + ", " + ShadeCatalog.valueText(e, camera: camera, long: true))
            .accessibilityHint(reason ?? "")
            .accessibilityAddTraits(.isButton)
        }
    }

    /// The last tile «Добавить»: a dashed outline, no fill; opens the catalogue
    private var addTile: some View {
        VStack(spacing: 2) {
            Image("kb_ic_shade_plus_rest").renderingMode(.template).resizable().frame(width: 22, height: 22).foregroundStyle(CameraStyle.muted)
            Text(L("a.shade_add")).font(.system(size: 11, weight: .semibold)).foregroundStyle(CameraStyle.text)
        }
        .frame(maxWidth: .infinity, minHeight: 84)
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(CameraStyle.line, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
        .padding(4)
        .contentShape(Rectangle())
        .onTapGesture { catalog = true }
        .accessibilityLabel(L("a.shade_add_description"))
        .accessibilityAddTraits(.isButton)
    }

    private func tap(_ e: ShadeEntry, reason: String?) {
        if model.editing { return }
        if let reason {
            MessageCenter.shared.info(reason)
            return
        }
        Haptics.tick()
        switch e.kind {
        case .format: onFormat(false)
        case .size: onFormat(true)
        case .slider: withAnimation(CameraMotion.glide) { model.openSlider = model.openSlider == e.key ? nil : e.key }
        case .list where e.values.count > 5: listOf = e
        default: Task { await ShadeCatalog.next(e, camera: camera, chrome: chrome) }
        }
    }

    /// The inline slider card of the open slider tile: its name, the value pill and the slider (SliderGrid's steps and texts)
    private func sliderCard(_ e: ShadeEntry) -> some View {
        let node = e.node
        let stored = SettingsStore.shared.string(e.key, e.def)
        let value = LutTrayModel.number(stored, LutTrayModel.number(e.def, 0))
        let steps = node.map { SliderGrid.steps($0) } ?? 100
        let param = TrayParam(id: e.key, title: e.longTitle, key: e.key, min: 0, max: Double(steps), step: 1,
                              def: node.map { Double(SliderGrid.progress(of: LutTrayModel.number(e.def, 0), node: $0)) } ?? 0,
                              read: { node.map { Double(SliderGrid.progress(of: value, node: $0)) } ?? 0 },
                              write: { p in
                                  guard let node else { return }
                                  SettingsStore.shared.set(e.key, SliderGrid.text(progress: Int(p.rounded()), node: node))
                                  ShadeModel.shared.touch()
                              })
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(e.longTitle).font(.system(size: 13, weight: .semibold)).foregroundStyle(CameraStyle.text).lineLimit(1)
                Spacer()
                Text(CameraStyle.decimal(stored)).font(.system(size: 13, weight: .semibold)).monospacedDigit().foregroundStyle(CameraStyle.ink)
                    .padding(.horizontal, 8).frame(height: 22).background(CameraStyle.accent, in: Capsule())
            }
            TraySlider(param: param, track: nil)
        }
        .padding(12)
        .background(CameraStyle.card.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 4)
    }

    // MARK: FULL

    private var fullRows: some View {
        let pinned = model.pinned
        let grouped = Set(ShadeModel.groups.flatMap { $0.1 })
        let extra = pinned.filter { !grouped.contains($0) }
        return VStack(alignment: .leading, spacing: 12) {
            ForEach(ShadeModel.groups, id: \.0) { g in
                group(L(g.0), keys: g.1)
            }
            if !extra.isEmpty { group(L("a.shade_group_extra"), keys: extra) }
            Button { catalog = true } label: {
                HStack {
                    Image("kb_ic_shade_plus_rest").renderingMode(.template).resizable().frame(width: 20, height: 20)
                    Text(L("a.shade_catalog_title")).font(.system(size: 15, weight: .semibold))
                    Spacer()
                }
                .foregroundStyle(CameraStyle.accent)
                .padding(.horizontal, 14).frame(height: 48)
                .background(CameraStyle.card.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .padding(.horizontal, 4)
    }

    private func group(_ title: String, keys: [String]) -> some View {
        let entries = keys.compactMap { ShadeCatalog.entry($0) }
        return VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased()).font(.system(size: 11.5, weight: .semibold)).kerning(0.8).foregroundStyle(CameraStyle.accent)
                .padding(.leading, 8)
            VStack(spacing: 0) {
                ForEach(Array(entries.enumerated()), id: \.offset) { i, e in
                    if i > 0 { Rectangle().fill(CameraStyle.line).frame(height: 0.5).padding(.leading, 44) }
                    row(e)
                }
            }
            .background(CameraStyle.card.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
        }
    }

    /// A FULL row: the icon, the name, the inline control (a switch, the value that steps or opens its list, a slider's value) and
    /// the pin
    private func row(_ e: ShadeEntry) -> some View {
        let reason = ShadeCatalog.reason(e, camera: camera)
        let pinned = model.pinned.contains(e.key)
        return HStack(spacing: 10) {
            Image(e.icon).renderingMode(.template).resizable().scaledToFit().frame(width: 20, height: 20).foregroundStyle(CameraStyle.muted)
            VStack(alignment: .leading, spacing: 1) {
                Text(e.longTitle).font(.system(size: 14)).foregroundStyle(CameraStyle.text).lineLimit(2)
                if let reason { Text(reason).font(.system(size: 11)).foregroundStyle(CameraStyle.summary).lineLimit(2) }
            }
            Spacer(minLength: 4)
            if e.kind == .toggle {
                Toggle("", isOn: Binding(get: { SettingsStore.shared.bool(e.key, e.def == "true") },
                                         set: { SettingsStore.shared.set(e.key, $0); ShadeCatalog.written(e, camera: camera) }))
                    .labelsHidden().tint(CameraStyle.accent)
            } else {
                Text(ShadeCatalog.valueText(e, camera: camera)).font(.system(size: 13, weight: .semibold)).foregroundStyle(CameraStyle.accent)
                    .lineLimit(1).padding(.horizontal, 10).frame(height: 28)
                    .background(CameraStyle.accent.opacity(0.14), in: Capsule())
                    .onTapGesture { tap(e, reason: reason) }
            }
            Button {
                Haptics.tick()
                withAnimation(CameraMotion.glide) { pinned ? model.unpin(e.key) : model.pin(e.key) }
            } label: {
                Image(systemName: pinned ? "pin.fill" : "pin").font(.system(size: 14))
                    .foregroundStyle(pinned ? CameraStyle.accent : CameraStyle.muted).frame(width: 30, height: 30)
            }
            .accessibilityLabel(String(format: L(pinned ? "a.shade_unpin" : "a.shade_pin"), e.title))
        }
        .padding(.horizontal, 12).frame(minHeight: 48)
        .opacity(reason != nil ? 0.6 : 1)
    }
}

/// ShadeCatalogView: «Добавить в шторку» - every setting a tile can show, by its settings page, searchable (the names in both
/// languages are the settings' own), each with its pin; «В шторке: N · настроек: M».
struct ShadeCatalogSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    private var model: ShadeModel { ShadeModel.shared }

    var body: some View {
        let _ = model.revision
        let pages = ShadeCatalog.all()
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let shown = pages.map { ($0.page, $0.entries.filter { q.isEmpty || $0.longTitle.lowercased().contains(q) || $0.title.lowercased().contains(q) || $0.key.contains(q) }) }
            .filter { !$0.1.isEmpty }
        let total = pages.reduce(0) { $0 + $1.entries.count }
        let found = shown.reduce(0) { $0 + $1.1.count }
        NavigationStack {
            List {
                Section {
                    Text(q.isEmpty ? String(format: L("a.shade_catalog_count"), model.pinned.count, total)
                                   : String(format: L("a.shade_catalog_found"), found, model.pinned.count))
                        .font(.footnote).foregroundStyle(.secondary)
                } footer: { Text(L("a.shade_catalog_note")) }
                if shown.isEmpty { Text(L("a.shade_catalog_nothing")).foregroundStyle(.secondary) }
                ForEach(shown, id: \.0) { page in
                    Section(page.0) {
                        ForEach(page.1, id: \.key) { e in
                            let pinned = model.pinned.contains(e.key)
                            Button {
                                Haptics.tick()
                                pinned ? model.unpin(e.key) : model.pin(e.key)
                            } label: {
                                HStack {
                                    Image(e.icon).renderingMode(.template).resizable().scaledToFit().frame(width: 20, height: 20)
                                        .foregroundStyle(pinned ? CameraStyle.accent : .secondary)
                                    Text(e.longTitle).foregroundStyle(.primary)
                                    Spacer()
                                    Image(systemName: pinned ? "checkmark.circle.fill" : "plus.circle").foregroundStyle(pinned ? CameraStyle.accent : .secondary)
                                }
                            }
                            .accessibilityLabel(String(format: L(pinned ? "a.shade_catalog_row_pinned" : "a.shade_catalog_row_add"), e.longTitle))
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: L("a.shade_catalog_search"))
            .navigationTitle(L("a.shade_catalog_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("a.shade_catalog_close")) { dismiss() } } }
        }
        .preferredColorScheme(.dark)
    }
}

/// ConfigMenu (P143 / P255): the shade's profile menu in the words and order of the settings' «Профили» - the active profile and
/// «Сохранить» over it, «Сохранить текущие как…», «Сохранённые» newest first (load / delete), «Загрузить из файла», «Сбросить
/// всё»; a step that needs a word (a name, a confirmation) opens in place. Loading, importing and the reset apply the settings at
/// once (the camera reads them again).
struct ConfigMenuSheet: View {
    let camera: CameraModel
    @Environment(\.dismiss) private var dismiss
    @State private var library = ProfileLibrary.shared
    @State private var name = ""
    @State private var openRow: String?
    @State private var importing = false

    var body: some View {
        let active = ConfigState.active()
        NavigationStack {
            List {
                Section(L("config.active")) {
                    Text(ConfigState.label()).font(.headline)
                    if let active {
                        Button { done(library.save(active) != nil) } label: {
                            Label(L("config.save") + " «" + active + "»", systemImage: "square.and.arrow.down")
                        }
                    }
                }
                Section {
                    Button { withAnimation { openRow = openRow == "saveas" ? nil : "saveas" } } label: {
                        Label(L("config.save_as"), systemImage: "plus")
                    }
                    if openRow == "saveas" {
                        TextField(L("config.name"), text: $name).textInputAutocapitalization(.never)
                        Button(L("config.save")) {
                            let n = ProfileLibrary.accepted(name)
                            guard !n.isEmpty else { MessageCenter.shared.info(L("config.enter_name")); return }
                            done(library.save(n) != nil)
                        }
                    }
                } footer: { Text(L("config.save_as_help")) }
                if !library.files.isEmpty {
                    Section(L("config.saved")) {
                        ForEach(library.files, id: \.self) { url in
                            let base = ConfigState.base(url.lastPathComponent)
                            Button { withAnimation { openRow = openRow == base ? nil : base } } label: {
                                HStack {
                                    Text(base).foregroundStyle(.primary)
                                    Spacer()
                                    if base == active { Text(L("config.now")).foregroundStyle(CameraStyle.accent) }
                                }
                            }
                            if openRow == base {
                                Text(L("config.load_note")).font(.footnote).foregroundStyle(.secondary)
                                HStack {
                                    Button(L("config.load")) { library.load(url); after() }.buttonStyle(.borderedProminent)
                                    Spacer()
                                    Button(L("config.delete"), role: .destructive) { library.delete(url); library.refresh(); openRow = nil }
                                }
                            }
                        }
                    }
                }
                Section {
                    Button { importing = true } label: { Label(L("config.import"), systemImage: "doc") }
                    Button(role: .destructive) { withAnimation { openRow = openRow == "reset" ? nil : "reset" } } label: {
                        Label(L("config.reset"), systemImage: "arrow.counterclockwise")
                    }
                    if openRow == "reset" {
                        Text(L("config.reset_note")).font(.footnote).foregroundStyle(.secondary)
                        Button(L("config.reset_confirm"), role: .destructive) { library.reset(); after() }
                    }
                }
            }
            .navigationTitle(L("config.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("a.shade_catalog_close")) { dismiss() } } }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.xml, .json, .item]) { result in
                if case .success(let url) = result { library.load(url); after() }
            }
            .onAppear { library.refresh() }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }

    private func done(_ ok: Bool) {
        if let m = library.message { ok ? MessageCenter.shared.info(m) : MessageCenter.shared.error(m) }
        if ok { dismiss() }
    }

    /// A load or a reset: the message, the camera's own settings read again
    private func after() {
        if let m = library.message { MessageCenter.shared.info(m) }
        ShadeModel.shared.touch()
        LutTrayModel.shared.touch()
        Task { await camera.modeChanged() }
        dismiss()
    }
}

/// A tile is dragged to reorder only in the edit mode (else its long press starts the edit mode)
private struct EditDrag: ViewModifier {
    let enabled: Bool
    let key: String
    func body(content: Content) -> some View {
        if enabled { content.draggable(key) } else { content }
    }
}
