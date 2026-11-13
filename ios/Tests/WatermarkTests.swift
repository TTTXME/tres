import CoreGraphics
import ImageIO
import XCTest
@testable import SCAMERA

/// The text measures of the host run of the Android code (ios/tools/watermark_host/stubs/android/graphics/Paint.java): fixed
/// advances (em) per UTF-16 unit, 4 % wider from weight 600, the letter spacing added to each; ascent -0.93, descent 0.24 em.
/// With them the Swift layout must give the Java layout's numbers (WatermarkGolden.layouts).
struct FixedFonts: WatermarkFontSource {
    static func advance(_ c: UInt16, mono: Bool) -> Float {
        if mono { return 0.6 }
        if c == 0x20 { return 0.28 }
        if c >= 0x30 && c <= 0x39 { return 0.56 }
        if c == 0x2026 { return 0.9 }
        if c >= 0x41 && c <= 0x5A { return 0.66 }
        return 0.52
    }

    func metrics(_ font: WatermarkSpec.Font, weight: Int, size: Float) -> (ascent: Float, descent: Float) {
        (-0.93 * size, 0.24 * size)
    }

    func measure(_ text: String, font: WatermarkSpec.Font, weight: Int, size: Float, spacing: Float) -> Float {
        let mono = font == .mono
        let k: Float = weight >= 600 ? 1.04 : 1
        var w: Float = 0
        for c in text.utf16 { w += size * (FixedFonts.advance(c, mono: mono) * k + spacing) }
        return w
    }
}

final class WatermarkTests: XCTestCase {
    static func info() -> WatermarkText.Info {
        var i = WatermarkText.Info.sample(device: "vivo X200 Ultra", now: WatermarkGolden.now)
        i.zone = TimeZone(secondsFromGMT: 0)
        return i
    }

    /// WatermarkSpec.from reads the stored values as the Android code does (codes, clamps, rint, Booleans as strings / numbers).
    func testStoredValuesReadAsAndroid() {
        for (n, c) in WatermarkGolden.stored.enumerated() {
            let s = WatermarkSpec.from(c.prefs)
            XCTAssertEqual([s.layout.rawValue, s.position.rawValue, s.font.rawValue, s.colour.rawValue, s.dateFormat.rawValue, s.text], c.spec, "\(n)")
            XCTAssertEqual(s.size, c.numbers[0], accuracy: 1e-5, "\(n) size")
            XCTAssertEqual(s.opacity, c.numbers[1], accuracy: 1e-5, "\(n) opacity")
            XCTAssertEqual(s.margin, c.numbers[2], accuracy: 1e-5, "\(n) margin")
            XCTAssertEqual([s.showText, s.showDevice, s.showLens, s.showAperture, s.showShutter, s.showIso, s.showTime, s.logo], c.fields, "\(n)")
        }
        XCTAssertTrue(WatermarkSpec.isOn([:]))
        XCTAssertFalse(WatermarkSpec.isOn([WatermarkSpec.keyOn: false]))
    }

    /// Every subset of the eight fields says what WatermarkText.java says (groups, column, the band's two sides).
    func testEverySubsetReadsAsAndroid() {
        XCTAssertEqual(WatermarkGolden.subsets.count, 256)
        for c in WatermarkGolden.subsets {
            let m = c.mask
            let s = WatermarkSpec().only(text: m & 1 != 0, device: m & 2 != 0, lens: m & 4 != 0, aperture: m & 8 != 0, shutter: m & 16 != 0,
                                         iso: m & 32 != 0, time: m & 64 != 0, logo: m & 128 != 0)
            let t = WatermarkText.of(s, Self.info(), ru: false)
            XCTAssertEqual(t.title, c.title, "\(m)")
            XCTAssertEqual(t.exif, c.exif, "\(m)")
            XCTAssertEqual(t.time, c.time, "\(m)")
            XCTAssertEqual(t.column, c.column, "\(m)")
            XCTAssertEqual(t.leading, c.leading, "\(m)")
            XCTAssertEqual(t.trailing, c.trailing, "\(m)")
            XCTAssertEqual(t.logo, m & 128 != 0)
        }
    }

