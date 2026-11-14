import SwiftUI

/// P633 EditWatermarkPanel: the editor's «Знак» tab. What happens to the photo's watermark - «Как есть», «Убрать» (only for a photo
/// that kept the pixels under its watermark: SCAMERA's, shot with it on; otherwise greyed, a tap says why), «Изменить» (taken off and
/// drawn again) / «Добавить» (a photo without a record) - and for a watermark drawn, the camera's own settings with the camera's names
/// and ranges (WatermarkSpec; the settings page's texts as the long-press help): style, position, colour, font, size, opacity, margin,
/// then every field on its own switch; a field this photo's EXIF lacks is greyed with «нет в EXIF». The copy draws it with the
/// camera's code (PhotoWatermark), the picture shows it live (EditorModel.watermarkOverlay).
@MainActor
struct EditWatermarkPanel: View {
    let model: EditorModel
    @State private var help: (title: String, text: String)?
    @State private var text = ""

    /// The settings page's string `key` (Android res/values, the interface language).
    static func pref(_ key: String) -> String {
        SettingsCatalog.shared.text(SettingsNode.Text(s: key, t: nil)) ?? key
    }

    static func entries(_ array: String) -> [String] {
        SettingsCatalog.shared.array(array)
    }

    private var record: SCAMWatermarkRecord? { model.watermarkRecord }

    var body: some View {
        VStack(spacing: 0) {
            hint(Self.statusText(record))
            GallerySegments(names: modeNames, selected: model.watermarkMode.rawValue) { model.pickWatermarkMode(EditorModel.WatermarkMode(rawValue: $0) ?? .keep) }
                .overlay(alignment: .center) { removeShade }
                .accessibilityHint(record == nil ? L("editor.wm.remove_unavailable") : "")
                .helpOnLongPress { help = (L("editor.wm.title"), Self.statusText(record)) }
            switch model.watermarkMode {
            case .remove: hint(L("editor.wm.removed_hint"))
            case .draw: drawControls
            case .keep: EmptyView()
            }
        }
        .alert(help?.title ?? "", isPresented: Binding(get: { help != nil }, set: { if !$0 { help = nil } })) {
            Button(L("gallery.ok"), role: .cancel) { help = nil }
        } message: {
            Text(help?.text ?? "")
        }
        .onAppear { text = model.watermarkSpec.text }
        .onChange(of: model.watermarkSpec.text) { _, t in if t != text { text = t } }
    }

    /// «Убрать» greyed without a record (the segment itself still answers: the model says why).
    @ViewBuilder private var removeShade: some View {
        if record == nil {
            GeometryReader { geo in
                Rectangle().fill(GalleryStyle.page.opacity(0.55))
                    .frame(width: (geo.size.width - 36) / 3 - 2, height: 30)
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
    }

    /// The mode's labels: «Изменить» with a record (the old one goes), «Добавить» without.
    private var modeNames: [String] {
        [L("editor.wm.as_is"), L("editor.wm.remove"), record != nil ? L("editor.wm.change") : L("editor.wm.add")]
    }

    static func statusText(_ record: SCAMWatermarkRecord?) -> String {
        guard let record else { return L("editor.wm.no_record") }
        var what = ""
        if let s = WatermarkSpec.fromJson(record.spec) {
            let layouts = entries("watermark_layout_entries"), positions = entries("watermark_position_entries")
            let l = WatermarkSpec.Layout.allCases.firstIndex(of: s.layout) ?? 0, p = WatermarkSpec.Position.allCases.firstIndex(of: s.position) ?? 0
            if l < layouts.count, p < positions.count { what = " («" + layouts[l] + "», " + positions[p].lowercased() + ")" }
        }
        return String(format: L("editor.wm.status"), what)
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.app(12)).foregroundStyle(GalleryStyle.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18).padding(.top, 6).padding(.bottom, 4)
    }

    private func caption(_ text: String) -> some View {
        Text(text).font(.app(15)).foregroundStyle(GalleryStyle.text)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18).padding(.top, 10)
    }

    // ---------------------------------------------------------------- the watermark drawn

