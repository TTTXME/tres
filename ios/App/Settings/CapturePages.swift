import SwiftUI

/// The camera the settings were opened from (nil when the presenter passes none): the format and size cards switch it as the
/// camera's own «Формат | Размер» sheet does; without it they show what is stored.
private struct SettingsCameraKey: EnvironmentKey {
    static let defaultValue: CameraModel? = nil
}

extension EnvironmentValues {
    var settingsCamera: CameraModel? {
        get { self[SettingsCameraKey.self] }
        set { self[SettingsCameraKey.self] = newValue }
    }
}

/// FormatPickerPreference: «Фото» and «RAW (DNG)» as two segmented rows, as the camera's sheet draws them, then what is saved.
/// The photo codec is «Формат фото» (pref_photo_format: jpeg / heic - the codecs the iPhone writes; Android also offers WebP and
/// AVIF); RAW is pref_save_raw_key (0 the photo, 1 RAW + photo, 2 RAW only), with the iPhone's RAW kind (Bayer RAW / ProRAW).
struct FormatPickerCard: View {
    @Environment(\.settingsCamera) private var camera
    private var store: SettingsStore { SettingsStore.shared }

    private var storedKind: RawChoice {
        let kind = RawChoice(rawValue: UserDefaults.standard.string(forKey: CameraModel.rawKindKey) ?? "") ?? .bayer
        return kind == .off ? .bayer : kind
    }

    var body: some View {
        let format = store.string("pref_photo_format", "jpeg") == "heic" ? "heic" : "jpeg"
        let mode = min(2, max(0, Int(store.string(CameraModel.saveRawKey, "0")) ?? 0))
        let kind = camera?.rawKind ?? storedKind
        VStack(alignment: .leading, spacing: 8) {
            Text(Lang.t("Фото", "Photo")).font(.caption).foregroundStyle(.secondary)
            Picker("", selection: Binding(get: { format }, set: { store.set("pref_photo_format", $0) })) {
                Text("JPEG").tag("jpeg")
                Text("HEIC").tag("heic")
            }
            .pickerStyle(.segmented)
            .disabled(mode == 2)
            Text("RAW (DNG)").font(.caption).foregroundStyle(.secondary)
            Picker("", selection: Binding(get: { mode }, set: { v in
                if let camera { camera.setRawSaving(v) } else { store.set(CameraModel.saveRawKey, String(v)) }
            })) {
                Text(L("sheet.raw_none")).tag(0)
                Text("+ RAW").tag(1)
                Text(L("sheet.raw_only")).tag(2)
            }
            .pickerStyle(.segmented)
            Text(L("sheet.raw_kind")).font(.caption).foregroundStyle(.secondary)
            Picker("", selection: Binding(get: { kind }, set: { v in
                if let camera { camera.setRawKind(v) } else { UserDefaults.standard.set(v.rawValue, forKey: CameraModel.rawKindKey) }
            })) {
                Text(RawChoice.bayer.title).tag(RawChoice.bayer)
                Text(RawChoice.proRAW.title).tag(RawChoice.proRAW)
            }
            .pickerStyle(.segmented)
            .disabled(mode == 0)
            Text(Lang.t("Сохраняется: ", "Saved: ") + (mode == 2 ? "DNG (" + kind.title + ")"
                                                     : format.uppercased() + (mode == 0 ? "" : " + DNG (" + kind.title + ")")))
                .font(.footnote).foregroundStyle(.secondary)
            Text(Lang.t("Качество", "Quality")).font(.footnote.weight(.semibold)).foregroundStyle(Color.accentColor).padding(.top, 8)
        }
        .padding(.vertical, 4)
    }
}

/// SizePickerPreference: the sizes of the camera's current lens as radio rows - «Обычный · 12 Мп» and «Подробный»: the lens's full
/// resolution (the iPhone's 48 MP where the lens has it; Android: 50 / 200 MP FULLRES). A size the lens lacks is dimmed.
struct SizePickerCard: View {
    @Environment(\.settingsCamera) private var camera

