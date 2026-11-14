import SwiftUI

/// TopRow (P574): the camera screen's top row - its items in order, a comma list of item ids in ui_top_row of the main settings
/// (the «Верхняя строка» sheet edits it). Nothing stored: the default row. The format and the LUT (their sheet and tray have no
/// other way in) are always in it; unknown ids and repeats are dropped, at most six items are kept. An item the open camera lacks
/// (DCG: no iPhone lens has it) stays in the list; the row only hides it.
enum TopRow {
    static let key = "ui_top_row"
    static let format = "format", torch = "torch", timer = "timer", lut = "lut", dcg = "dcg", grid = "grid", horizon = "horizon",
               uhdr = "uhdr"
    static let all = [format, torch, timer, lut, dcg, grid, horizon, uhdr]
    static let defaults = [format, torch, timer, lut, dcg]
    static let max = 6
    static let required = [format, lut]

    static func isRequired(_ id: String) -> Bool { required.contains(id) }

    /// The row's items: the stored list, or the defaults
    static func load() -> [String] {
        guard let raw = SettingsStore.shared.values[key] else { return defaults }
        return clean("\(raw)".split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) })
    }

    static func save(_ items: [String]) { SettingsStore.shared.set(key, clean(items).joined(separator: ",")) }

    /// Known ids in order, without repeats, at most six, the required ones in (a missing format first, a missing LUT last)
    static func clean(_ items: [String]) -> [String] {
        var out: [String] = []
        var others = 0
        for id in items where all.contains(id) && !out.contains(id) {
            if isRequired(id) { out.append(id); continue }
            if others >= max - required.count { continue }
            out.append(id)
            others += 1
        }
        if !out.contains(format) { out.insert(format, at: 0) }
        if !out.contains(lut) { out.append(lut) }
        return out
    }

    /// Whether the open camera can show `id` (TopBar.available: DCG only on a module with it - none on the iPhone)
    static func available(_ id: String) -> Bool { id != dcg }

    static func icon(_ id: String) -> String? {
        switch id {
        case torch: return "cv_bolt"
        case timer: return "cv_timer"
        case lut: return "cv_lut"
        case grid: return "cv_grid"
        case horizon: return "cv_level"
        case uhdr: return "cv_sparkle"
        default: return nil
        }
    }

    /// The caption under its disc in «ДОБАВИТЬ»
    static func label(_ id: String) -> String {
        switch id {
        case format: return L("toprow.format")
        case torch: return L("toprow.flash")
        case timer: return L("toprow.timer")
        case lut: return "LUT"
        case dcg: return "DCG"
        case grid: return L("toprow.grid")
        case horizon: return L("toprow.level")
        default: return "UHDR"
        }
    }

    /// The item's full name (read out)
    static func name(_ id: String) -> String {
        switch id {
        case format: return L("toprow.format_size")
        case lut: return L("toprow.lut_film")
        case dcg: return L("toprow.dcg")
        case uhdr: return "Ultra HDR"
        default: return label(id)
        }
    }
}

/// The photo format and size of the badge (FormatChoice.longLabel, PhotoSize.badge) and of the format sheet: the save mode
/// (pref_save_raw_key: 0 the photo, 1 RAW + photo, 2 RAW only) over the photo codec (pref_photo_format: JPEG / HEIC - the codecs
/// the iPhone writes) and the camera's RAW kind (Bayer RAW / ProRAW).
enum FormatState {
    static let none = 0, paired = 1, rawOnly = 2

    /// The save mode: pref_save_raw_key (the one source of whether RAW is saved, as Android's FormatChoice)
    @MainActor static func rawMode(_ camera: CameraModel) -> Int { camera.rawSaving }

    static var photo: String { SettingsStore.shared.string("pref_photo_format", "jpeg") == "heic" ? "HEIC" : "JPEG" }

    /// «JPEG», «RAW+HEIC», «RAW»
    @MainActor static func badge(_ camera: CameraModel) -> String {
        switch rawMode(camera) {
        case rawOnly: return "RAW"
        case paired: return "RAW+" + photo
        default: return photo
        }
    }

    /// «12 Мп» / «48 Мп»
    @MainActor static func size(_ camera: CameraModel) -> String {
        (camera.fullResolution && camera.supportsFullResolution ? "48" : "12") + L("unit.mp")
    }