    @ViewBuilder private var drawControls: some View {
        let spec = model.watermarkSpec
        let info = model.watermarkInfo
        lookControls(spec)
        fieldControls(spec, info)
        if WatermarkExif.has(info, .time) {
            chips("prefs_watermark_date_format_title", "prefs_watermark_date_format_summary", Self.dates(info),
                  WatermarkSpec.DateFormat.allCases.firstIndex(of: spec.dateFormat) ?? 0) { i in
                model.editWatermark(commit: true) { $0.dateFormat = WatermarkSpec.DateFormat.allCases[i] }
            }
        }
        HStack {
            GalleryChip(text: L("editor.wm.camera"), on: false) { model.watermarkAsCamera() }
                .helpOnLongPress { help = (L("editor.wm.camera"), L("editor.wm.camera_help")) }
            Spacer()
        }
        .padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 4)
        hint(L("editor.wm.hint"))
    }

    /// «Вид»: style, position, colour, font, size, opacity, margin.
    @ViewBuilder private func lookControls(_ spec: WatermarkSpec) -> some View {
        GallerySection(text: L("editor.wm.look"))
        choice("prefs_watermark_layout_title", "prefs_watermark_layout_summary", "watermark_layout_entries",
               WatermarkSpec.Layout.allCases.firstIndex(of: spec.layout) ?? 0) { i in
            model.editWatermark(commit: true) { $0.layout = WatermarkSpec.Layout.allCases[i] }
        }
        chips("prefs_watermark_position_title", "prefs_watermark_position_summary", Self.entries("watermark_position_entries"),
              WatermarkSpec.Position.allCases.firstIndex(of: spec.position) ?? 0) { i in
            model.editWatermark(commit: true) { $0.position = WatermarkSpec.Position.allCases[i] }
        }
        choice("prefs_watermark_color_title", "prefs_watermark_color_summary", "watermark_color_entries",
               WatermarkSpec.Colour.allCases.firstIndex(of: spec.colour) ?? 0) { i in
            model.editWatermark(commit: true) { $0.colour = WatermarkSpec.Colour.allCases[i] }
        }
        choice("prefs_watermark_font_title", "prefs_watermark_font_summary", "watermark_font_entries",
               WatermarkSpec.Font.allCases.firstIndex(of: spec.font) ?? 0) { i in
            model.editWatermark(commit: true) { $0.font = WatermarkSpec.Font.allCases[i] }
        }
        slider("prefs_watermark_size_title", "prefs_watermark_size_summary", WatermarkSpec.minSize, WatermarkSpec.maxSize,
               WatermarkSpec.defaultSize, spec.size, { "\(EditParam.javaRound($0))" }) { s, v in s.size = v }
        slider("prefs_watermark_opacity_title", "prefs_watermark_opacity_summary", WatermarkSpec.minOpacity, 100,
               WatermarkSpec.defaultOpacity, spec.opacity * 100, { "\(EditParam.javaRound($0)) %" }) { s, v in s.opacity = v / 100 }
        slider("prefs_watermark_margin_title", "prefs_watermark_margin_summary", 0, WatermarkSpec.maxMargin, WatermarkSpec.defaultMargin,
               spec.margin, { "\(EditParam.javaRound($0)) %" }) { s, v in s.margin = v }
    }

    /// «Что показывать»: every field on its own switch, the photo's value under it.
    @ViewBuilder private func fieldControls(_ spec: WatermarkSpec, _ info: WatermarkText.Info) -> some View {
        GallerySection(text: L("editor.wm.shows"))
        VStack(spacing: 0) {
            Group {
                toggle("prefs_watermark_show_text_title", "prefs_watermark_show_text_summary", nil, true, spec.showText) { $0.showText = $1 }
                textRow(spec)
                divider
                toggle("prefs_watermark_show_device_title", "prefs_watermark_show_device_summary", Self.value(info, .device),
                       WatermarkExif.has(info, .device), spec.showDevice) { $0.showDevice = $1 }
                divider
                toggle("prefs_watermark_show_lens_title", "prefs_watermark_show_lens_summary", Self.value(info, .lens),
                       WatermarkExif.has(info, .lens), spec.showLens) { $0.showLens = $1 }
                divider
                toggle("prefs_watermark_show_aperture_title", "prefs_watermark_show_aperture_summary", Self.value(info, .aperture),
                       WatermarkExif.has(info, .aperture), spec.showAperture) { $0.showAperture = $1 }
            }
            Group {
                divider
                toggle("prefs_watermark_show_shutter_title", "prefs_watermark_show_shutter_summary", Self.value(info, .shutter),
                       WatermarkExif.has(info, .shutter), spec.showShutter) { $0.showShutter = $1 }
                divider
                toggle("prefs_watermark_show_iso_title", "prefs_watermark_show_iso_summary", Self.value(info, .iso),
                       WatermarkExif.has(info, .iso), spec.showIso) { $0.showIso = $1 }
                divider
                toggle("prefs_watermark_show_time_title", "prefs_watermark_show_time_summary", Self.value(info, .time),
                       WatermarkExif.has(info, .time), spec.showTime) { $0.showTime = $1 }
                divider
                toggle("prefs_watermark_logo_title", "prefs_desc_pref_watermark_logo", nil, true, spec.logo) { $0.logo = $1 }
            }
        }
        .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 18).padding(.top, 6)
    }

    private var divider: some View {
        Rectangle().fill(GalleryStyle.hairline).frame(height: 0.5).padding(.leading, 16)
    }

    /// A segmented choice under its caption; a long press shows the setting's help.
    private func choice(_ title: String, _ summary: String, _ array: String, _ selected: Int, pick: @escaping (Int) -> Void) -> some View {
        VStack(spacing: 0) {
            caption(Self.pref(title))
            GallerySegments(names: Self.entries(array), selected: selected, pick: pick)
        }
        .helpOnLongPress { help = (Self.pref(title), Self.pref(summary)) }
    }

    private func chips(_ title: String, _ summary: String, _ names: [String], _ selected: Int, pick: @escaping (Int) -> Void) -> some View {
        VStack(spacing: 0) {
            caption(Self.pref(title))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(names.indices, id: \.self) { i in GalleryChip(text: names[i], on: i == selected) { pick(i) } }
                }
                .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 6)
            }
        }
        .helpOnLongPress { help = (Self.pref(title), Self.pref(summary)) }
    }

    private func slider(_ title: String, _ summary: String, _ min: Float, _ max: Float, _ def: Float, _ value: Float,
                        _ format: @escaping (Float) -> String, set: @escaping (inout WatermarkSpec, Float) -> Void) -> some View {
        EditSliderRow(label: Self.pref(title), min: min, max: max, def: def, step: 1, bipolar: false, value: value, format: format) { v, commit in
            model.editWatermark(commit: commit) { set(&$0, v) }
        }
        .helpOnLongPress { help = (Self.pref(title), Self.pref(summary)) }
    }

    /// A field's row: the name, the photo's value under it (or «нет в EXIF»: the row greyed, its switch off and still), the switch.
    private func toggle(_ title: String, _ summary: String, _ value: String?, _ available: Bool, _ on: Bool,
                        set: @escaping (inout WatermarkSpec, Bool) -> Void) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.pref(title)).font(.app(16)).foregroundStyle(GalleryStyle.text)
                if let value, !value.isEmpty {
                    Text(value).font(.app(12)).foregroundStyle(GalleryStyle.muted).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Toggle("", isOn: Binding(get: { available && on }, set: { v in model.editWatermark(commit: true) { set(&$0, v) } }))
                .labelsHidden()
                .tint(GalleryStyle.accent)
                .disabled(!available)
        }
        .padding(.horizontal, 16).padding(.vertical, 8).frame(minHeight: 52)
        .opacity(available ? 1 : 0.45)
        .contentShape(Rectangle())
        .helpOnLongPress {
            help = (Self.pref(title), available ? Self.pref(summary) : Self.pref(summary) + "\n\n" + L("editor.wm.not_in_exif_help"))
        }
        .accessibilityElement(children: .combine)
    }

    /// The own text, typed in place (64 characters at most, as the camera draws); one undo step when the typing ends.
    private func textRow(_ spec: WatermarkSpec) -> some View {
        TextField(Self.pref("prefs_watermark_text_title"), text: $text)
            .font(.app(16)).foregroundStyle(GalleryStyle.text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .padding(.horizontal, 16).frame(minHeight: 44)
            .disabled(!spec.showText)
            .opacity(spec.showText ? 1 : 0.45)
            .onChange(of: text) { _, v in
                var cut = v
                if cut.utf16.count > WatermarkSpec.maxText { cut = String(decoding: Array(cut.utf16.prefix(WatermarkSpec.maxText)), as: UTF16.self) }
                if cut != v { text = cut; return }
                if cut != model.watermarkSpec.text { model.editWatermark(commit: false) { $0.text = cut } }
            }
            .onSubmit { model.changed(commit: true) }
    }

    /// The field's value from the photo («24mm»), «нет в EXIF» without it.
    static func value(_ info: WatermarkText.Info, _ f: WatermarkExif.Field) -> String {
        if !WatermarkExif.has(info, f) { return L("editor.wm.not_in_exif") }
        let one = WatermarkSpec().only(text: false, device: f == .device, lens: f == .lens, aperture: f == .aperture, shutter: f == .shutter,
                                       iso: f == .iso, time: f == .time, logo: false)
        return WatermarkText.of(one, info).groups.first ?? ""
    }

    /// The date formats as this photo's date reads in each.
    static func dates(_ info: WatermarkText.Info) -> [String] {
        let t = info.timeMs > 0 ? info.timeMs : Int64((Date().timeIntervalSince1970 * 1000).rounded())
        return WatermarkSpec.DateFormat.allCases.map { WatermarkText.time(t, zone: info.zone, format: $0, ru: WatermarkText.interfaceRussian) }
    }
}

extension View {
    /// EditWatermarkPanel.help: a long press shows the setting's help, beside the control's own taps and drags.
    func helpOnLongPress(_ show: @escaping () -> Void) -> some View {
        simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in show() })
    }
}
