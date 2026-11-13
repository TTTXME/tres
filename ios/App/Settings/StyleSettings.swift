import SwiftUI

/// PhotoStyle (settings/PhotoStyle.java): Обычный, Чёрно-белый or Плёнка - a view over the black-and-white filter
/// (pref_scam_hybrid_colour_mono), the film LUT (pref_film_lut) and the film simulation (pref_film_sim_stock). Leaving a style
/// remembers its choice (ui_style_last_*), so coming back restores the same filter, LUT or stock.
enum PhotoStyle {
    static let normal = 0, mono = 1, film = 2
    static let monoKey = "pref_scam_hybrid_colour_mono", lutKey = "pref_film_lut"
    static let stockKey = "pref_film_sim_stock", strengthKey = "pref_film_sim_strength"
    static let lastMono = "ui_style_last_mono", lastLut = "ui_style_last_lut"
    static let lastStock = "ui_style_last_stock", lastStockLut = "ui_style_last_stock_lut"
    static let defaultLut = "scam:warm400"

    /// The stock in effect (a known stock at a strength above 0), or nil
    static func stock(_ s: SettingsStore) -> String? {
        let id = s.string(stockKey, "")
        guard !id.trimmingCharacters(in: .whitespaces).isEmpty, FilmCatalog.stock(id) != nil else { return nil }
        return PrefValue.number(s.values[strengthKey], 100) > 0 ? id : nil
    }

    static func current(_ s: SettingsStore) -> Int {
        if stock(s) != nil { return film }
        let m = s.string(monoKey, "off")
        if m != "off" { return mono }
        return s.string(lutKey, "").isEmpty ? normal : film
    }

    static func apply(_ s: SettingsStore, _ style: Int) {
        let m = s.string(monoKey, "off"), lut = s.string(lutKey, ""), inEffect = Self.stock(s)
        let oldLastMono = s.string(lastMono, "neutral"), oldLastLut = s.string(lastLut, defaultLut)
        let oldLastStock = s.string(lastStock, ""), oldLastStockLut = s.string(lastStockLut, "")
        if m != "off" { s.set(lastMono, m) }
        if !lut.isEmpty { s.set(lastLut, lut) }
        if let inEffect {
            s.set(lastStock, inEffect)
            s.set(lastStockLut, lut)
        } else if !lut.isEmpty {
            s.set(lastStock, nil)   // the last film was a LUT alone: «Плёнка» brings that back
        }
        let last: String? = inEffect != nil ? inEffect : !lut.isEmpty ? nil : oldLastStock
        switch style {
        case mono:
            s.set(monoKey, oldLastMono)
            s.set(lutKey, "")
            s.set(stockKey, "")
        case film:
            s.set(monoKey, "off")
            if let last, FilmCatalog.stock(last) != nil {   // the film that was on, with its LUT
                s.set(stockKey, last)
                s.set(lutKey, inEffect != nil ? lut : oldLastStockLut)
            } else {
                s.set(lutKey, oldLastLut)
            }
        default:
            s.set(monoKey, "off")
            s.set(lutKey, "")
            s.set(stockKey, "")
        }
    }

    static func name(_ style: Int) -> String {
        switch style {
        case mono: return Lang.t("Ч/б", "B&W")
        case film: return Lang.t("Плёнка", "Film")
        default: return Lang.t("Обычный", "Normal")
        }
    }
}

/// PhotoStylePreference: one card of three radio rows - Обычный, Чёрно-белый, Плёнка - over PhotoStyle.
struct PhotoStylePicker: View {
    private var store: SettingsStore { SettingsStore.shared }
    private static let rows: [(String, String)] = [
        (Lang.t("Обычный", "Normal"), Lang.t("Естественные цвета, как их видит камера. Плёночные цвета и чёрно-белый режим выключены.",
                                         "Natural colours as the camera sees them. Film colours and black and white are off.")),
        (Lang.t("Чёрно-белый", "Black and white"), Lang.t("Снимок без цвета. Ниже можно выбрать цветной фильтр, как у чёрно-белой плёнки: он меняет яркость неба, листвы и кожи.",
                                                       "A photo without colour. Below you can pick a colour filter as on black-and-white film: it changes how bright the sky, leaves and skin come out.")),
        (Lang.t("Плёнка", "Film"), Lang.t("Снимок в цветах плёнки: их задаёт выбранный ниже LUT (таблица цветов) или симуляция плёнки.",
                                       "The photo in a film's colours: the LUT (colour table) or the film simulation chosen below sets them.")),
    ]