    func testTimeInEveryFormatAsAndroid() throws {
        for c in WatermarkGolden.times {
            let format = try XCTUnwrap(WatermarkSpec.DateFormat(rawValue: c.format))
            XCTAssertEqual(WatermarkText.time(c.ms, zone: TimeZone(secondsFromGMT: c.offset), format: format, ru: c.ru), c.text,
                           "\(c.ms) \(c.offset) \(c.format) ru \(c.ru)")
        }
    }

    func testValuesFormattedAsAndroid() {
        for (v, s) in WatermarkGolden.shutters { XCTAssertEqual(WatermarkText.shutter(v), s, "shutter \(v)") }
        for (v, s) in WatermarkGolden.apertures { XCTAssertEqual(WatermarkText.aperture(v), s, "aperture \(v)") }
        for (v, s) in WatermarkGolden.numbers { XCTAssertEqual(WatermarkText.number(v), s, "number \(v)") }
        for (v, s) in WatermarkGolden.cleans { XCTAssertEqual(WatermarkText.clean(v), s, "clean \(v)") }
        var i = Self.info()
        i.focal35 = 0
        XCTAssertEqual(WatermarkText.focal(i), "6.9mm")
    }

    private func assertRect(_ r: WatermarkRect?, _ e: [Float]?, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        guard let e else { XCTAssertNil(r, what, file: file, line: line); return }
        guard let r else { XCTFail("\(what): none, expected \(e)", file: file, line: line); return }
        for (a, b) in zip([r.left, r.top, r.right, r.bottom], e) { XCTAssertEqual(a, b, accuracy: 0.01, "\(what) \(r) vs \(e)", file: file, line: line) }
    }

    /// WatermarkPainter.plan and WatermarkArea give the Java plan's geometry for every layout / position / size case (the same
    /// measures): runs, glyph, plate, window, hairline, «Авто»'s sample, the bounds and the touched blocks.
    func testLayoutsMatchAndroid() {
        let fonts = FixedFonts()
        for c in WatermarkGolden.layouts {
            let spec = WatermarkSpec.from(c.prefs)
            let text = WatermarkText.of(spec, Self.info(), ru: false)
            let plan = WatermarkPainter.plan(spec, text, width: c.width, height: c.height, fonts: fonts)
            XCTAssertEqual(plan.unit, c.unit, accuracy: 1e-3, c.name)
            XCTAssertEqual(plan.line, c.line, accuracy: 1e-3, c.name)
            XCTAssertEqual(plan.radius, c.radius, accuracy: 1e-3, c.name)
            XCTAssertEqual(plan.windowRadius, c.windowRadius, accuracy: 1e-3, c.name)
            XCTAssertEqual(plan.runs.map(\.text), c.runs.map(\.text), c.name)
            for (r, e) in zip(plan.runs, c.runs) {
                XCTAssertEqual(r.x, e.x, accuracy: 0.01, "\(c.name) \(e.text) x")
                XCTAssertEqual(r.baseline, e.baseline, accuracy: 0.01, "\(c.name) \(e.text) baseline")
                XCTAssertEqual(r.size, e.size, accuracy: 1e-3, "\(c.name) \(e.text) size")
                XCTAssertEqual(r.weight, e.weight, "\(c.name) \(e.text)")
                XCTAssertEqual(r.main, e.main, "\(c.name) \(e.text)")
                assertRect(r.box, e.box, "\(c.name) \(e.text) box")
            }
            assertRect(plan.logo, c.logo, "\(c.name) logo")
            assertRect(plan.plate, c.plate, "\(c.name) plate")
            assertRect(plan.window, c.window, "\(c.name) window")
            assertRect(plan.hairline, c.hairline, "\(c.name) hairline")
            assertRect(plan.sample, c.sample, "\(c.name) sample")
            assertRect(plan.bounds, c.bounds, "\(c.name) bounds")
            XCTAssertEqual(WatermarkArea.of(plan).flatMap { [$0.left, $0.top, $0.right, $0.bottom] }, c.area, "\(c.name) area")
        }
    }