    /// «RAW (DNG) + HEIC» / «HEIC» / «RAW (DNG)»
    @MainActor static func summary(_ camera: CameraModel) -> String {
        switch rawMode(camera) {
        case rawOnly: return "RAW (DNG)"
        case paired: return "RAW (DNG) + " + photo
        default: return photo
        }
    }

    @MainActor static func setRawMode(_ mode: Int, camera: CameraModel) {
        camera.setRawSaving(mode)
    }
}

/// The top row (camera_top_row, P574): one centred group of 40 pt items 16 pt apart (the gaps close when the row would not fit),
/// 64 pt high. Off: a white glyph on nothing; on: the accent on its 16 % tint. The format is its badge over the size; the timer
/// carries its seconds on a small accent badge; every item turns with the phone.
struct TopRowBar: View {
    let camera: CameraModel
    let chrome: CameraChrome
    let lutOn: Bool
    let lutOpen: Bool
    var onFormat: (Bool) -> Void
    var onLut: () -> Void
    var onEdit: () -> Void

    var body: some View {
        let items = TopRow.load().filter { TopRow.available($0) }
        let _ = (camera.flashShown, chrome.timerSeconds, chrome.grid, chrome.horizon, chrome.ultraHdr, camera.raw, camera.fullResolution,
                 FormatState.badge(camera), DeviceTurn.shared.degrees)
        GeometryReader { g in
            let widths = items.map { $0 == TopRow.format ? 64.0 : 40.0 }
            let room = g.size.width - 20 - widths.reduce(0, +)
            let gap = items.count > 1 ? Swift.max(0, Swift.min(16, room / CGFloat(items.count - 1))) : 0
            HStack(spacing: gap) {
                ForEach(items, id: \.self) { item($0) }
            }
            .frame(width: g.size.width, height: g.size.height)
        }
        .padding(.top, 4)
        .frame(height: 64)
        .foregroundStyle(.white)
    }

    @ViewBuilder private func item(_ id: String) -> some View {
        switch id {
        case TopRow.format:
            // a tap opens «Формат», a long press «Размер»
            VStack(spacing: 3) {
                Text(FormatState.badge(camera)).font(.app(13, 700)).lineLimit(1)
                Text(FormatState.size(camera)).font(.app(9, 500)).lineLimit(1)
            }
            .padding(.horizontal, 6)
            .frame(minWidth: 40, minHeight: 40)
            .turnsWithPhone()
            .contentShape(Rectangle())
            .onTapGesture { Haptics.tick(); onFormat(false) }
            .onLongPressGesture(minimumDuration: 0.5) { Haptics.longPress(); onFormat(true) }
            .accessibilityLabel(TopRow.name(id))
            .accessibilityAddTraits(.isButton)
        case TopRow.torch:
            glyph(camera.flashShown.icon, on: camera.flashShown != .off) { Task { await camera.cycleFlash() } }
                .accessibilityLabel(camera.flashShown.label)
        case TopRow.timer:
            ZStack(alignment: .bottomTrailing) {
                glyph("cv_timer", on: chrome.timerSeconds > 0) {
                    let seconds = chrome.cycleTimer()
                    camera.say(seconds > 0 ? String(format: L("timer.seconds"), seconds) : L("timer.off"))
                }
                if chrome.timerSeconds > 0 {
                    Text("\(chrome.timerSeconds)").font(.app(10, 700)).foregroundStyle(CameraStyle.ink)
                        .padding(.horizontal, 3).frame(minWidth: 15, minHeight: 15, maxHeight: 15)
                        .background(CameraStyle.accent, in: Capsule())
                        .padding(.bottom, 2)
                        .allowsHitTesting(false)
                        .turnsWithPhone()
                }
            }
            .accessibilityLabel(L("top.timer"))
        case TopRow.lut:
            glyph("cv_lut", on: lutOn || lutOpen, tint: lutOpen) { onLut() }
                .accessibilityLabel(L("toprow.lut_film") + ": " + (lutOn ? L("lut.on") : L("lut.off")))
        case TopRow.grid:
            glyph("cv_grid", on: chrome.grid) { camera.say(L(chrome.toggleGrid() ? "grid.on" : "grid.off")) }
                .accessibilityLabel(L("top.grid"))
        case TopRow.horizon:
            glyph("cv_level", on: chrome.horizon) {
                chrome.horizon.toggle()
                camera.say(L(chrome.horizon ? "level.on" : "level.off"))
            }
            .accessibilityLabel(L("top.level"))
        case TopRow.uhdr:
            glyph("cv_sparkle", on: chrome.ultraHdr) {
                chrome.ultraHdr.toggle()
                camera.say(L(chrome.ultraHdr ? "uhdr.on" : "uhdr.off"))
            }
            .accessibilityLabel(L("top.uhdr"))
        default:
            EmptyView()
        }
    }

