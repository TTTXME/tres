import Photos
import SwiftUI
import UIKit

/// PhotoEditorFragment: the picture on black, the top bar (close, the name, undo, redo, «Ещё», «Сохранить копию»), the tools on
/// the #1C1C1E sheet with 30pt top corners, the tab bar (Кадр, Проявка, Стили, LUT, Свет, Цвет, Эффекты, Детали, Знак). A long press shows the
/// original («Оригинал»); the crop tab shows the whole turned photo with the crop frame. The picture is EditorView's frame,
/// rendered by the Android pipeline (SCAMEditSession).
@MainActor
struct PhotoEditorView: View {
    @State private var model: EditorModel
    @State private var confirmLeave = false
    @GestureState private var holding = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale

    init(asset: PHAsset, raw: Bool) {
        _model = State(initialValue: EditorModel(asset: asset, raw: raw))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                imageArea
                panel
                tabBar
            }
            if model.exporting { exportOverlay }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
        .task { await model.open(scale: displayScale) }
        .onDisappear { model.close() }
        .onChange(of: holding) { _, on in model.before = on && model.tab != .crop }
        .alert(L("editor.leave_title"), isPresented: $confirmLeave) {
            Button(L("editor.keep_editing"), role: .cancel) {}
            Button(L("editor.leave"), role: .destructive) { dismiss() }
        } message: {
            Text(L("editor.leave_message"))
        }
        .alert(model.savedMessage ?? "", isPresented: Binding(get: { model.savedMessage != nil }, set: { if !$0 { model.clearMessage() } })) {
            Button(L("gallery.ok"), role: .cancel) { model.clearMessage() }
        }
    }

    // ---------------------------------------------------------------- top bar

    private var topBar: some View {
        HStack(spacing: 6) {
            RoundIconButton(icon: "ui_ic_close", label: L("editor.close")) {
                if model.hasChanges { confirmLeave = true } else { dismiss() }
            }
            Text(model.name).font(.system(size: 13)).foregroundStyle(GalleryStyle.muted).lineLimit(1).truncationMode(.middle)
                .frame(maxWidth: .infinity).padding(.horizontal, 2)
            RoundIconButton(icon: "kb_ui_ic_undo_rest", label: L("editor.undo"), enabled: model.history.canUndo) { model.undo() }
            RoundIconButton(icon: "kb_ui_ic_redo_rest", label: L("editor.redo"), enabled: model.history.canRedo) { model.redo() }
            Menu {
                Button { model.resetAll() } label: { Label(L("editor.reset_all"), image: "kb_ui_ic_backup_rest") }
                Button { model.copySettings() } label: { Label(L("editor.copy_settings"), image: "kb_ui_ic_layers_rest") }
                Button { model.pasteSettings() } label: { Label(L("editor.paste_settings"), image: "ui_ic_plus") }
                    .disabled(EditorModel.clipboard == nil)
            } label: {
                GalleryIcon(name: "ui_ic_more", size: 22).frame(width: 40, height: 40).background(GalleryStyle.card, in: Circle())
            }
            .accessibilityLabel(L("gallery.more"))
            Button { Task { await model.save() } } label: {
                Text(L("editor.save_copy")).font(.system(size: 14, weight: .semibold)).foregroundStyle(GalleryStyle.ink)
                    .padding(.horizontal, 14).frame(minHeight: 40).background(GalleryStyle.accent, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(model.loading || model.failure != nil)
            .padding(.leading, 2)
        }
        .padding(.horizontal, 12).padding(.top, 14).padding(.bottom, 6)
    }

    // ---------------------------------------------------------------- the picture

    private var imageArea: some View {
        GeometryReader { geo in
            ZStack {
                if let picture = model.picture {
                    Image(uiImage: picture).resizable().frame(width: geo.size.width, height: geo.size.height)
                }
                // P633: the watermark's live preview over the picture (WatermarkOverlayView)
                if let overlay = model.watermarkOverlay {
                    Image(uiImage: overlay).resizable()
                        .frame(width: model.watermarkRect.width, height: model.watermarkRect.height)
                        .position(x: model.watermarkRect.midX, y: model.watermarkRect.midY)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                if model.tab == .crop, !model.loading, model.failure == nil {
                    CropOverlay(model: model, size: geo.size)
                }
                VStack {
                    HStack(alignment: .top) {
                        if model.before { GalleryPill(text: L("editor.original")).transition(.opacity) }
                        Spacer()
                        if model.tab != .crop, let h = model.histogram {
                            HistogramView(counts: h).frame(width: 132, height: 60)
                        }
                    }
                    .padding(12)
                    Spacer()
                    if let notice = model.notice {
                        Text(notice).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white).multilineTextAlignment(.center)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 16))
                            .padding(.horizontal, 16).padding(.bottom, 12)
                            .transition(.opacity)
                            .task(id: notice) {
                                try? await Task.sleep(nanoseconds: 4_000_000_000)
                                if model.notice == notice { model.notice = nil }
                            }
                    } else if let note = model.note { GalleryPill(text: note).padding(.bottom, 12) }
                }
                if model.loading { ProgressView().tint(.white).scaleEffect(1.4) }
                if let f = model.failure {
                    Text(f).font(.system(size: 15)).foregroundStyle(GalleryStyle.muted).multilineTextAlignment(.center).padding(24)
                }
            }
            .contentShape(Rectangle())
            .gesture(LongPressGesture(minimumDuration: 0.4).sequenced(before: DragGesture(minimumDistance: 0))
                .updating($holding) { value, state, _ in
                    if case .second(true, _) = value { state = true }
                }, including: model.tab == .crop ? .subviews : .all)
            .onAppear {
                model.setViewPoints(geo.size)
                model.setViewSize(geo.size, scale: displayScale)
            }
            .onChange(of: geo.size) { _, s in
                model.setViewPoints(s)
                model.setViewSize(s, scale: displayScale)
            }
        }
    }

    // ---------------------------------------------------------------- the panels

    private var panel: some View {
        ScrollView {
            EditorPanels(model: model).padding(.top, 8).padding(.bottom, 8)
        }
        .frame(height: min(300, UIScreen.main.bounds.height * 0.36))
        .background(GalleryStyle.page)
        .clipShape(UnevenRoundedRectangle(topLeadingRadius: 30, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: 30))
        .disabled(model.loading || model.failure != nil)
    }

    private var tabBar: some View {
        let icons = ["kb_ui_ic_crop_rest", "kb_ui_ic_raw_rest", "kb_ui_ic_styles_rest", "cs_ic_film", "kb_ui_ic_sun_rest", "kb_ui_ic_palette_rest",
                     "kb_ui_ic_effects_rest", "kb_ui_ic_detail_rest", "ui_ic_tag"]
        let names = [L("editor.tab.crop"), L("editor.tab.develop"), L("editor.tab.styles"), L("editor.tab.look"), L("editor.tab.light"),
                     L("editor.tab.colour"), L("editor.tab.effects"), L("editor.tab.detail"), L("editor.tab.mark")]
        return HStack(spacing: 4) {
            ForEach(EditorModel.Tab.allCases, id: \.rawValue) { t in
                let on = t == model.tab
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { model.tab = t }
                } label: {
                    VStack(spacing: 3) {
                        GalleryIcon(name: icons[t.rawValue], size: 22, color: on ? GalleryStyle.accent : GalleryStyle.muted)
                        Text(names[t.rawValue]).font(.system(size: 10.5, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                            .foregroundStyle(on ? GalleryStyle.accent : GalleryStyle.muted)
                    }
                    .frame(maxWidth: .infinity).padding(.top, 8).padding(.bottom, 6)
                    .background(on ? GalleryStyle.thumb : Color.clear, in: RoundedRectangle(cornerRadius: 18))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(names[t.rawValue])
            }
        }
        .padding(.horizontal, 8).padding(.top, 6).padding(.bottom, 14)
        .background(GalleryStyle.page)
    }

    private var exportOverlay: some View {
        ZStack {
            Color.black.opacity(0.8).ignoresSafeArea()
            VStack(spacing: 12) {
                Text(L("editor.saving") + " \(Int((model.exportProgress * 100).rounded())) %")
                    .font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                ProgressView(value: Double(model.exportProgress)).tint(GalleryStyle.accent)
            }
            .padding(20)
            .frame(width: 290)
            .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 30))
        }
    }
}