    // ───── Drawing (CoreText: the geometry above, not Android's pixels) ─────

    private func rows(_ w: Int, _ h: Int, grey: UInt8, _ body: (WatermarkRows) -> Void) -> [UInt8] {
        var bytes = [UInt8](repeating: grey, count: w * 4 * h)
        bytes.withUnsafeMutableBufferPointer { body(WatermarkRows(base: $0.baseAddress!, width: w, height: h, bytesPerRow: w * 4)) }
        return bytes
    }

    /// {left, top, right, bottom} of the pixels brighter than 160 (red channel).
    private func ink(_ b: [UInt8], _ w: Int, _ h: Int) -> [Int] {
        var l = w, t = h, r = -1, bo = -1
        for y in 0..<h { for x in 0..<w where b[(y * w + x) * 4] > 160 { l = min(l, x); r = max(r, x); t = min(t, y); bo = max(bo, y) } }
        return [l, t, r, bo]
    }

    /// The date alone in white on a black photo lands in the bottom-left corner and reads across, in landscape and portrait.
    func testTheDateIsDrawnUprightInTheChosenCorner() {
        var s = WatermarkSpec().only(text: false, device: false, lens: false, aperture: false, shutter: false, iso: false, time: true, logo: false)
        s.colour = .white
        s.size = 12
        for (w, h) in [(800, 600), (600, 800)] {
            let b = rows(w, h, grey: 0) { XCTAssertNotNil(WatermarkPainter.drawOn($0, s, WatermarkText.of(s, Self.info(), ru: false), fonts: .shared)) }
            let i = ink(b, w, h)
            XCTAssertTrue(i[2] > i[0], "\(w)x\(h) \(i)")
            XCTAssertTrue(i[2] < Int(Double(w) * 0.7) && i[0] < Int(Double(w) * 0.2), "\(w)x\(h) left half \(i)")
            XCTAssertTrue(i[1] > h / 2, "\(w)x\(h) bottom half \(i)")
            XCTAssertTrue(i[2] - i[0] > 3 * (i[3] - i[1]), "\(w)x\(h) reads across \(i)")
        }
    }

    func testAutoPicksTheInkByThePhoto() {
        let s = WatermarkSpec()
        for grey: UInt8 in [255, 0] {
            var white: Bool?
            _ = rows(400, 300, grey: grey) { white = WatermarkPainter.drawOn($0, s, WatermarkText.of(s, Self.info(), ru: false), fonts: .shared)?.whiteInk }
            XCTAssertEqual(white, grey == 0)
        }
        let none = WatermarkSpec().only(text: false, device: false, lens: false, aperture: false, shutter: false, iso: false, time: false, logo: false)
        let untouched = rows(40, 30, grey: 7) { XCTAssertNil(WatermarkPainter.drawOn($0, none, WatermarkText.of(none, Self.info(), ru: false), fonts: .shared)) }
        XCTAssertTrue(untouched.allSatisfy { $0 == 7 })
    }