    /// Cam.Glyph: a 22 pt glyph in its 40 pt item; on: the accent (and its 16 % disc when `tint`, as the open LUT tray)
    private func glyph(_ name: String, on: Bool, tint: Bool? = nil, action: @escaping () -> Void) -> some View {
        Button(action: { Haptics.tick(); action() }) {
            Image(name).renderingMode(.template).resizable().frame(width: 22, height: 22)
                .foregroundStyle(on ? CameraStyle.accent : Color.white)
                .frame(width: 40, height: 40)
                .background((tint ?? on) ? CameraStyle.accent.opacity(0.16) : Color.clear, in: Circle())
                .turnsWithPhone()
        }
        .buttonStyle(PressScale())
    }
}

/// Motion.press: the item gives under the finger (to 92 %) and springs back
struct PressScale: ButtonStyle {
    var scale: CGFloat = 0.92
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(configuration.isPressed ? .easeOut(duration: 0.1) : .spring(response: 0.3, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/// The top grabber (P574): a 36 x 4 grip 3 pt under the top; a tap or a pull down opens «Верхняя строка»
struct TopGrabber: View {
    var onOpen: () -> Void
    var body: some View {
        RoundedRectangle(cornerRadius: 2).fill(CameraStyle.grip).frame(width: 36, height: 4)
            .frame(width: 56, height: 12)
            .contentShape(Rectangle().inset(by: -10))
            .onTapGesture { onOpen() }
            .gesture(DragGesture(minimumDistance: 6).onEnded { d in if d.translation.height > 12 { onOpen() } })
            .accessibilityLabel(L("toprow.edit"))
            .accessibilityAddTraits(.isButton)
    }
}

/// TopRowSheet (P574, the concept's «edit»): a card from the top with «Отмена» / «Готово», the row as it will be (each item with a
/// «−» badge that takes it out, but the format and the LUT), «ДОБАВИТЬ» with every item the camera can show (the ones in the row
/// dimmed, all of them once the row is full) and «Вернуть основные». Nothing is stored before «Готово»; «Отмена», a tap beside the
/// card, a swipe up on it and the grabber leave the row as it was.
struct TopRowSheet: View {
    let camera: CameraModel
    @Binding var shown: Bool
    @State private var items = TopRow.load()
    @State private var drag: CGFloat = 0
    @State private var popped: String?

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.opacity(0.45).ignoresSafeArea()
                .onTapGesture { close() }
                .accessibilityLabel(L("toprow.close"))
            card
                .offset(y: min(0, drag))
                .gesture(DragGesture(minimumDistance: 8)
                    .onChanged { drag = $0.translation.height }
                    .onEnded { d in
                        if d.translation.height < -60 || d.predictedEndTranslation.height < -160 { close() }
                        else { withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { drag = 0 } }
                    })
                // an iPad: the card in a phone sheet's width under the top row, not stretched over the screen
                .tabletColumn(600)
                .padding(.horizontal, 8)
                .padding(.top, 8)
                .transition(.move(edge: .top))
        }
    }

    private func close() {
        withAnimation(.easeOut(duration: 0.24)) { shown = false }
    }

    private var card: some View {
        let full = items.count >= TopRow.max
        let library = TopRow.all.filter { TopRow.available($0) }
        return VStack(spacing: 14) {
            ZStack {
                HStack {
                    pill(L("toprow.cancel"), fill: CameraStyle.row, ink: CameraStyle.text, bold: false) { close() }
                    Spacer()
                    pill(L("toprow.done"), fill: CameraStyle.accent, ink: CameraStyle.ink, bold: true) {
                        TopRow.save(items)
                        close()
                    }
                }
                Text(L("toprow.title")).font(.app(15, 600)).foregroundStyle(CameraStyle.text).lineLimit(1)
                    .padding(.horizontal, 96)
                    .accessibilityAddTraits(.isHeader)
            }
            HStack(spacing: 12) {
                if items.isEmpty {
                    Text(L("toprow.empty")).font(.app(13)).foregroundStyle(CameraStyle.muted)
                }
                ForEach(items, id: \.self) { id in
                    ZStack(alignment: .topLeading) {
                        TopRowGlyph(id: id, camera: camera, iconSize: 20, small: false)
                            .frame(width: 38, height: 38)
                            .opacity(TopRow.available(id) ? 1 : 0.45)
                            .scaleEffect(popped == id ? 1.15 : 1)
                        if !TopRow.isRequired(id) {
                            Button {
                                Haptics.tick()
                                withAnimation(CameraMotion.glide) { items.removeAll { $0 == id } }
                            } label: {
                                Image("cv_minus").renderingMode(.template).resizable().frame(width: 11, height: 11)
                                    .foregroundStyle(CameraStyle.control)
                                    .frame(width: 20, height: 20).background(CameraStyle.badge, in: Circle())
                            }
                            .offset(x: 38 - 20 + 6, y: -6)
                            .accessibilityLabel(L("toprow.remove") + TopRow.name(id))
                        }
                    }
                    .frame(width: 38, height: 38)
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel(TopRow.name(id))
                }
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Capsule().fill(Color.black))
            .overlay(Capsule().stroke(Color.white.opacity(0.18), lineWidth: 1))
            Text(L("toprow.add").uppercased()).font(.app(12, 600)).kerning(1)
                .foregroundStyle(Color.white.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 6)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 5), spacing: 12) {
                ForEach(library, id: \.self) { id in
                    let isIn = items.contains(id)
                    Button {
                        guard !isIn, !full else { return }
                        Haptics.tick()
                        withAnimation(CameraMotion.glide) { items.append(id) }
                        popped = id
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.6).delay(0.05)) { popped = nil }
                    } label: {
                        VStack(spacing: 5) {
                            TopRowGlyph(id: id, camera: camera, iconSize: 21, small: true)
                                .frame(width: 48, height: 48).background(CameraStyle.row, in: Circle())
                            Text(TopRow.label(id)).font(.app(10, 600)).foregroundStyle(CameraStyle.text)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PressScale())
                    .opacity(isIn || full ? 0.35 : 1)
                    .accessibilityLabel(L("toprow.add_item") + TopRow.name(id))
                    .accessibilityValue(isIn ? L("toprow.in_row") : full ? L("toprow.full") : "")
                }
            }
            Button {
                withAnimation(CameraMotion.glide) { items = TopRow.defaults }
            } label: {
                Text(L("toprow.reset")).font(.app(14, 600)).foregroundStyle(CameraStyle.accent)
                    .padding(.horizontal, 16).frame(height: 34)
            }
            SheetGrab().onTapGesture { close() }.accessibilityLabel(L("toprow.close"))
        }
        .padding(.horizontal, 14).padding(.top, 14).padding(.bottom, 8)
        .background(RoundedRectangle(cornerRadius: 36).fill(CameraStyle.control))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("toprow.title"))
    }

    /// The head's buttons: 34 pt capsules, 15 pt - «Отмена» regular on #2C2C2E, «Готово» heavy on the accent
    private func pill(_ text: String, fill: Color, ink: Color, bold: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.app(15, bold ? 700 : 400)).foregroundStyle(ink).lineLimit(1)
                .padding(.horizontal, bold ? 16 : 14).frame(height: 34).background(fill, in: Capsule())
        }
        .buttonStyle(PressScale())
    }
}

/// An item's glyph as the top row draws it: its icon, or its text (the format over the size, DCG)
struct TopRowGlyph: View {
    let id: String
    let camera: CameraModel
    let iconSize: CGFloat
    let small: Bool

    var body: some View {
        if let icon = TopRow.icon(id) {
            Image(icon).renderingMode(.template).resizable().frame(width: iconSize, height: iconSize).foregroundStyle(CameraStyle.text)
        } else {
            let main = id == TopRow.format ? FormatState.badge(camera) : id == TopRow.dcg ? "DCG" : "UHDR"
            VStack(spacing: 3) {
                Text(main).font(.system(size: (small ? 11 : 12) - (main.count > 5 ? 2 : 0), weight: .bold)).lineLimit(1).fixedSize()
                if id == TopRow.format {
                    Text(FormatState.size(camera)).font(.app(small ? 8 : 9, 600)).opacity(0.85).lineLimit(1).fixedSize()
                }
            }
            .foregroundStyle(CameraStyle.text)
        }
    }
}
