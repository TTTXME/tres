import Foundation

/// The watermark of a shot (Android PhotoWatermark.java, P628), drawn into the finished photo - upright, after every crop and
/// resize, before the encoder - so the file carries it in the chosen corner. As on Android, the Ultra HDR gain map is not
/// watermarked: ScamProcessor computes the map before the watermark and draws only on the SDR base (the map stays the pipeline's).
/// A shot is never lost to its watermark: anything that fails leaves the photo as it was (logged).
enum PhotoWatermark {
    /// Whether the watermark switch is on (pref_show_watermark_key, default on).
    static func on(_ store: SettingsStore = .shared) -> Bool {
        WatermarkSpec.isOn(store.values)
    }

    /// The shot's facts: the camera's own photo's ImageIO properties first, the DNG's EXIF for what they lack, and the time of
    /// `shotTime` in the phone's zone when neither has one (Android: the shot's wall clock, ParseExif.captureTimeMs).
    static func info(metadata: [String: Any], dng: Data?, shotTime: Date) -> WatermarkText.Info {
        var i = WatermarkExif.info(metadata, fallback: dng.flatMap { WatermarkExif.properties(of: $0) })
        if i.timeMs <= 0 {
            i.timeMs = Int64((shotTime.timeIntervalSince1970 * 1000).rounded())
            i.zone = TimeZone.current
        }
        return i
    }

    /// The stored settings' watermark drawn into `rgba` (RGBA8 rows top-down, `width` x `height`, upright) when the switch is on;
    /// returns the plan drawn (nil: none). `log` gets one line either way.
    @discardableResult
    static func apply(_ rgba: inout Data, width: Int, height: Int, metadata: [String: Any], dng: Data?, shotTime: Date,
                      store: SettingsStore = .shared, log: (String) -> Void) -> WatermarkPainter.Plan? {
        guard on(store) else { return nil }
        let spec = WatermarkSpec.stored(store)
        let facts = Self.info(metadata: metadata, dng: dng, shotTime: shotTime)
        return draw(&rgba, width: width, height: height, spec: spec, info: facts, log: log)
    }

    /// The watermark of `spec` with the facts `info` drawn into `rgba` (PhotoWatermark.draw without the record); nil when nothing
    /// was drawn (every field and the glyph off, a photo too small, rows of another size).
    @discardableResult
    static func draw(_ rgba: inout Data, width: Int, height: Int, spec: WatermarkSpec, info: WatermarkText.Info,
                     fonts: WatermarkFonts = .shared, log: (String) -> Void) -> WatermarkPainter.Plan? {
        let started = Date()
        let text = WatermarkText.of(spec, info)
        if !text.any && spec.layout != .frame {
            log("watermark: nothing to draw (every field and the glyph off)")
            return nil
        }
        guard width > 0, height > 0, rgba.count >= width * 4 * height else {
            log("watermark: not drawn, \(rgba.count) bytes for a \(width)x\(height) photo")
            return nil
        }
        let plan: WatermarkPainter.Plan? = rgba.withUnsafeMutableBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return nil }
            return WatermarkPainter.drawOn(WatermarkRows(base: base, width: width, height: height, bytesPerRow: width * 4), spec, text,
                                           fonts: fonts)
        }
        guard let plan else {
            log("watermark: not drawn (empty plan or no drawing context) on \(width)x\(height)")
            return nil
        }
        let area = WatermarkArea.of(plan)
        let b = plan.bounds
        log("watermark \(spec.layout.rawValue) \(spec.position.rawValue) on \(width)x\(height) at "
            + "[\(Int(b.left)),\(Int(b.top))][\(Int(b.right)),\(Int(b.bottom))] \(plan.whiteInk ? "white" : "black")"
            + " lines \(plan.runs.count) logo \(plan.logo != nil), touched \(area.count) rects \(WatermarkArea.pixels(area)) px"
            + " in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
        return plan
    }
}