// ---------------------------------------------------------------- EditPanels

@MainActor
private struct EditorPanels: View {
    let model: EditorModel
    @State private var curveChannel = EditState.curveMaster
    @State private var colourSection = 0
    @State private var hslBand = 0
    @State private var gradeWheel = 0
    @State private var lookSection = 0

    static let bandColours: [Color] = [0xE5484D, 0xF08A24, 0xE8C531, 0x3FB950, 0x2EC4C4, 0x3B7BF6, 0x8E5BE8, 0xD94BC4].map { EditorPanels.rgb($0) }
    static let hueGradient: [Color] = [0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000].map { EditorPanels.rgb($0) }

    static func rgb(_ v: Int) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    var body: some View {
        VStack(spacing: 0) {
            switch model.tab {
            case .crop: crop
            case .develop: EditDevelopPanel(model: model)
            case .styles: styles
            case .look: look
            case .light: light
            case .colour: colour
            case .effects: params([.texture, .clarity, .dehaze, .vignette, .vignetteMid, .grain, .grainSize])
            case .detail:
                params([.sharpen, .sharpenRadius, .lumaNr, .colorNr])
                hint(L("editor.hint.detail"))
            case .mark: EditWatermarkPanel(model: model)
            }
        }
    }

    // ---------------------------------------------------------------- pieces