    var body: some View {
        let now = PhotoStyle.current(store)
        ForEach(0..<3, id: \.self) { i in
            Button { PhotoStyle.apply(store, i) } label: {
                HStack(spacing: 12) {
                    Image("cs_ic_palette").resizable().frame(width: 22, height: 22)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Self.rows[i].0).foregroundStyle(.primary)
                        Text(Self.rows[i].1).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: i == now ? "largecircle.fill.circle" : "circle").foregroundStyle(i == now ? Color.accentColor : .secondary)
                }
            }
            .accessibilityAddTraits(i == now ? .isSelected : [])
        }
    }
}

/// LutLook (processing/color/LutLook.java): reference colours and greys through the LUT at its strength, «before» against «after».
/// The LUT is sampled through SCAMFilm.lookThumbnail (the engine's FinishedLook table at full strength, 8-bit in and out); the
/// strength mixes as the photo does.
struct LutLook {
    static let chart: [[Int]] = [[115, 82, 68], [194, 150, 130], [98, 122, 157], [87, 108, 67], [133, 128, 177],
                                 [103, 189, 170], [214, 126, 44], [80, 91, 166], [193, 90, 99], [94, 60, 108], [157, 188, 64], [224, 163, 46],
                                 [56, 61, 150], [70, 148, 73], [175, 54, 60], [231, 199, 31], [187, 86, 149], [8, 133, 161], [243, 243, 242],
                                 [200, 200, 200], [160, 160, 160], [122, 122, 121], [85, 85, 85], [52, 52, 52]]
    static let keyColours: [[Int]] = [[194, 150, 130], [98, 122, 157], [87, 108, 67], [175, 54, 60], [45, 45, 48], [225, 222, 215]]
    static let curveSteps = 40

    private let table: [Int: [Float]]
    let strength: Float

    private static func code(_ v: Float) -> Int { Int((max(0, min(1, v)) * 255).rounded()) }
    private static func pack(_ r: Int, _ g: Int, _ b: Int) -> Int { r << 16 | g << 8 | b }

    /// Every colour the analysis, the curve and the swatches read
    private static func inputs() -> [[Int]] {
        var out: [[Int]] = []
        for v: Float in [0, 0.1, 0.2, 0.3, 0.7, 0.8, 0.9, 1] { out.append([code(v), code(v), code(v)]) }
        for k in 0...curveSteps { let c = code(Float(k) / Float(curveSteps)); out.append([c, c, c]) }
        out += chart + keyColours
        return out
    }

    init?(lut: String, strength: Float) {
        let colours = Self.inputs()
        var rgba = [UInt8](repeating: 255, count: colours.count * 4)
        for (i, c) in colours.enumerated() {
            rgba[i * 4] = UInt8(c[0]); rgba[i * 4 + 1] = UInt8(c[1]); rgba[i * 4 + 2] = UInt8(c[2])
        }
        guard let out = SCAMFilm.lookThumbnail(Data(rgba), width: colours.count, height: 1, lut: lut, film: ""),
              out.count >= rgba.count else { return nil }
        let bytes = [UInt8](out)
        var table: [Int: [Float]] = [:]
        for (i, c) in colours.enumerated() {
            table[Self.pack(c[0], c[1], c[2])] = [Float(bytes[i * 4]) / 255, Float(bytes[i * 4 + 1]) / 255, Float(bytes[i * 4 + 2]) / 255]
        }
        self.table = table
        self.strength = max(0, min(1, strength))
    }