    private var storedFull: Bool { UserDefaults.standard.object(forKey: "full_resolution") as? Bool ?? true }

    var body: some View {
        let full = camera?.fullResolution ?? storedFull
        let fullOffered = camera?.supportsFullResolution ?? true
        VStack(alignment: .leading, spacing: 0) {
            row(Lang.t("Обычный · 12 Мп", "Normal · 12 MP"), Lang.t("Быстрее, меньше файл", "Faster, a smaller file"),
                on: !full || !fullOffered, enabled: camera != nil) { setFull(false) }
            Divider().padding(.leading, 34)
            row(Lang.t("Подробный · полное разрешение", "Detailed · full resolution"),
                fullOffered ? Lang.t("48 Мп там, где объектив их даёт", "48 MP where the lens has them")
                    : Lang.t("Этот объектив не снимает в полном разрешении", "This lens has no full resolution"),
                on: full && fullOffered, enabled: camera != nil && fullOffered) { setFull(true) }
            if camera == nil {
                Text(L("set.size.in_camera")).font(.app(12)).foregroundStyle(.secondary).padding(.top, 6)
            }
        }
        .buttonStyle(.borderless)
    }

    private func setFull(_ on: Bool) {
        guard let camera else { return }
        Task { await camera.setFullResolution(on) }
    }

    private func row(_ title: String, _ sub: String, on: Bool, enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: on ? "largecircle.fill.circle" : "circle").foregroundStyle(on ? Color.accentColor : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    Text(sub).font(.app(12)).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(.vertical, 8)
        }
        .disabled(!enabled)
        .opacity(enabled || on ? 1 : 0.5)
    }
}

/// ShadeTransparencyPreference: a preview (a scene, the quick panel over its lower 62 % at the chosen transparency) and the slider
/// over 0 / 20 / 30 / 40 / 50 / 60 % (0 the default); stored as the percentage text (pref_shade_transparency).
struct ShadeTransparencyCard: View {
    static let values = [0, 20, 30, 40, 50, 60]
    static let key = "pref_shade_transparency", defaultValue = 0
    private var store: SettingsStore { SettingsStore.shared }

    private var value: Int {
        Swift.max(0, Swift.min(80, Int(PrefValue.number(store.values[Self.key], Double(Self.defaultValue)).rounded())))
    }

    static func index(_ percent: Int) -> Int {
        var best = 0
        for i in 1..<values.count where abs(values[i] - percent) < abs(values[best] - percent) { best = i }
        return best
    }

