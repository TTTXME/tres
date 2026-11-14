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
        drawRecorded(&rgba, width: width, height: height, spec: spec, info: info, fonts: fonts, record: false, log: log).plan
    }

    /// A photo's watermark as drawn: the plan (nil: nothing drawn) and the record that takes it off again (nil: none kept).
    struct Drawn {
        var plan: WatermarkPainter.Plan?
        var record: SCAMWatermarkRecord?
    }

    /// P633 PhotoWatermark.applyRecorded: `apply` that also keeps the original pixels under the watermark and its spec
    /// (WatermarkPatch), which the shot appends to its file (appendRecord) so the gallery's editor can take it off or change it.
    static func applyRecorded(_ rgba: inout Data, width: Int, height: Int, metadata: [String: Any], dng: Data?, shotTime: Date,
                              store: SettingsStore = .shared, log: (String) -> Void) -> Drawn {
        guard on(store) else { return Drawn() }
        let spec = WatermarkSpec.stored(store)
        let facts = Self.info(metadata: metadata, dng: dng, shotTime: shotTime)
        return drawRecorded(&rgba, width: width, height: height, spec: spec, info: facts, record: true, log: log)
    }

    /// P633 WatermarkStore.append for the shot's encoded photo: `file` with `record` at its end (the file as it was when its
    /// container is not one the record knows); one log line.
    static func appendRecord(_ record: SCAMWatermarkRecord, to file: Data, log: (String) -> Void) -> Data {
        guard let out = WatermarkStore.append(record, to: file) else {
            log("watermark record not appended: the encoded photo is neither JPEG, WebP nor HEIC (the watermark cannot be taken off later)")
            return file
        }
        log("watermark record appended: \(record.summary), the file \(file.count) -> \(out.count) bytes")
        return out
    }

    /// PhotoWatermark.draw: the watermark of `spec` with the facts `info` drawn into `rgba` - the camera's drawing, which the
    /// gallery's editor uses for its copy too; `record`: the pixels under it kept first (WatermarkArea, WatermarkPatch; a failure
    /// there costs the record only). Nothing drawn: no plan, no record.
    static func drawRecorded(_ rgba: inout Data, width: Int, height: Int, spec: WatermarkSpec, info: WatermarkText.Info,
                             fonts: WatermarkFonts = .shared, record: Bool, displayP3: Bool = false, log: (String) -> Void) -> Drawn {
        let started = Date()
        let text = WatermarkText.of(spec, info)
        if !text.any && spec.layout != .frame {
            log("watermark: nothing to draw (every field and the glyph off)")
            return Drawn()
        }
        guard width > 0, height > 0, rgba.count >= width * 4 * height else {
            log("watermark: not drawn, \(rgba.count) bytes for a \(width)x\(height) photo")
            return Drawn()
        }
        var kept: SCAMWatermarkRecord?
        var recordNote = ""
        let plan: WatermarkPainter.Plan? = rgba.withUnsafeMutableBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return nil }
            let rows = WatermarkRows(base: base, width: width, height: height, bytesPerRow: width * 4)
            var keep: ((WatermarkPainter.Plan) -> Void)?
            if record {
                keep = { plan in
                    let t0 = Date()
                    do {
                        let r = try WatermarkPatch.capture(rows, plan: plan, spec: spec, displayP3: displayP3)
                        kept = r
                        recordNote = ", record \(r.summary) in \(Int(Date().timeIntervalSince(t0) * 1000)) ms"
                    } catch {
                        recordNote = ", record not kept (the watermark cannot be taken off later): \(error.localizedDescription)"
                    }
                }
            }
            return WatermarkPainter.drawOn(rows, spec, text, fonts: fonts, beforeDrawing: keep)
        }
        guard let plan else {
            log("watermark: not drawn (empty plan or no drawing context) on \(width)x\(height)")
            return Drawn()
        }
        let area = WatermarkArea.of(plan)
        let b = plan.bounds
        log("watermark \(spec.layout.rawValue) \(spec.position.rawValue) on \(width)x\(height) at "
            + "[\(Int(b.left)),\(Int(b.top))][\(Int(b.right)),\(Int(b.bottom))] \(plan.whiteInk ? "white" : "black")"
            + " lines \(plan.runs.count) logo \(plan.logo != nil), touched \(area.count) rects \(WatermarkArea.pixels(area)) px"
            + recordNote + " in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
        return Drawn(plan: plan, record: kept)
    }
}
