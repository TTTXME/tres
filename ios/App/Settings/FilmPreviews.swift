import SwiftUI
import UniformTypeIdentifiers

/// RGBA8 sRGB pixels with their size
struct SamplePixels {
    let rgba: Data
    let width: Int
    let height: Int

    var image: CGImage? {
        guard let provider = CGDataProvider(data: rgba as CFData), let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: space,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider, decode: nil,
                       shouldInterpolate: true, intent: .defaultIntent)
    }

    /// LutSamples.mix: before + (after - before) x strength
    func mixed(with after: SamplePixels, _ strength: Double) -> SamplePixels {
        let k = Float(max(0, min(1, strength)))
        guard after.rgba.count == rgba.count, k < 1 else { return k >= 1 ? after : self }
        let a: [UInt8] = [UInt8](rgba), b: [UInt8] = [UInt8](after.rgba)
        var out: [UInt8] = [UInt8](repeating: 0, count: a.count)
        for i in 0..<a.count {
            let x: Float = Float(a[i])
            let y: Float = Float(b[i])
            let v: Float = (x + (y - x) * k).rounded()
            out[i] = UInt8(Swift.max(Float(0), Swift.min(Float(255), v)))
        }
        return SamplePixels(rgba: Data(out), width: width, height: height)
    }
}

/// LutSamples (ui/settings/custompreferences/LutSamples.java, SIMPLE_UI §4c): the pages' sample pictures drawn here, so they need no
/// bundled photo - «Пейзаж» and «Закат» (the concept's drawScene, SampleScene), «Шкала цветов» (LutLook.CHART and a grey ramp) and
/// the lens filters' «Огни» (a night street: sodium and white lamps, lit windows, a car's lights).
enum LutSamples {
    static func name(_ i: Int) -> String { [L("film.sample.landscape"), L("film.sample.sunset"), L("film.sample.chart")][min(2, max(0, i))] }

    /// The lens filter page's samples: «Огни», «Пейзаж», «Закат»
    static func lensName(_ i: Int) -> String { [L("film.sample.lights"), L("film.sample.landscape"), L("film.sample.sunset")][min(2, max(0, i))] }