    static func label(_ p: EditParam) -> String {
        switch p {
        case .exposure: return L("editor.param.exposure")
        case .contrast: return L("editor.param.contrast")
        case .highlights: return L("editor.param.highlights")
        case .shadows: return L("editor.param.shadows")
        case .whites: return L("editor.param.whites")
        case .blacks: return L("editor.param.blacks")
        case .temperature: return L("editor.param.temperature")
        case .tint: return L("editor.param.tint")
        case .vibrance: return L("editor.param.vibrance")
        case .saturation: return L("editor.param.saturation")
        case .texture: return L("editor.param.texture")
        case .clarity: return L("editor.param.clarity")
        case .dehaze: return L("editor.param.dehaze")
        case .vignette: return L("editor.param.vignette")
        case .vignetteMid: return L("editor.param.vignette_mid")
        case .grain: return L("editor.param.grain")
        case .grainSize: return L("editor.param.grain_size")
        case .sharpen: return L("editor.param.sharpen")
        case .sharpenRadius: return L("editor.param.radius")
        case .lumaNr: return L("editor.param.luma_nr")
        case .colorNr: return L("editor.param.color_nr")
        case .curveShadows: return L("editor.param.shadows")
        case .curveDarks: return L("editor.param.darks")
        case .curveLights: return L("editor.param.lights")
        case .curveHighlights: return L("editor.param.curve_highlights")
        case .curveSplit1: return L("editor.param.split_shadows")
        case .curveSplit2: return L("editor.param.split_mid")
        case .curveSplit3: return L("editor.param.split_highlights")
        case .gradeBlend: return L("editor.param.blending")
        case .gradeBalance: return L("editor.param.balance")
        case .lutAmount, .filmAmount: return L("editor.param.strength")
        case .filmGrain: return L("editor.param.grain")
        case .filmExposure: return L("editor.param.exposure")
        case .filmContrast: return L("editor.param.contrast")
        case .filmDir: return L("editor.param.film_dir")
        case .filmPrintEv: return L("editor.param.film_print_ev")
        default: return p.key
        }
    }

    static func bandName(_ b: Int) -> String {
        switch b {
        case 0: return L("editor.band.red")
        case 1: return L("editor.band.orange")
        case 2: return L("editor.band.yellow")
        case 3: return L("editor.band.green")
        case 4: return L("editor.band.aqua")
        case 5: return L("editor.band.blue")
        case 6: return L("editor.band.purple")
        default: return L("editor.band.magenta")
        }
    }

    static func decimal(_ v: Float, _ digits: Int) -> String {
        let s = String(format: "%.\(digits)f", v)
        return s.replacingOccurrences(of: ".", with: Locale.current.decimalSeparator ?? ".")
    }

    private func slider(_ p: EditParam, _ label: String? = nil, gradient: [Color]? = nil) -> some View {
        var format: (Float) -> String = { v in
            let i = EditParam.javaRound(v)
            return i > 0 ? "+\(i)" : "\(i)"
        }
        switch p {
        case .exposure: format = { v in (v > 0 ? "+" : "") + Self.decimal(v, 2) + " EV" }
        case .temperature, .tint: format = { [model] v in model.wbText(p, v) }
        case .gradeShHue, .gradeMidHue, .gradeHiHue, .gradeAllHue: format = { v in "\(EditParam.javaRound(v))°" }
        case .sharpenRadius: format = { v in Self.decimal(v, 1) }
        case .filmExposure: format = { v in (v > 0 ? "+" : "") + Self.decimal(v, 1) + " EV" }
        case .filmPrintEv: format = { v in (v > 0 ? "+" : "") + Self.decimal(v, 2) + " EV" }
        case .lutAmount, .filmAmount, .filmGrain, .filmDir: format = { v in "\(EditParam.javaRound(v)) %" }
        default: break
        }
        let hue = [EditParam.gradeShHue, .gradeMidHue, .gradeHiHue, .gradeAllHue].contains(p)
        return EditSliderRow(label: label ?? Self.label(p), min: p.min, max: p.max, def: p.def, step: p.step, bipolar: p.bipolar,
                             value: model.state[p], gradient: hue ? Self.hueGradient : gradient, format: format) { v, commit in
            model.set(p, v, commit: commit)
        }
    }