    /// One display colour through the LUT at the strength (the input on the 8-bit grid it was sampled at)
    func apply(_ r: Float, _ g: Float, _ b: Float) -> [Float] {
        let ri = Self.code(r), gi = Self.code(g), bi = Self.code(b)
        let rq = Float(ri) / 255, gq = Float(gi) / 255, bq = Float(bi) / 255
        let o = table[Self.pack(ri, gi, bi)] ?? [rq, gq, bq]
        func mix(_ a: Float, _ b: Float) -> Float { max(0, min(1, a + (max(0, min(1, b)) - a) * strength)) }
        return [mix(rq, o[0]), mix(gq, o[1]), mix(bq, o[2])]
    }

    static func luma(_ c: [Float]) -> Float { 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] }
    private func grey(_ v: Float) -> Float { Self.luma(apply(v, v, v)) }
    private static func spread(_ c: [Float]) -> Float { c.max()! - c.min()! }

    /// The analysis (the concept's thresholds): (aspect, verdict) in the concept's order
    func analyse() -> [(String, String)] {
        var t: [(String, String)] = []
        let all = (grey(0.9) - grey(0.1)) / 0.8, mid = (grey(0.7) - grey(0.3)) / 0.4
        if mid > 1.05 && all <= 1.05 { t.append(("contrast", "midtones")) } else {
            t.append(("contrast", all > 1.05 ? "stronger" : all < 0.97 ? "softer" : "same"))
        }
        if Self.luma(apply(0, 0, 0)) > 0.015 { t.append(("black", "lifted")) }
        if Self.luma(apply(1, 1, 1)) < 0.985 { t.append(("white", "muted")) }
        var before: Float = 0, after: Float = 0
        for c in Self.chart.prefix(18) {
            let r = Float(c[0]) / 255, g = Float(c[1]) / 255, b = Float(c[2]) / 255
            before += Self.spread([r, g, b])
            after += Self.spread(apply(r, g, b))
        }
        let ratio = after / before
        t.append(("colours", ratio < 0.08 ? "mono" : ratio > 1.04 ? "richer" : ratio < 0.95 ? "calmer" : "kept"))
        if ratio >= 0.08 {
            if let sh = Self.tone(apply(0.2, 0.2, 0.2)) { t.append(("shadows", sh)) }
            if let hi = Self.tone(apply(0.8, 0.8, 0.8)) { t.append(("highlights", hi)) }
            let skin: [Float] = [194 / 255, 150 / 255, 130 / 255]
            if Self.spread(apply(skin[0], skin[1], skin[2])) > Self.spread(skin) * 1.04 { t.append(("skin", "richer")) }
            let gr = apply(87 / 255, 108 / 255, 67 / 255)
            let dr = gr[0] - 87 / 255, db = gr[2] - 67 / 255
            if db > 0.015 || dr > 0.02 { t.append(("greens", dr > db ? "olive" : "teal")) }
        }
        return t
    }

    private static func tone(_ c: [Float]) -> String? {
        let w = c[0] - c[2]
        return w > 0.012 ? "warmer" : w < -0.012 ? "cooler" : nil
    }

    static func aspectName(_ a: String) -> String {
        switch a {
        case "contrast": return Lang.t("Контраст", "Contrast")
        case "black": return Lang.t("Чёрный", "Black")
        case "white": return Lang.t("Белый", "White")
        case "colours": return Lang.t("Цвета", "Colours")
        case "shadows": return Lang.t("Тени", "Shadows")
        case "highlights": return Lang.t("Света", "Highlights")
        case "skin": return Lang.t("Кожа", "Skin")
        default: return Lang.t("Зелень", "Greens")
        }
    }

    static func verdictName(_ v: String) -> String {
        switch v {
        case "midtones": return Lang.t("ярче в середине, мягче по краям", "punchier middle, softer ends")
        case "stronger": return Lang.t("сильнее", "stronger")
        case "softer": return Lang.t("мягче", "softer")
        case "same": return Lang.t("почти как был", "about the same")
        case "lifted": return Lang.t("приподнят", "lifted")
        case "muted": return Lang.t("приглушён", "muted")
        case "mono": return Lang.t("чёрно-белые", "black and white")
        case "richer": return Lang.t("насыщеннее", "richer")
        case "calmer": return Lang.t("спокойнее", "calmer")
        case "kept": return Lang.t("как были", "as they were")
        case "warmer": return Lang.t("теплее", "warmer")
        case "cooler": return Lang.t("холоднее", "cooler")
        case "olive": return Lang.t("к оливковой", "towards olive")
        default: return Lang.t("к бирюзовой", "towards teal")
        }
    }

    /// FilmLooks.description, or «Свой LUT из файла …»
    static func description(_ value: String) -> String {
        switch value {
        case "scam:warm400": return Lang.t("Портретный негатив: тёплые чёрные, кремовые света, оливковая зелень, мягкие края тонов",
                                           "Portrait negative: warm blacks, creamy highlights, olive greens, soft tonal ends")
        case "scam:cool160": return Lang.t("Мелкозернистый негатив: низкий контраст, пастель, бирюзовые тени, розоватые белые",
                                           "Fine-grain negative: low contrast, pastels, teal shadows, pinkish whites")
        case "scam:slide100": return Lang.t("Обращаемая плёнка: глубокий чёрный, сильный контраст, плотное синее небо, сочные цвета",
                                            "Slide film: deep black, strong contrast, dense blue sky, rich colours")
        case "scam:cine500": return Lang.t("Кинокопия: контрастная середина, бирюзовые тени, тёплые света, мягкие белые",
                                           "Print film: contrasty middle, teal shadows, warm highlights, soft whites")
        case "scam:mono400": return Lang.t("Панхроматическая ч/б плёнка: плотная S-кривая, мягкий подъём теней",
                                           "Panchromatic black-and-white film: a firm S-curve, a soft shadow lift")
        default: return Lang.t("Свой LUT из файла «" + value + "»", "Own LUT from «" + value + "»")
        }
    }
}