    var body: some View {
        let now = value
        VStack(alignment: .leading, spacing: 12) {
            preview(alpha: 1 - Double(now) / 100).frame(height: 150)
            HStack {
                Text(Lang.t("Прозрачность", "Transparency")).font(.app(17, 600))
                Spacer()
                Text("\(now) %").font(.app(15).bold()).foregroundStyle(Color.accentColor)
                    .padding(.horizontal, 12).padding(.vertical, 4).background(Color.accentColor.opacity(0.15), in: Capsule())
            }
            HStack {
                Text("\(Self.values.first!) %").font(.app(12)).foregroundStyle(.secondary)
                Slider(value: Binding(get: { Double(Self.index(now)) },
                                      set: { store.set(Self.key, String(Self.values[Swift.max(0, Swift.min(Self.values.count - 1, Int($0.rounded())))])) }),
                       in: 0...Double(Self.values.count - 1), step: 1)
                    .accessibilityLabel(Lang.t("Прозрачность шторки", "Panel transparency"))
                Text("\(Self.values.last!) %").font(.app(12)).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
    }

    private func preview(alpha: Double) -> some View {
        Canvas { ctx, size in
            let w = size.width, h = size.height
            ctx.fill(Path(CGRect(origin: .zero, size: size)),
                     with: .linearGradient(Gradient(colors: [Color(red: 0.36, green: 0.55, blue: 0.78), Color(red: 0.93, green: 0.71, blue: 0.45),
                                                             Color(red: 0.2, green: 0.36, blue: 0.22)]),
                                           startPoint: .zero, endPoint: CGPoint(x: w, y: h)))
            let top = h * 0.38
            ctx.fill(Path(roundedRect: CGRect(x: 0, y: top, width: w, height: h - top + 18), cornerRadius: 18),
                     with: .color(Color(red: 0.11, green: 0.11, blue: 0.118).opacity(alpha)))
            ctx.draw(Text(Lang.t("Быстрые настройки", "Quick settings")).font(.app(12, 600)).foregroundStyle(.white),
                     at: CGPoint(x: 10, y: top + 16), anchor: .leading)
            let gap: CGFloat = 5, tw = (w - 20 - 3 * gap) / 4, th: CGFloat = 28, y0 = top + 30
            for k in 0..<8 {
                let r = CGRect(x: 10 + CGFloat(k % 4) * (tw + gap), y: y0 + CGFloat(k / 4) * (th + gap), width: tw, height: th)
                ctx.fill(Path(roundedRect: r, cornerRadius: 9), with: .color(Color(red: 0.1, green: 0.12, blue: 0.133).opacity(alpha)))
                ctx.stroke(Path(roundedRect: r, cornerRadius: 9), with: .color(.white.opacity(0.15)), lineWidth: 1)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.15), lineWidth: 1))
    }
}

/// RawCrop / RawCropView.compute (processing/RawCrop.java): the crop the next shot makes - per side the larger of the zero padding
/// the automatic crop finds (a shot's measurement; none on an iPhone) and the own margin of the upright photo, the kept area on
/// whole colour periods with even sides. Without a measured shot: a 4:3 sketch of a 4096x3072 sensor mounted at 90 degrees.
enum RawCropMath {
    static let period = 8, maxMargin = 512
    static let sideKeys = ["pref_raw_crop_left", "pref_raw_crop_top", "pref_raw_crop_right", "pref_raw_crop_bottom"]

    struct State {
        let width, height, keptWidth, keptHeight: Int
        let cut: [Int]
        let autoOn: Bool
    }

    /// Math.round(orientation / 90f), wrapped to 0..3
    static func quarterTurns(_ orientation: Int) -> Int { ((Int((Float(orientation) / 90 + 0.5).rounded(.down)) % 4) + 4) % 4 }
    static func photoToSensor(_ photo: [Int], _ o: Int) -> [Int] { let k = quarterTurns(o); return (0..<4).map { photo[($0 + k) % 4] } }
    static func sensorToPhoto(_ sensor: [Int], _ o: Int) -> [Int] { let k = quarterTurns(o); return (0..<4).map { sensor[($0 - k + 4) % 4] } }

    /// RawCrop.keep: the kept area of a w x h frame, nil when nothing is cut or less than half of a side would stay
    static func keep(_ w: Int, _ h: Int, _ sides: [Int]) -> (x: Int, y: Int, width: Int, height: Int)? {
        let left = max(0, sides[0]), top = max(0, sides[1]), right = max(0, sides[2]), bottom = max(0, sides[3])
        if left == 0 && top == 0 && right == 0 && bottom == 0 { return nil }
        let x = (left + period - 1) / period * period, y = (top + period - 1) / period * period
        let width = (w - right - x) & ~1, height = (h - bottom - y) & ~1
        if width < w / 2 || height < h / 2 { return nil }
        if x == 0 && y == 0 && width == w && height == h { return nil }
        return (x, y, width, height)
    }

    static func compute(autoOn: Bool, manualPhoto: [Int]) -> State {
        let w = 4096, h = 3072, orientation = 90
        let manual = photoToSensor(manualPhoto, orientation)
        let area = keep(w, h, manual)
        let cutSensor = area.map { [$0.x, $0.y, w - $0.x - $0.width, h - $0.y - $0.height] } ?? [0, 0, 0, 0]
        let turned = quarterTurns(orientation) % 2 == 1
        let kw = area?.width ?? w, kh = area?.height ?? h
        return State(width: turned ? h : w, height: turned ? w : h, keptWidth: turned ? kh : kw, keptHeight: turned ? kw : kh,
                     cut: sensorToPhoto(cutSensor, orientation), autoOn: autoOn)
    }