    /// Every layout draws inside the photo with CoreText's measures too, and changes only the pixels WatermarkArea names.
    func testEveryLayoutDrawsInsideItsArea() {
        let (w, h) = (640, 480)
        for layout in WatermarkSpec.Layout.allCases {
            for position in WatermarkSpec.Position.allCases {
                var s = WatermarkSpec().only(text: true, device: true, lens: true, aperture: true, shutter: true, iso: true, time: true, logo: true)
                s.layout = layout
                s.position = position
                var plan: WatermarkPainter.Plan?
                let b = rows(w, h, grey: 90) { plan = WatermarkPainter.drawOn($0, s, WatermarkText.of(s, Self.info(), ru: false), fonts: .shared) }
                guard let plan else { XCTFail("\(layout) \(position): nothing drawn"); continue }
                let area = WatermarkArea.of(plan)
                var inArea = [Bool](repeating: false, count: w * h)
                for r in area { for y in r.top..<r.bottom { for x in r.left..<r.right { inArea[y * w + x] = true } } }
                var outside = 0
                for p in 0..<(w * h) where !inArea[p] && (b[p * 4] != 90 || b[p * 4 + 1] != 90 || b[p * 4 + 2] != 90) { outside += 1 }
                XCTAssertEqual(outside, 0, "\(layout) \(position): pixels changed outside WatermarkArea")
                XCTAssertTrue(WatermarkRect(-0.5, -0.5, Float(w) + 0.5, Float(h) + 0.5).contains(plan.bounds), "\(layout) \(position) \(plan.bounds)")
            }
        }
    }

    // ───── EXIF ─────

    func testFactsFromTheCamerasProperties() {
        let heic: [String: Any] = [
            kCGImagePropertyTIFFDictionary as String: [kCGImagePropertyTIFFMake as String: "Apple", kCGImagePropertyTIFFModel as String: "iPhone 16 Pro"],
            kCGImagePropertyExifDictionary as String: [
                kCGImagePropertyExifFocalLength as String: 6.765,
                kCGImagePropertyExifFocalLenIn35mmFilm as String: 24,
                kCGImagePropertyExifFNumber as String: 1.78,
                kCGImagePropertyExifExposureTime as String: 1 / 120.0,
                kCGImagePropertyExifISOSpeedRatings as String: [100],
                kCGImagePropertyExifDateTimeOriginal as String: "2026:11:11 14:05:09",
                kCGImagePropertyExifOffsetTimeOriginal as String: "+03:00",
            ] as [String: Any],
        ]
        let i = WatermarkExif.info(heic)
        XCTAssertEqual(i.device, "Apple iPhone 16 Pro")
        let all = WatermarkSpec().only(text: true, device: true, lens: true, aperture: true, shutter: true, iso: true, time: true, logo: false)
        let t = WatermarkText.of(all, i, ru: false)
        XCTAssertEqual(t.title, "SCAMERA  ·  Apple iPhone 16 Pro")
        XCTAssertEqual(t.exif, "24mm   f/1.8   1/120s   ISO 100")
        XCTAssertEqual(t.time, "11.11.2026  14:05") // the wall clock as written, whatever the phone's zone
        // a model that names the brand stands alone; the DNG fills what the photo lacks
        XCTAssertEqual(WatermarkExif.device(make: "vivo", model: "vivo X200 Ultra"), "vivo X200 Ultra")
        XCTAssertNil(WatermarkExif.device(make: " ", model: nil))
        let bare: [String: Any] = [kCGImagePropertyExifDictionary as String: [kCGImagePropertyExifFNumber as String: "1.8"]]
        let dng: [String: Any] = [kCGImagePropertyExifDictionary as String: [kCGImagePropertyExifISOSpeedRatings as String: [250],
                                                                             kCGImagePropertyExifExposureTime as String: "1/50"] as [String: Any]]
        let merged = WatermarkExif.info(bare, fallback: dng)
        XCTAssertEqual(merged.iso, 250)
        XCTAssertEqual(WatermarkText.shutter(merged.exposureTime), "1/50s")
        XCTAssertEqual(WatermarkText.aperture(merged.fNumber), "f/1.8")
        XCTAssertEqual(merged.timeMs, 0)
        XCTAssertEqual(WatermarkExif.zone("-05:00").secondsFromGMT(), -5 * 3600)
        XCTAssertEqual(WatermarkExif.time("garbage", zone: .current), 0)
    }
}