/// LutChangesPreference: «Что меняется» of the LUT page - what the LUT imitates («Слегка.» under 50 %), the analysis chips, the
/// tone curve («тени / середина / света») and its reading, the six key colours before → after and «Как это определяется». With
/// no LUT or at 0 %: one line.
struct LutChangesView: View {
    private var store: SettingsStore { SettingsStore.shared }
    @State private var look: LutLook?
    @State private var failed = false
    @State private var howOpen = false

    private var value: String { store.string("pref_film_lut", "") }
    private var strength: Float {
        Float(max(0, min(100, PrefValue.number(store.values["pref_film_lut_strength"], 100)))) / 100
    }

    var body: some View {
        let value = value, k = strength
        VStack(alignment: .leading, spacing: 10) {
            Text(Lang.t("Что меняется", "What changes")).font(.footnote.weight(.semibold)).foregroundStyle(Color.accentColor)
            if value.isEmpty {
                line(Lang.t("Плёночный образ выключен: снимок как есть.", "The film look is off: the photo stays as it is."))
            } else if k <= 0 {
                line(Lang.t("Сила 0 %: снимок не меняется.", "Strength 0 %: the photo does not change."))
            } else if failed {
                line(Lang.t("Файл не читается: выберите другой LUT.", "The file cannot be read: choose another LUT."))
            } else if let look {
                details(look, value: value)
            } else {
                ProgressView()
            }
        }
        .padding(.vertical, 6)
        .task(id: value + "|" + String(k)) {
            look = nil
            failed = false
            guard !value.isEmpty, k > 0 else { return }
            let made = await Task.detached(priority: .userInitiated) { LutLook(lut: value, strength: k) }.value
            if Task.isCancelled { return }
            look = made
            failed = made == nil
        }
    }

    private func line(_ text: String) -> some View { Text(text).font(.footnote).foregroundStyle(.secondary) }