    static func margins(_ store: SettingsStore) -> [Int] {
        sideKeys.map { max(0, min(maxMargin, Int(PrefValue.number(store.values[$0], 0).rounded()))) }
    }
}

/// RawCropPreference: the picture of the crop - the frame, the cut bands hatched, the kept frame; a dragged edge of the kept
/// frame sets that side's margin (stored through its slider's key when the finger lifts).
struct RawCropCard: View {
    let enabled: Bool
    private var store: SettingsStore { SettingsStore.shared }
    @State private var dragSide: Int?
    @State private var dragValue = 0

    var body: some View {
        var margins = RawCropMath.margins(store)
        if let dragSide { margins[dragSide] = dragValue }
        let state = RawCropMath.compute(autoOn: store.bool("pref_raw_crop_auto", true), manualPhoto: margins)
        return VStack(spacing: 8) {
            GeometryReader { geo in
                let scale = min(geo.size.width / CGFloat(state.width), geo.size.height / CGFloat(state.height))
                let fw = CGFloat(state.width) * scale, fh = CGFloat(state.height) * scale
                let frame = CGRect(x: (geo.size.width - fw) / 2, y: (geo.size.height - fh) / 2, width: fw, height: fh)
                let kept = CGRect(x: frame.minX + CGFloat(state.cut[0]) * scale, y: frame.minY + CGFloat(state.cut[1]) * scale,
                                  width: CGFloat(state.keptWidth) * scale, height: CGFloat(state.keptHeight) * scale)
                Canvas { ctx, _ in
                    ctx.fill(Path(frame), with: .color(Color.red.opacity(0.25)))
                    ctx.fill(Path(kept), with: .linearGradient(Gradient(colors: [Color(red: 0.3, green: 0.45, blue: 0.6), Color(red: 0.25, green: 0.35, blue: 0.25)]),
                                                               startPoint: CGPoint(x: kept.minX, y: kept.minY), endPoint: CGPoint(x: kept.maxX, y: kept.maxY)))
                    ctx.stroke(Path(frame), with: .color(.white.opacity(0.4)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    ctx.stroke(Path(kept), with: .color(.accentColor), lineWidth: 2)
                }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 4)
                    .onChanged { v in
                        if dragSide == nil {
                            let d = [abs(v.startLocation.x - kept.minX), abs(v.startLocation.y - kept.minY),
                                     abs(v.startLocation.x - kept.maxX), abs(v.startLocation.y - kept.maxY)]
                            guard let i = d.indices.min(by: { d[$0] < d[$1] }), d[i] < 28 else { return }
                            dragSide = i
                        }
                        guard let side = dragSide else { return }
                        let px: CGFloat
                        switch side {
                        case 0: px = (v.location.x - frame.minX) / scale
                        case 1: px = (v.location.y - frame.minY) / scale
                        case 2: px = (frame.maxX - v.location.x) / scale
                        default: px = (frame.maxY - v.location.y) / scale
                        }
                        dragValue = max(0, min(RawCropMath.maxMargin, Int(px.rounded())))
                    }
                    .onEnded { _ in
                        if let side = dragSide { store.set(RawCropMath.sideKeys[side], String(dragValue)) }
                        dragSide = nil
                    }, including: enabled ? .all : .subviews)
            }
            .frame(height: 200)
            Text(Lang.t("Обрезка: слева \(state.cut[0]), сверху \(state.cut[1]), справа \(state.cut[2]), снизу \(state.cut[3]) px",
                        "Cut: left \(state.cut[0]), top \(state.cut[1]), right \(state.cut[2]), bottom \(state.cut[3]) px"))
                .font(.app(12).monospacedDigit()).foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
    }
}