    private static func render(_ w: Int, _ h: Int, _ draw: (CGContext) -> Void) -> SamplePixels {
        var data = Data(count: w * h * 4)
        data.withUnsafeMutableBytes { raw in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return }
            ctx.translateBy(x: 0, y: CGFloat(h))
            ctx.scaleBy(x: 1, y: -1)
            draw(ctx)
        }
        return SamplePixels(rgba: data, width: w, height: h)
    }

    private static func rgb(_ v: UInt32, _ a: CGFloat = 1) -> CGColor {
        CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: a)
    }

    /// LutSamples.draw: 0 the landscape, 1 the sunset, 2 the colour chart
    static func draw(_ i: Int, _ w: Int, _ h: Int) -> SamplePixels {
        render(w, h) { ctx in
            switch i {
            case 0: SampleScene.draw(ctx, w: Float(w), h: Float(h), seed: 0, zoom: 1.5)
            case 1: SampleScene.draw(ctx, w: Float(w), h: Float(h), seed: 2, zoom: 1.15)
            default: chart(ctx, CGFloat(w), CGFloat(h))
            }
        }
    }

    private static func chart(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
        ctx.setFillColor(rgb(0x202326))
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        let pw = w / 6, ph = h * 0.78 / 4, m = max(1, 3 * w / 360)
        for (k, c) in LutLook.chart.enumerated() {
            ctx.setFillColor(CGColor(srgbRed: CGFloat(c[0]) / 255, green: CGFloat(c[1]) / 255, blue: CGFloat(c[2]) / 255, alpha: 1))
            let x = CGFloat(k % 6) * pw, y = CGFloat(k / 6) * ph
            ctx.fill(CGRect(x: x + m, y: y + m, width: pw - 2 * m, height: ph - 2 * m))
        }
        let ramp = CGRect(x: m, y: h * 0.8, width: w - 2 * m, height: h * 0.2 - m)
        ctx.saveGState()
        ctx.clip(to: ramp)
        if let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [rgb(0x000000), rgb(0xFFFFFF)] as CFArray, locations: [0, 1]) {
            ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: 0), end: CGPoint(x: w, y: 0), options: [])
        }
        ctx.restoreGState()
    }

    /// P228: a night street for the lens filters - a dark sky, roof lines, warm (sodium) and white point lights, a lit window row
    static func lights(_ w: Int, _ h: Int) -> SamplePixels {
        render(w, h) { ctx in
            let W = CGFloat(w), H = CGFloat(h)
            if let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [rgb(0x0B1426), rgb(0x1D2A44), rgb(0x3A3340)] as CFArray,
                                  locations: [0, 0.6, 1]) {
                ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: H), options: [])
            }
            ctx.setFillColor(rgb(0x0A0C10))
            let xs: [CGFloat] = [0, 0.12, 0.12, 0.3, 0.3, 0.42, 0.42, 0.63, 0.63, 0.78, 0.78, 1, 1]
            let ys: [CGFloat] = [0.55, 0.55, 0.42, 0.42, 0.6, 0.6, 0.35, 0.35, 0.5, 0.5, 0.45, 0.45, 1]
            let roofs = CGMutablePath()
            roofs.move(to: CGPoint(x: 0, y: H))
            for i in xs.indices { roofs.addLine(to: CGPoint(x: xs[i] * W, y: ys[i] * H)) }
            roofs.closeSubpath()
            ctx.addPath(roofs)
            ctx.fillPath()
            ctx.setFillColor(rgb(0x2A2E36))
            ctx.fill(CGRect(x: 0, y: H * 0.82, width: W, height: H * 0.18))
            ctx.setFillColor(rgb(0xFFD27A))
            for i in 0..<6 {
                let x0 = W * (0.45 + CGFloat(i) * 0.025)
                ctx.fill(CGRect(x: x0, y: H * 0.42, width: W * 0.01, height: H * 0.03))
            }
            let lamps: [(CGFloat, CGFloat, Bool)] = [(0.08, 0.62, false), (0.26, 0.66, true), (0.5, 0.6, false), (0.7, 0.64, true), (0.9, 0.62, false)]
            for (lx, ly, white) in lamps {
                let c = CGPoint(x: lx * W, y: ly * H), r = W * 0.012
                let glow: UInt32 = white ? 0xDDE8FF : 0xFF9A2E
                if let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [rgb(glow, 0xC0 / 255), rgb(glow, 0)] as CFArray,
                                      locations: [0, 1]) {
                    ctx.drawRadialGradient(g, startCenter: c, startRadius: 0, endCenter: c, endRadius: r * 4, options: [])
                }
                ctx.setFillColor(rgb(0xFFFFFF))
                ctx.fillEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
            }
            ctx.setFillColor(rgb(0xFFFFFF))
            for x in [0.36, 0.41] as [CGFloat] {
                let r = W * 0.007
                ctx.fillEllipse(in: CGRect(x: W * x - r, y: H * 0.88 - r, width: 2 * r, height: 2 * r))
            }
        }
    }

    /// Sample through a LUT value or a film stock at full strength ("" both: the sample itself)
    static func look(_ s: SamplePixels, lut: String, film: String = "") -> SamplePixels {
        if lut.isEmpty && film.isEmpty { return s }
        guard let out = SCAMFilm.lookThumbnail(s.rgba, width: s.width, height: s.height, lut: lut, film: film) else { return s }
        return SamplePixels(rgba: out, width: s.width, height: s.height)
    }

    /// Sample through a lens filter at `strength` 0..1 with its stored fine controls
    static func lens(_ s: SamplePixels, filter: String, strength: Double, tune: String?) -> SamplePixels {
        guard let out = SCAMFilm.lensFilterThumbnail(s.rgba, width: s.width, height: s.height, filter: filter, strength: Float(strength), tune: tune)
        else { return s }
        return SamplePixels(rgba: out, width: s.width, height: s.height)
    }
}

/// The pictures of the film pages, made off the main thread one at a time and kept (FilmLutPreference's WORKER and PIXELS cache):
/// nil until ready, the page drawn again then
@MainActor
@Observable
final class SampleCache {
    static let shared = SampleCache()
    private(set) var store: [String: SamplePixels] = [:]
    @ObservationIgnored private var pending = Set<String>()
    @ObservationIgnored private let worker = DispatchQueue(label: "scam.film-samples", qos: .userInitiated)

    func get(_ key: String, _ make: @escaping @Sendable () -> SamplePixels) -> SamplePixels? {
        if let p = store[key] { return p }
        guard !pending.contains(key) else { return nil }
        pending.insert(key)
        worker.async {
            let p = make()
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    if self.store.count > 64 { self.store.removeAll() }
                    self.store[key] = p
                    self.pending.remove(key)
                }
            }
        }
        return nil
    }

    func forget(containing part: String) { store = store.filter { !$0.key.contains(part) } }
}

/// ItemOrder (settings/ItemOrder.java, P279): the user's order of the LUTs and of the lens filters, one stored list each (one item a
/// line); the first item («Нет», «Без фильтра») stays first, an item the order does not know comes after the known ones, an item no
/// longer there is dropped.
enum ItemOrder {
    static let lutKey = "pref_film_lut_order", filterKey = "pref_lens_filter_order"

    static func apply(_ items: [String], _ stored: String) -> [String] {
        guard let first = items.first else { return [] }
        var rest = Array(items.dropFirst())
        var out = [first]
        for s in stored.components(separatedBy: "\n") where !s.isEmpty {
            if let i = rest.firstIndex(of: s) { out.append(rest.remove(at: i)) }
        }
        return out + rest
    }

    static func ordered(_ key: String, _ items: [String]) -> [String] { apply(items, SettingsStore.shared.string(key, "")) }

