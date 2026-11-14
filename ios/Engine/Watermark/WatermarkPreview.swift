import CoreGraphics
import SwiftUI
import UIKit

/// The watermark page's live preview (Android WatermarkPreviewPreference, P628) for the settings row «WatermarkPreviewPreference»:
/// a sample photo (the settings' drawn landscape, LutSamples.scene) with the watermark of the stored settings drawn by the shot's
/// own code, in a rounded card; a segmented switch under it turns the sample landscape / portrait. Every change of a watermark
/// setting redraws it with a cross-fade (ease-out, 260 ms). The values are a sample shot's (this iPhone, 24mm f/1.8 1/120s ISO
/// 100, now); with the switch off the sample is shown alone.
struct WatermarkPreview: View {
    /// The sample's orientation, kept while the app runs (as Android's static field).
    private static var portraitKept = false
    @State private var portrait = WatermarkPreview.portraitKept
    /// The rendered sample and the settings it shows (its identity for the cross-fade).
    @State private var shown: (key: String, image: CGImage)?
    @Environment(\.displayScale) private var displayScale

    private var store: SettingsStore { SettingsStore.shared }

    var body: some View {
        VStack(spacing: 10) {
            GeometryReader { geo in
                let spec = WatermarkSpec.stored(store)
                let on = PhotoWatermark.on(store)
                let box = Self.fit(geo.size, portrait: portrait)
                let key = "\(spec)|\(on)|\(portrait)|\(Int(box.width))x\(Int(box.height))|\(displayScale)"
                ZStack {
                    RoundedRectangle(cornerRadius: 18).fill(Color(white: 0.11))
                    if let shown {
                        Image(decorative: shown.image, scale: displayScale)
                            .resizable()
                            .frame(width: box.width, height: box.height)
                            .clipShape(RoundedRectangle(cornerRadius: 18))
                            .id(shown.key)
                            .transition(.opacity)
                    }
                    RoundedRectangle(cornerRadius: 18).strokeBorder(Color.white.opacity(0.12), lineWidth: 1)
                }
                .task(id: key) {
                    guard let image = Self.render(spec: spec, on: on, width: Int((box.width * displayScale).rounded()),
                                                  height: Int((box.height * displayScale).rounded()), now: Date()) else { return }
                    // the first picture appears at once, every later one cross-fades from the one shown
                    if shown == nil { shown = (key, image) } else { withAnimation(.easeOut(duration: 0.26)) { shown = (key, image) } }
                }
            }
            .frame(height: 236)
            .accessibilityElement()
            .accessibilityLabel(L("watermark.preview"))
            Picker("", selection: Binding(get: { portrait }, set: { portrait = $0; WatermarkPreview.portraitKept = $0 })) {
                Text(L("watermark.landscape")).tag(false)
                Text(L("watermark.portrait")).tag(true)
            }
            .pickerStyle(.segmented)
        }
        .padding(.vertical, 4)
    }

    /// The photo's box in the card: landscape 4:3 filling the width, portrait 3:4 at the card's height.
    static func fit(_ size: CGSize, portrait: Bool) -> CGSize {
        if !portrait {
            let ph = min(size.height, size.width * 0.75)
            return CGSize(width: ph / 0.75, height: ph)
        }
        return CGSize(width: size.height * 0.75, height: size.height)
    }

    /// The sample photo `width` x `height` with the watermark of `spec` drawn in by the shot's code; with the switch off the sample
    /// alone.
    static func render(spec: WatermarkSpec, on: Bool, width: Int, height: Int, now: Date) -> CGImage? {
        guard width > 0, height > 0 else { return nil }
        var rgba = Data(count: width * 4 * height)
        let w = Float(width), h = Float(height)
        rgba.withUnsafeMutableBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            let rows = WatermarkRows(base: base, width: width, height: height, bytesPerRow: width * 4)
            guard let context = rows.context() else { return }
            SampleScene.draw(context, w: w, h: h, seed: 0, zoom: width > height ? 1.4 : 1.9)
            guard on else { return }
            let info = WatermarkText.Info.sample(device: sampleDevice, now: Int64((now.timeIntervalSince1970 * 1000).rounded()))
            WatermarkPainter.drawOn(rows, spec, WatermarkText.of(spec, info), fonts: .shared)
        }
        guard let provider = CGDataProvider(data: rgba as CFData), let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: space,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider, decode: nil,
                       shouldInterpolate: true, intent: .defaultIntent)
    }

    /// WatermarkPreviewPreference.sampleDevice: the phone the page runs on as the photo's EXIF names it («Apple iPhone 16 Pro»,
    /// DeviceName's market name).
    static var sampleDevice: String { DeviceName.device }
}

/// The settings' drawn landscape (Android LutSamples.scene, the concept's drawScene(seed, zoom)): sky, sun, two ridges, water with
/// the sun's reflection, trees. Drawn into a context whose user space is y down (WatermarkRows.context).
enum SampleScene {
    /// Sky top, sky middle, horizon, far ridge, near ridge, sun.
    static let palettes: [[UInt32]] = [
        [0x203C55, 0xB9826B, 0xF3C39B, 0x3D4660, 0x262C3A, 0xFFD9A0],
        [0x1D4A63, 0x6FA3B8, 0xD8E6E3, 0x3B5D63, 0x22363B, 0xFFF6D8],
        [0x2B2347, 0x9D5F7A, 0xF0A07E, 0x4A3B5A, 0x271F33, 0xFFC28A],
    ]