    private func params(_ list: [EditParam]) -> some View {
        ForEach(list, id: \.rawValue) { p in slider(p) }
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.system(size: 12)).foregroundStyle(GalleryStyle.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18).padding(.top, 4).padding(.bottom, 8)
    }

    private func chipRow(_ chips: [(String, Bool, () -> Void)]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(chips.indices, id: \.self) { i in GalleryChip(text: chips[i].0, on: chips[i].1, action: chips[i].2) }
            }
            .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 6)
        }
    }

    // ---------------------------------------------------------------- Кадр

    private var crop: some View {
        VStack(spacing: 0) {
            chipRow(aspectChips)
            HStack(spacing: 0) {
                actionButton("kb_ui_ic_rotate_rest", L("editor.rotate")) { model.rotate() }
                actionButton("kb_ui_ic_mirror_rest", L("editor.flip")) { model.flip() }
                actionButton("kb_ui_ic_backup_rest", L("editor.reset_crop")) { model.resetCrop() }
            }
            .padding(.horizontal, 8)
            EditSliderRow(label: L("editor.straighten"), min: -EditState.maxStraighten, max: EditState.maxStraighten, def: 0, step: 0.1,
                          bipolar: true, value: model.state.straighten, format: { v in (v > 0 ? "+" : "") + Self.decimal(v, 1) + "°" }) { v, commit in
                model.straighten(v, commit: commit)
            }
        }
    }

    private var aspectChips: [(String, Bool, () -> Void)] {
        let on = CropMath.presetIndex(model.state.aspect)
        var chips: [(String, Bool, () -> Void)] = []
        for i in CropMath.presets.indices {
            let name = i == 0 ? L("editor.aspect.free") : i == 1 ? L("editor.aspect.original") : CropMath.presetRatios[i]
            chips.append((name, i == on, { model.aspect(CropMath.presets[i]) }))
        }
        return chips
    }

    private func actionButton(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                GalleryIcon(name: icon, size: 24)
                Text(label).font(.system(size: 12, weight: .medium)).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, minHeight: 64)
            .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 16))
            .padding(4)
        }
        .buttonStyle(.plain)
    }

    // ---------------------------------------------------------------- Стили

    private var styleChips: [(String, Bool, () -> Void)] {
        var chips: [(String, Bool, () -> Void)] = [(L("editor.style.none"), model.styleIndex < 0, {
            model.style(-1, amount: 0, commit: true)
            model.styleIndex = -1
        })]
        for i in EditPresets.styles.indices {
            chips.append((EditPresets.name(i), i == model.styleIndex, {
                model.styleIndex = i
                model.style(i, amount: model.styleAmount, commit: true)
            }))
        }
        return chips
    }

    private var styles: some View {
        VStack(spacing: 0) {
            chipRow(styleChips)
            EditSliderRow(label: L("editor.amount"), min: 0, max: 100, def: 100, step: 1, bipolar: false, value: model.styleAmount * 100,
                          format: { v in "\(EditParam.javaRound(v)) %" }) { v, commit in
                model.styleAmount = v / 100
                if model.styleIndex >= 0 { model.style(model.styleIndex, amount: model.styleAmount, commit: commit) }
            }
            hint(L("editor.hint.styles"))
        }
    }

    // ---------------------------------------------------------------- P619 «LUT»

    /// EditPanels.look: the camera's LUTs and its film stocks as cards with the photo through each, under the segmented control;
    /// the strength, and for a film its print, format, grain, exposure, contrast, couplers and print exposure (only what acts on it)
    private var look: some View {
        let luts = FilmCatalog.luts(), stocks = [""] + FilmCatalog.stocks.map(\.id)
        return VStack(spacing: 0) {
            GallerySegments(names: ["LUT", L("editor.look.film")], selected: lookSection) { lookSection = $0 }
            if lookSection == 0 {
                lookCards(luts.map { v -> (String, String, String, Bool) in (v, FilmCatalog.lutName(v), "lut|" + v, v == model.state.lut) }) {
                    model.setLut($0)
                }
                slider(.lutAmount)
                hint(L("editor.hint.lut"))
            } else {
                lookCards(stocks.map { id -> (String, String, String, Bool) in
                    (id, FilmCatalog.stock(id)?.name ?? L("editor.look.no_film"), "film|" + id, id == model.state.film)
                }) { model.setFilm($0) }
                if let st = FilmCatalog.stock(model.state.film) { filmParams(st) }
                hint(L("editor.hint.film"))
            }
        }
        .onAppear { model.loadLookThumbs(luts: luts, stocks: stocks) }
    }

    private func lookCards(_ cards: [(String, String, String, Bool)], pick: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(cards, id: \.0) { card in
                    Button { pick(card.0) } label: {
                        VStack(spacing: 4) {
                            ZStack {
                                Color.black
                                if let image = model.lookThumbs[card.2] { Image(uiImage: image).resizable().scaledToFill() }
                            }
                            .frame(width: 53, height: 53).clipShape(RoundedRectangle(cornerRadius: 14))
                            Text(card.1).font(.system(size: 11, weight: .semibold)).foregroundStyle(.white).lineLimit(2)
                                .multilineTextAlignment(.center).frame(width: 64)
                        }
                        .padding(5)
                        .background(card.3 ? Self.rgb(0x3A3A3C) : Color.clear, in: RoundedRectangle(cornerRadius: 18))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(card.1)
                }
            }
            .padding(.horizontal, 14).padding(.top, 8).padding(.bottom, 6)
        }
    }

    /// EditPanels.filmParams: the chosen stock's controls (LutTray.filmParams)
    @ViewBuilder private func filmParams(_ st: FilmCatalog.Stock) -> some View {
        slider(.filmAmount)
        if !st.reversal {
            GallerySection(text: L("editor.look.print"))
            let on = SCAMFilm.print(stored: model.state.filmPrint, stock: st.id)
            chipRow(FilmCatalog.prints.map { p -> (String, Bool, () -> Void) in (p.name, p.id == on, { model.setFilmPrint(p.id) }) })
        }
        GallerySection(text: L("editor.look.format"))
        let format = SCAMFilm.format(stored: model.state.filmFormat)
        chipRow(FilmCatalog.formats.map { f -> (String, Bool, () -> Void) in (FilmCatalog.formatName(f), f.id == format, { model.setFilmFormat(f.id) }) })
        slider(.filmGrain)
        slider(.filmExposure)
        if st.measured {
            slider(.filmContrast)
            slider(.filmDir)
        }
        if st.type != 2 && st.type != 4 { slider(.filmPrintEv) }
    }

    // ---------------------------------------------------------------- Свет

    private var light: some View {
        VStack(spacing: 0) {
            params([.exposure, .contrast, .highlights, .shadows, .whites, .blacks])
            GallerySection(text: L("editor.curve"))
            GallerySegments(names: ["RGB", L("editor.band.red"), L("editor.band.green"), L("editor.band.blue")], selected: curveChannel) {
                curveChannel = $0
            }
            CurveEditor(points: model.state.curves[curveChannel], colour: [Color.white, Self.rgb(0xFF5A5F), Self.rgb(0x4CD964),
                                                                            Self.rgb(0x4A8CFF)][curveChannel]) { pts, done in
                model.setCurve(curveChannel, pts, done: done)
            }
            .frame(height: 220)
            .id(curveChannel)
            hint(L("editor.hint.curve"))
            params([.curveHighlights, .curveLights, .curveDarks, .curveShadows, .curveSplit1, .curveSplit2, .curveSplit3])
        }
    }

    // ---------------------------------------------------------------- Цвет

    private var colour: some View {
        VStack(spacing: 0) {
            GallerySegments(names: [L("editor.colour.basic"), L("editor.colour.mixer"), L("editor.colour.grading"), L("editor.colour.bw")],
                            selected: colourSection) { colourSection = $0 }
            switch colourSection {
            case 1: mixer
            case 2: grading
            case 3: mono
            default:
                slider(.temperature, gradient: [Self.rgb(0x4A8CFF), Self.rgb(0xDDDDDD), Self.rgb(0xFFB13B)])
                slider(.tint, gradient: [Self.rgb(0x4CD964), Self.rgb(0xDDDDDD), Self.rgb(0xE24BD8)])
                slider(.vibrance)
                slider(.saturation)
            }
        }
    }

    private var mixer: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                ForEach(0..<8, id: \.self) { b in
                    Circle().fill(Self.bandColours[b]).frame(width: 30, height: 30)
                        .overlay(Circle().stroke(Color.white, lineWidth: b == hslBand ? 3 : 0))
                        .onTapGesture { hslBand = b }
                        .accessibilityLabel(Self.bandName(b))
                        .accessibilityAddTraits(.isButton)
                }
            }
            .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 4)
            let n = Self.bandName(hslBand)
            slider(.hue(hslBand), L("editor.hue") + " · " + n)
            slider(.sat(hslBand), L("editor.param.saturation") + " · " + n)
            slider(.lum(hslBand), L("editor.luminance") + " · " + n)
        }
    }

    private var grading: some View {
        VStack(spacing: 0) {
            GallerySegments(names: [L("editor.param.shadows"), L("editor.midtones"), L("editor.param.highlights"), L("editor.global")],
                            selected: gradeWheel) { gradeWheel = $0 }
            slider(.grade(gradeWheel, 0), L("editor.hue"))
            slider(.grade(gradeWheel, 1), L("editor.param.saturation"))
            slider(.grade(gradeWheel, 2), L("editor.luminance"))
            slider(.gradeBlend)
            slider(.gradeBalance)
        }
    }

    private var mono: some View {
        VStack(spacing: 0) {
            GallerySegments(names: [L("editor.colour.colour"), L("editor.colour.bw")], selected: model.state.mono ? 1 : 0) { k in
                model.set(.mono, k == 1 ? 1 : 0, commit: true)
            }
            if model.state.mono {
                ForEach(0..<8, id: \.self) { b in
                    slider(.bw(b), Self.bandName(b), gradient: [Self.rgb(0x202020), Self.bandColours[b], Self.rgb(0xF0F0F0)])
                }
            }
        }
    }
}