    @ViewBuilder private func details(_ look: LutLook, value: String) -> some View {
        Text((look.strength < 0.5 ? Lang.t("Слегка. ", "Slightly. ") : "") + LutLook.description(value)).font(.footnote)
        let tags = look.analyse()
        FlowChips(items: tags.map { LutLook.aspectName($0.0) + ": " + LutLook.verdictName($0.1) })
        HStack(alignment: .center, spacing: 12) {
            VStack(spacing: 2) {
                LutCurveShape(look: look).frame(width: 120, height: 90)
                HStack {
                    Text(Lang.t("тени", "shadows")); Spacer(); Text(Lang.t("середина", "middle")); Spacer(); Text(Lang.t("света", "lights"))
                }
                .font(.system(size: 10)).foregroundStyle(.secondary).frame(width: 120)
            }
            Text(Lang.t("Линия выше пунктира — эта часть снимка станет светлее, ниже — темнее. Цветные линии показывают сдвиг оттенка.",
                        "Above the dashed line that part of the photo gets lighter, below it darker. The coloured lines show the tint shift."))
                .font(.caption).foregroundStyle(.secondary)
        }
        Text(Lang.t("Ключевые цвета: слева — до, справа — после", "Key colours: before on the left, after on the right"))
            .font(.caption).foregroundStyle(.secondary)
        let names = [Lang.t("Кожа", "Skin"), Lang.t("Небо", "Sky"), Lang.t("Зелень", "Greens"), Lang.t("Красный", "Red"),
                     Lang.t("Тени", "Shadows"), Lang.t("Света", "Lights")]
        HStack(spacing: 6) {
            ForEach(0..<LutLook.keyColours.count, id: \.self) { i in
                let k0 = LutLook.keyColours[i]
                let a = look.apply(Float(k0[0]) / 255, Float(k0[1]) / 255, Float(k0[2]) / 255)
                VStack(spacing: 4) {
                    HStack(spacing: 0) {
                        Color(red: Double(k0[0]) / 255, green: Double(k0[1]) / 255, blue: Double(k0[2]) / 255)
                        Color(red: Double(a[0]), green: Double(a[1]), blue: Double(a[2]))
                    }
                    .frame(height: 28)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    Text(names[i]).font(.system(size: 10.5)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        DisclosureGroup(Lang.t("Как это определяется", "How it is worked out"), isExpanded: $howOpen) {
            Text(Lang.t(
                "Через LUT прогоняются эталонные цвета и серые тона, и сравнивается «до» и «после». Так же и для ваших файлов — описание из файла не нужно.\n"
                    + "• Контраст — разница яркости между тёмно-серым (10 %) и светло-серым (90 %): больше на 5 % — «сильнее», меньше на 3 % — «мягче». Если середина (30–70 %) растёт, а края нет — «ярче в середине, мягче по краям».\n"
                    + "• Чёрный / Белый — во что превращается чистый чёрный и чистый белый: чёрный светлее 1,5 % — «приподнят», белый темнее 98,5 % — «приглушён».\n"
                    + "• Цвета — насыщенность 18 цветов шкалы: больше на 4 % — «насыщеннее», меньше на 5 % — «спокойнее», почти ноль — «чёрно-белые».\n"
                    + "• Тени / Света — оттенок тёмно-серого (20 %) и светло-серого (80 %): красного больше синего — «теплее», наоборот — «холоднее».\n"
                    + "• Кожа — светлый тон кожи со шкалы стал насыщеннее. Зелень — цвет листвы ушёл к жёлтому (оливковая) или к синему (бирюзовая).",
                "Reference colours and greys go through the LUT and «before» is compared with «after» - the same for your own files, no description in the file is needed.\n"
                    + "• Contrast - the brightness spread of dark grey (10 %) and light grey (90 %): 5 % more is «stronger», 3 % less «softer». A middle (30-70 %) that grows while the ends do not: «punchier middle, softer ends».\n"
                    + "• Black / White - what pure black and pure white become: black above 1.5 % is «lifted», white below 98.5 % «muted».\n"
                    + "• Colours - the saturation of the chart's 18 colours: 4 % more is «richer», 5 % less «calmer», about none «black and white».\n"
                    + "• Shadows / Highlights - the tint of dark grey (20 %) and light grey (80 %): more red than blue is «warmer», the other way «cooler».\n"
                    + "• Skin - the chart's light skin got richer. Greens - foliage moved to yellow (olive) or to blue (teal)."))
                .font(.caption).foregroundStyle(.secondary)
        }
        .font(.footnote.weight(.semibold))
    }
}

/// LutCurveView: greys 0..1 through the LUT at its strength - the dashed diagonal «no change», the red / green / blue lines the
/// channels, the white line the luminance with the area between it and the diagonal in the accent.
struct LutCurveShape: View {
    let look: LutLook
    var body: some View {
        Canvas { ctx, size in
            let w = size.width, h = size.height
            ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 10), with: .color(Color(red: 0.067, green: 0.082, blue: 0.094)))
            var grid = Path()
            for k in 1..<4 {
                grid.move(to: CGPoint(x: w * CGFloat(k) / 4, y: 0)); grid.addLine(to: CGPoint(x: w * CGFloat(k) / 4, y: h))
                grid.move(to: CGPoint(x: 0, y: h * CGFloat(k) / 4)); grid.addLine(to: CGPoint(x: w, y: h * CGFloat(k) / 4))
            }
            ctx.stroke(grid, with: .color(.white.opacity(0.13)), lineWidth: 1)
            var diag = Path()
            diag.move(to: CGPoint(x: 0, y: h)); diag.addLine(to: CGPoint(x: w, y: 0))
            ctx.stroke(diag, with: .color(.white.opacity(0.33)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            let n = LutLook.curveSteps
            let out = (0...n).map { k -> [Float] in let v = Float(k) / Float(n); return look.apply(v, v, v) }
            let colours: [Color] = [Color(red: 1, green: 0.42, blue: 0.42), Color(red: 0.42, green: 0.86, blue: 0.55), Color(red: 0.42, green: 0.66, blue: 1)]
            for ch in 0..<3 {
                var p = Path()
                for k in 0...n {
                    let pt = CGPoint(x: CGFloat(k) / CGFloat(n) * w, y: h - CGFloat(out[k][ch]) * h)
                    if k == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                }
                ctx.stroke(p, with: .color(colours[ch].opacity(0.67)), lineWidth: 1.2)
            }
            var area = Path(), line = Path()
            area.move(to: CGPoint(x: 0, y: h))
            for k in 0...n {
                let pt = CGPoint(x: CGFloat(k) / CGFloat(n) * w, y: h - CGFloat(LutLook.luma(out[k])) * h)
                area.addLine(to: pt)
                if k == 0 { line.move(to: pt) } else { line.addLine(to: pt) }
            }
            for k in stride(from: n, through: 0, by: -1) { area.addLine(to: CGPoint(x: CGFloat(k) / CGFloat(n) * w, y: h - CGFloat(k) / CGFloat(n) * h)) }
            area.closeSubpath()
            ctx.fill(area, with: .color(Color.accentColor.opacity(0.22)))
            ctx.stroke(line, with: .color(.white), lineWidth: 2)
        }
        .accessibilityLabel(Lang.t("Тоновая кривая", "Tone curve"))
    }
}

/// Chips left to right, wrapped onto new lines (LutChangesPreference.FlowLayout)
struct FlowChips: View {
    let items: [String]
    var body: some View {
        ChipFlow(spacing: 6) {
            ForEach(items, id: \.self) { item in
                Text(item).font(.caption).padding(.horizontal, 9).padding(.vertical, 4)
                    .background(Color(red: 0.137, green: 0.165, blue: 0.18), in: Capsule())
            }
        }
    }
}

struct ChipFlow: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxW = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > maxW { x = 0; y += line + spacing; line = 0 }
            x += s.width + spacing
            widest = max(widest, x - spacing)
            line = max(line, s.height)
        }
        return CGSize(width: proposal.width ?? widest, height: y + line)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > bounds.width { x = 0; y += line + spacing; line = 0 }
            v.place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            line = max(line, s.height)
        }
    }
}