    /// The concept's rnd(seed): a small LCG.
    private struct Rnd {
        var s: Int64
        init(_ seed: Int) { s = Int64(seed) * 9301 + 49297 }
        mutating func next() -> Float {
            s = (s * 9301 + 49297) % 233280
            return Float(s) / 233280
        }
    }

    private static func colour(_ rgb: UInt32, _ a: UInt32 = 0xFF) -> CGColor {
        WatermarkPainter.cgColor((a << 24) | (rgb & 0xFF_FFFF))
    }

    private static func gradient(_ colours: [CGColor], _ stops: [CGFloat]) -> CGGradient? {
        CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: colours as CFArray, locations: stops)
    }

    static func draw(_ ctx: CGContext, w: Float, h: Float, seed: Int, zoom: Float) {
        let p = palettes[seed % palettes.count]
        var r = Rnd(seed + 3)
        ctx.saveGState()
        let cx = CGFloat(w * 0.62), cy = CGFloat(h * 0.47)
        ctx.translateBy(x: cx, y: cy)
        ctx.scaleBy(x: CGFloat(zoom), y: CGFloat(zoom))
        ctx.translateBy(x: -cx, y: -cy)
        let W = CGFloat(w), H = CGFloat(h)
        // the sky: a clamped vertical gradient to 62 % of the height
        ctx.saveGState()
        ctx.clip(to: CGRect(x: -W, y: -H, width: 3 * W, height: 3 * H))
        if let g = gradient([colour(p[0]), colour(p[1]), colour(p[2])], [0, 0.6, 1]) {
            ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: 0), end: CGPoint(x: 0, y: H * 0.62),
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        }
        ctx.restoreGState()
        let sx = w * (0.55 + r.next() * 0.2), sy = h * (0.38 + r.next() * 0.08), sr = w * 0.055
        // the sun's glow and disc
        ctx.saveGState()
        ctx.clip(to: CGRect(x: CGFloat(sx - sr * 6), y: CGFloat(sy - sr * 6), width: CGFloat(sr * 12), height: CGFloat(sr * 12)))
        if let g = gradient([colour(p[5], 0xCC), colour(p[5], 0)], [0, 1]) {
            let c = CGPoint(x: CGFloat(sx), y: CGFloat(sy))
            ctx.drawRadialGradient(g, startCenter: c, startRadius: 0, endCenter: c, endRadius: CGFloat(sr * 6), options: [.drawsAfterEndLocation])
        }
        ctx.restoreGState()
        ctx.setFillColor(colour(p[5]))
        ctx.fillEllipse(in: CGRect(x: CGFloat(sx - sr), y: CGFloat(sy - sr), width: CGFloat(2 * sr), height: CGFloat(2 * sr)))
        ridge(ctx, w, h, seed, 0.6, 0.16, colour(p[3]), 3.1)
        ridge(ctx, w, h, seed, 0.66, 0.09, colour(p[4]), 5.3)
        // the water
        ctx.saveGState()
        ctx.clip(to: CGRect(x: -W, y: H * 0.66, width: 3 * W, height: H * 3 - H * 0.66))
        if let g = gradient([colour(p[1]), colour(p[4])], [0, 1]) {
            ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: H * 0.66), end: CGPoint(x: 0, y: H),
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        }
        ctx.restoreGState()
        // the sun's reflection
        ctx.setFillColor(colour(p[5], 0x88))
        let px = Swift.max(1, w / 360)
        for i in 0..<14 {
            let fi = Float(i)
            let yy = h * 0.68 + fi * h * 0.022, x = sx - sr * (1.4 - fi * 0.06) - r.next() * 6 * px
            ctx.fill(CGRect(x: CGFloat(x), y: CGFloat(yy), width: CGFloat(sr * (2.8 - fi * 0.1)), height: CGFloat(2 * px)))
        }
        // the trees
        ctx.setFillColor(WatermarkPainter.cgColor(0xFF0D_1116))
        for i in 0..<9 {
            let fi = Float(i)
            let tx = w * (0.02 + fi * 0.05 + r.next() * 0.03), th = h * (0.16 + r.next() * 0.14), tw = th * 0.32, by = h * 0.7
            let tree = CGMutablePath()
            tree.move(to: CGPoint(x: CGFloat(tx), y: CGFloat(by - th)))
            tree.addLine(to: CGPoint(x: CGFloat(tx - tw), y: CGFloat(by)))
            tree.addLine(to: CGPoint(x: CGFloat(tx + tw), y: CGFloat(by)))
            tree.closeSubpath()
            ctx.addPath(tree)
            ctx.fillPath()
        }
        ctx.fill(CGRect(x: -W, y: H * 0.7, width: W * 1.5, height: H * 0.012))
        ctx.restoreGState()
    }

    private static func ridge(_ ctx: CGContext, _ w: Float, _ h: Float, _ seed: Int, _ base: Float, _ amp: Float, _ colour: CGColor,
                              _ f: Float) {
        let path = CGMutablePath()
        path.move(to: CGPoint(x: CGFloat(-w), y: CGFloat(h * 2)))
        var x = -w
        while x <= w * 2 {
            let y = Double(h * base) - abs(sin(Double(x / w * f) + Double(seed))) * Double(h * amp)
                - sin(Double(x / w * f) * 2.7 + Double(seed * 2)) * Double(h * amp) * 0.35
            path.addLine(to: CGPoint(x: CGFloat(x), y: CGFloat(y)))
            x += w / 60
        }
        path.addLine(to: CGPoint(x: CGFloat(w * 2), y: CGFloat(h * 2)))
        path.closeSubpath()
        ctx.setFillColor(colour)
        ctx.addPath(path)
        ctx.fillPath()
    }
}