// ---------------------------------------------------------------- CurveView

/// CurveView: the point curve of one channel - a touch near a point drags it (between its neighbours), a touch elsewhere adds a
/// point on the curve there, a double tap on a point removes it (the end points stay); drawn as EditMath.curveAt evaluates it.
@MainActor
private struct CurveEditor: View {
    let points: [Float]?
    let colour: Color
    let changed: ([Float]?, Bool) -> Void
    @State private var pts: [[Float]] = []
    @State private var drag = -1
    @State private var started = false

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height) - 12
            let l = (geo.size.width - side) / 2, t = (geo.size.height - side) / 2
            Canvas { c, _ in
                var grid = Path()
                for i in 0...4 {
                    let k = CGFloat(i) / 4
                    grid.move(to: CGPoint(x: l + side * k, y: t)); grid.addLine(to: CGPoint(x: l + side * k, y: t + side))
                    grid.move(to: CGPoint(x: l, y: t + side * k)); grid.addLine(to: CGPoint(x: l + side, y: t + side * k))
                }
                grid.move(to: CGPoint(x: l, y: t + side)); grid.addLine(to: CGPoint(x: l + side, y: t))
                c.stroke(grid, with: .color(.white.opacity(0.2)), lineWidth: 1)
                let flat = Self.flat(pts)
                var line = Path()
                for i in 0...64 {
                    let x = Float(i) / 64, y = Self.curveAt(flat, x)
                    let p = CGPoint(x: l + CGFloat(x) * side, y: t + CGFloat(1 - y) * side)
                    if i == 0 { line.move(to: p) } else { line.addLine(to: p) }
                }
                c.stroke(line, with: .color(colour), lineWidth: 2)
                for q in pts {
                    let p = CGPoint(x: l + CGFloat(q[0]) * side, y: t + CGFloat(1 - q[1]) * side)
                    c.fill(Path(ellipseIn: CGRect(x: p.x - 6, y: p.y - 6, width: 12, height: 12)), with: .color(colour))
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { g in
                    let x = Float(max(0, min(1, (g.location.x - l) / side))), y = Float(max(0, min(1, 1 - (g.location.y - t) / side)))
                    if !started {
                        started = true
                        drag = nearest(g.startLocation, l, t, side)
                        if drag < 0 && pts.count < 12 {
                            var at = 0
                            while at < pts.count && pts[at][0] < x { at += 1 }
                            if at > 0 && at < pts.count {
                                pts.insert([x, Self.curveAt(Self.flat(pts), x)], at: at)
                                drag = at
                            }
                        }
                        return
                    }
                    guard drag >= 0 else { return }
                    let lo: Float = drag == 0 ? 0 : pts[drag - 1][0] + 0.02, hi: Float = drag == pts.count - 1 ? 1 : pts[drag + 1][0] - 0.02
                    pts[drag][0] = max(lo, min(hi, x))
                    pts[drag][1] = y
                    changed(Self.points(pts), false)
                }
                .onEnded { _ in
                    if drag >= 0 { changed(Self.points(pts), true) }
                    drag = -1
                    started = false
                })
            .simultaneousGesture(SpatialTapGesture(count: 2).onEnded { e in
                let i = nearest(e.location, l, t, side)
                if i > 0 && i < pts.count - 1 {
                    pts.remove(at: i)
                    drag = -1
                    changed(Self.points(pts), true)
                }
            })
        }
        .onAppear { load() }
        .onChange(of: points) { _, _ in if drag < 0 { load() } }
    }

    private func load() {
        if let p = points, p.count >= 4 { pts = stride(from: 0, to: p.count - 1, by: 2).map { [p[$0], p[$0 + 1]] } } else { pts = [[0, 0], [1, 1]] }
    }

    private func nearest(_ p: CGPoint, _ l: CGFloat, _ t: CGFloat, _ side: CGFloat) -> Int {
        var best: CGFloat = 28, idx = -1
        for (i, q) in pts.enumerated() {
            let d = hypot(l + CGFloat(q[0]) * side - p.x, t + CGFloat(1 - q[1]) * side - p.y)
            if d < best { best = d; idx = i }
        }
        return idx
    }

    static func flat(_ pts: [[Float]]) -> [Float] { pts.flatMap { $0 } }

    static func points(_ pts: [[Float]]) -> [Float]? {
        if pts.count == 2 && pts[0] == [0, 0] && pts[1] == [1, 1] { return nil }
        return flat(pts)
    }

    /// EditMath.curveAt: the monotone cubic (Fritsch-Carlson) through the points
    static func curveAt(_ pts: [Float], _ x: Float) -> Float {
        if pts.count < 4 { return x }
        let n = pts.count / 2
        if x <= pts[0] { return pts[1] }
        if x >= pts[2 * (n - 1)] { return pts[2 * n - 1] }
        var d = [Float](repeating: 0, count: n - 1), m = [Float](repeating: 0, count: n)
        for i in 0..<(n - 1) {
            let dx = max(1e-6, pts[2 * i + 2] - pts[2 * i])
            d[i] = (pts[2 * i + 3] - pts[2 * i + 1]) / dx
        }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        if n > 2 { for i in 1..<(n - 1) { m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2 } }
        for i in 0..<(n - 1) {
            if d[i] == 0 { m[i] = 0; m[i + 1] = 0; continue }
            let a = m[i] / d[i], b = m[i + 1] / d[i], h = a * a + b * b
            if h > 9 {
                let tau = 3 / h.squareRoot()
                m[i] = tau * a * d[i]
                m[i + 1] = tau * b * d[i]
            }
        }
        var k = 0
        while k < n - 2 && x > pts[2 * k + 2] { k += 1 }
        let x0 = pts[2 * k], x1 = pts[2 * k + 2], y0 = pts[2 * k + 1], y1 = pts[2 * k + 3], h = max(1e-6, x1 - x0)
        let t = (x - x0) / h, t2 = t * t, t3 = t2 * t
        let v1 = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m[k]
        let v2 = (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * h * m[k + 1]
        return max(0, min(1, v1 + v2))
    }
}