    /// The item at `from` put at `to` (neither the fixed first one), stored
    static func move(_ key: String, _ ordered: [String], from: Int, to: Int) {
        var l = ordered
        guard from >= 1, from < l.count else { return }
        let item = l.remove(at: from)
        l.insert(item, at: max(1, min(l.count, to)))
        SettingsStore.shared.set(key, l.count <= 1 ? "" : l.dropFirst().joined(separator: "\n"))
    }
}

/// LutCompareView (SIMPLE_UI §4c): the before / after preview - «До» left of a white cut, «После» right of it, a white knob; the cut
/// follows a horizontal drag or a tap
struct LutCompareView: View {
    let before: CGImage?
    let after: CGImage?
    @State private var cut: CGFloat = 0.5

    var body: some View {
        GeometryReader { g in
            let x = g.size.width * cut
            ZStack(alignment: .topLeading) {
                if let after { Image(decorative: after, scale: 1).resizable().scaledToFill().frame(width: g.size.width, height: g.size.height) }
                if let before {
                    Image(decorative: before, scale: 1).resizable().scaledToFill().frame(width: g.size.width, height: g.size.height)
                        .mask(alignment: .leading) { Rectangle().frame(width: x) }
                }
                Rectangle().fill(Color.white).frame(width: 2, height: g.size.height).offset(x: x - 1)
                Circle().fill(Color.white).frame(width: 30, height: 30)
                    .overlay(Image(systemName: "chevron.left.chevron.right").font(.app(12, 700)).foregroundStyle(.black))
                    .position(x: x, y: g.size.height / 2)
                tag(L("film.before")).opacity(Double(min(1, cut * 4))).padding(8)
                tag(L("film.after")).opacity(Double(min(1, (1 - cut) * 4))).padding(8)
                    .frame(width: g.size.width, alignment: .trailing)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Color.white.opacity(0.12)))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in cut = max(0, min(1, v.location.x / max(1, g.size.width))) })
        }
        .aspectRatio(4 / 3, contentMode: .fit)
        .accessibilityElement()
        .accessibilityLabel(L("film.compare"))
        .accessibilityAdjustableAction { dir in cut = max(0, min(1, cut + (dir == .increment ? 0.1 : -0.1))) }
    }

    private func tag(_ text: String) -> some View {
        Text(text).font(.app(11, 600)).foregroundStyle(.white).padding(.horizontal, 7).padding(.vertical, 3)
            .background(Color.black.opacity(0.45), in: Capsule())
    }
}

/// The sample chips over a preview
struct SampleChips: View {
    let names: [String]
    @Binding var selected: Int

    var body: some View {
        HStack(spacing: 8) {
            ForEach(names.indices, id: \.self) { i in
                Button { selected = i } label: {
                    Text(names[i]).font(.app(12.5)).padding(.horizontal, 12).frame(minHeight: 34)
                        .foregroundStyle(i == selected ? Color.black : Color.white)
                        .background(i == selected ? AnyShapeStyle(AccentPalette.settingsColor) : AnyShapeStyle(Color(white: 0.23)), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// A strip of cards (the sample through each item, its name and hint; the chosen one with an accent border). StripReorder (P279):
/// a card is held and dragged onto another one to take its place; the first card neither moves nor is taken (a drop on it puts
/// the card second).
struct CardStrip: View {
    let items: [String]
    let chosen: String
    let name: (String) -> String
    let hint: (String) -> String
    let thumb: (String) -> CGImage?
    let choose: (String) -> Void
    let move: (Int, Int) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 10) {
                    ForEach(Array(items.enumerated()), id: \.element) { index, item in
                        card(item).id(item)
                            .modifier(StripDrag(index: index, item: item))
                            .dropDestination(for: String.self) { dropped, _ in
                                guard let from = dropped.first.flatMap({ items.firstIndex(of: $0) }), from != index else { return false }
                                move(from, max(1, index))
                                return true
                            }
                    }
                }
                .padding(.vertical, 4)
            }
            .onAppear { proxy.scrollTo(chosen, anchor: .center) }
        }
    }

    private func card(_ item: String) -> some View {
        let on = item == chosen
        return Button { choose(item) } label: {
            VStack(alignment: .leading, spacing: 4) {
                Group {
                    if let img = thumb(item) {
                        Image(decorative: img, scale: 1).resizable().scaledToFill()
                    } else {
                        Color(white: 0.2)
                    }
                }
                .frame(width: 104, height: 78)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(on ? AccentPalette.settingsColor : Color.clear, lineWidth: 2))
                Text(name(item)).font(.app(12.5, on ? 600 : 400)).foregroundStyle(.white).lineLimit(1)
                Text(hint(item)).font(.app(11)).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(width: 104)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// StripReorder's long press: every card but the first can be dragged
private struct StripDrag: ViewModifier {
    let index: Int
    let item: String
    func body(content: Content) -> some View {
        if index > 0 { content.draggable(item) } else { content }
    }
}