// ---------------------------------------------------------------- the crop frame

/// CropOverlayView: the frame over the crop tab's picture (the whole turned photo, the canvas of EditorView), corner handles,
/// thirds while dragging; a corner or an edge resizes (keeping a chosen ratio), a drag inside moves, a pinch scales about the
/// centre; every move stops at the edge of the straightened photo (CropMath.constrain).
@MainActor
private struct CropOverlay: View {
    let model: EditorModel
    let size: CGSize
    @State private var start: [Float]?
    @State private var target = -1
    @State private var active = false
    @State private var pinchStart: [Float]?

    private var canvas: CGRect {
        CropMath.canvas(Double(size.width), Double(size.height), Double(EditorModel.margin(true)), model.state,
                        Double(model.orientedSize.width), Double(model.orientedSize.height), true)
    }
    private var base: (Double, Double) {
        CropMath.baseSize(Double(model.orientedSize.width), Double(model.orientedSize.height), model.state.quarterTurns)
    }
    private var ratio: Double { CropMath.ratioOf(model.state.aspect, base.0, base.1) }
    private var rect: [Float] { [model.state.cropL, model.state.cropT, model.state.cropR, model.state.cropB] }

    var body: some View {
        let c = canvas
        let r = rect
        let l = c.minX + CGFloat(r[0]) * c.width, t = c.minY + CGFloat(r[1]) * c.height
        let rr = c.minX + CGFloat(r[2]) * c.width, b = c.minY + CGFloat(r[3]) * c.height
        ZStack {
            Canvas { g, _ in
                g.stroke(Path(CGRect(x: l, y: t, width: rr - l, height: b - t)), with: .color(.white), lineWidth: 1.5)
                if active {
                    var grid = Path()
                    for i in 1..<3 {
                        let x = l + (rr - l) * CGFloat(i) / 3, y = t + (b - t) * CGFloat(i) / 3
                        grid.move(to: CGPoint(x: x, y: t)); grid.addLine(to: CGPoint(x: x, y: b))
                        grid.move(to: CGPoint(x: l, y: y)); grid.addLine(to: CGPoint(x: rr, y: y))
                    }
                    g.stroke(grid, with: .color(.white.opacity(0.6)), lineWidth: 0.8)
                }
                let k: CGFloat = 18
                var h = Path()
                for (px, py, dx, dy) in [(l, t, 1.0, 1.0), (rr, t, -1.0, 1.0), (rr, b, -1.0, -1.0), (l, b, 1.0, -1.0)] {
                    h.move(to: CGPoint(x: px + k * CGFloat(dx), y: py)); h.addLine(to: CGPoint(x: px, y: py))
                    h.addLine(to: CGPoint(x: px, y: py + k * CGFloat(dy)))
                }
                g.stroke(h, with: .color(.white), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
            }
            .allowsHitTesting(false)
            Color.clear.contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        if start == nil {
                            start = rect
                            target = hit(g.startLocation, c)
                            active = true
                        }
                        guard pinchStart == nil, let s = start else { return }
                        let du = Float(g.translation.width / max(1, c.width)), dv = Float(g.translation.height / max(1, c.height))
                        apply(moved(s, target, du, dv), done: false)
                    }
                    .onEnded { _ in
                        start = nil
                        active = false
                        model.crop(rect, done: true)
                    })
                .simultaneousGesture(MagnifyGesture()
                    .onChanged { g in
                        if pinchStart == nil { pinchStart = rect }
                        if let s = pinchStart { apply(scaled(s, Float(1 / max(0.01, g.magnification))), done: false) }
                    }
                    .onEnded { _ in
                        pinchStart = nil
                        model.crop(rect, done: true)
                    })
        }
    }

    private func apply(_ candidate: [Float], done: Bool) {
        let c = CropMath.constrain(rect, candidate, base.0, base.1, Double(model.state.straighten))
        model.crop(c, done: done)
    }

    private func hit(_ p: CGPoint, _ c: CGRect) -> Int {
        let r = rect
        let l = c.minX + CGFloat(r[0]) * c.width, t = c.minY + CGFloat(r[1]) * c.height
        let rr = c.minX + CGFloat(r[2]) * c.width, b = c.minY + CGFloat(r[3]) * c.height
        let corners = [(l, t), (rr, t), (rr, b), (l, b)]
        for (i, q) in corners.enumerated() where hypot(p.x - q.0, p.y - q.1) < 30 { return i }
        if p.y > t && p.y < b {
            if abs(p.x - l) < 22 { return 4 }
            if abs(p.x - rr) < 22 { return 6 }
        }
        if p.x > l && p.x < rr {
            if abs(p.y - t) < 22 { return 5 }
            if abs(p.y - b) < 22 { return 7 }
        }
        return 8
    }

    private func scaled(_ r: [Float], _ k: Float) -> [Float] {
        let cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2
        let hw = max((r[2] - r[0]) / 2 * k, 0.04), hh = max((r[3] - r[1]) / 2 * k, 0.04)
        return [cx - hw, cy - hh, cx + hw, cy + hh]
    }

    private func moved(_ r: [Float], _ target: Int, _ du: Float, _ dv: Float) -> [Float] {
        var l = r[0], t = r[1], rr = r[2], b = r[3]
        let minSize: Float = 0.08
        if target == 8 { return [l + du, t + dv, rr + du, b + dv] }
        let left = target == 0 || target == 3 || target == 4, right = target == 1 || target == 2 || target == 6
        let top = target == 0 || target == 1 || target == 5, bottom = target == 2 || target == 3 || target == 7
        if left { l = min(l + du, rr - minSize) }
        if right { rr = max(rr + du, l + minSize) }
        if top { t = min(t + dv, b - minSize) }
        if bottom { b = max(b + dv, t + minSize) }
        if ratio > 0 {
            let wpx = Double(rr - l) * base.0, hpx = Double(b - t) * base.1
            if target >= 4 {
                if left || right {
                    let nh = Float(wpx / ratio / base.1), cy = (r[1] + r[3]) / 2
                    t = cy - nh / 2
                    b = cy + nh / 2
                } else {
                    let nw = Float(hpx * ratio / base.0), cx = (r[0] + r[2]) / 2
                    l = cx - nw / 2
                    rr = cx + nw / 2
                }
            } else {
                let nh = Float(wpx / ratio / base.1)
                if top { t = b - nh } else { b = t + nh }
            }
        }
        return [l, t, rr, b]
    }
}

// ---------------------------------------------------------------- the histogram

/// EditHistogramView: R, G, B square-root counts filled and added up, on the pill colour with 10pt corners.
struct HistogramView: View {
    let counts: [[Int]]
    var body: some View {
        Canvas { c, size in
            var maxV = 1.0
            for ch in counts { for i in 1..<255 where i < ch.count { maxV = max(maxV, Double(ch[i]).squareRoot()) } }
            let colours: [Color] = [Color(red: 1, green: 0.23, blue: 0.19), Color(red: 0.2, green: 0.78, blue: 0.35), Color(red: 0.18, green: 0.42, blue: 1)]
            let pad: CGFloat = 4, w = size.width, h = size.height
            c.blendMode = .plusLighter
            for k in 0..<min(3, counts.count) {
                var p = Path()
                p.move(to: CGPoint(x: pad, y: h - pad))
                for i in 0..<min(256, counts[k].count) {
                    let x = pad + (w - 2 * pad) * CGFloat(i) / 255
                    let y = h - pad - (h - 2 * pad) * CGFloat(min(1, Double(counts[k][i]).squareRoot() / maxV))
                    p.addLine(to: CGPoint(x: x, y: y))
                }
                p.addLine(to: CGPoint(x: w - pad, y: h - pad))
                p.closeSubpath()
                c.fill(p, with: .color(colours[k].opacity(0.78)))
            }
        }
        .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 10))
        .allowsHitTesting(false)
    }
}
