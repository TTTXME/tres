import Foundation
import ImageIO
import os
import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A decoded picture for the editor: RGBA8 rows top-down in sRGB or Display P3, as stored (the EXIF orientation not applied).
struct DecodedPicture {
    let rgba: Data
    let width: Int
    let height: Int
    let p3: Bool
}

/// EditSource's decoding on the iPhone (ImageIO): the JPEG route's power-of-two preview, the other formats' 2048 px preview, the
/// export's full picture (EditSource.RegionReader's sample / BitmapReader's memory scale), in the file's own colour (Display P3
/// kept, anything else drawn into sRGB).
enum PhotoDecode {
    struct Info { let width: Int; let height: Int; let orientation: Int; let jpeg: Bool; let p3: Bool }

    static func info(_ data: Data) -> Info? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue, w > 0, h > 0 else { return nil }
        let o = (props[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        let type = (CGImageSourceGetType(src) as String?) ?? ""
        let jpeg = UTType(type)?.conforms(to: .jpeg) ?? false
        let profile = (props[kCGImagePropertyProfileName] as? String ?? "").lowercased()
        return Info(width: w, height: h, orientation: (1...8).contains(o) ? o : 1, jpeg: jpeg, p3: profile.contains("p3"))
    }

    /// The JPEG route's sample (EditSource.openJpeg) / the decoder route's scale: the preview's long side.
    static func previewSide(_ i: Info) -> Int {
        let full = max(i.width, i.height), side = Int(SCAMEditSession.previewSide())
        if i.jpeg {
            var sample = 1
            while full / (sample * 2) >= side { sample *= 2 }
            return (full + sample - 1) / sample
        }
        return min(full, side)
    }

    /// The export's long side for an output of at most outMax pixels of a crop covering cropFraction (EditSource.openReader).
    static func exportSide(_ i: Info, cropFraction: Double, outMax: Int64, budget: Int64) -> Int {
        let full = max(i.width, i.height)
        if i.jpeg {
            let crop = Double(i.width) * Double(i.height) * cropFraction
            var s = 1
            while crop / Double(s * s) > Double(outMax) * 4 && s < 1 << 12 { s *= 2 }
            return full / s
        }
        var k = min(1.0, (Double(outMax) / (Double(i.width) * Double(i.height))).squareRoot())
        let bytes = Double(i.width) * k * Double(i.height) * k * 8
        if bytes > Double(budget) { k *= (Double(budget) / bytes).squareRoot() }
        return max(1, Int((Double(full) * k).rounded()))
    }

    static func decode(_ data: Data, longSide: Int, p3: Bool) -> DecodedPicture? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return nil }
        let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0, h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        let image: CGImage?
        if longSide >= max(w, h) {
            image = CGImageSourceCreateImageAtIndex(src, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        } else {
            let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                            kCGImageSourceCreateThumbnailWithTransform: false,
                                            kCGImageSourceThumbnailMaxPixelSize: longSide,
                                            kCGImageSourceShouldCacheImmediately: true]
            image = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary)
        }
        guard let image else { return nil }
        return draw(image, p3: p3)
    }

    static func draw(_ image: CGImage, p3: Bool) -> DecodedPicture? {
        let w = image.width, h = image.height
        guard let space = CGColorSpace(name: p3 ? CGColorSpace.displayP3 : CGColorSpace.sRGB) else { return nil }
        var bytes = Data(count: w * h * 4)
        let ok = bytes.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.setFillColor(red: 0, green: 0, blue: 0, alpha: 1)
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? DecodedPicture(rgba: bytes, width: w, height: h, p3: p3) : nil
    }

    static func cgImage(rgba: Data, width: Int, height: Int) -> CGImage? {
        guard let provider = CGDataProvider(data: rgba as CFData), let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: space,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider, decode: nil,
                       shouldInterpolate: true, intent: .defaultIntent)
    }

    /// EditExporter.writeCopy: JPEG quality 95 with the original's EXIF / GPS, the orientation reset, the new size, Software SCAMERA.
    static func jpeg(_ image: CGImage, original: Data) -> Data? {
        var props: [CFString: Any] = [:]
        if let src = CGImageSourceCreateWithData(original as CFData, nil),
           let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] {
            for key in [kCGImagePropertyExifDictionary, kCGImagePropertyGPSDictionary, kCGImagePropertyTIFFDictionary] {
                if let d = p[key] { props[key] = d }
            }
        }
        var exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        exif[kCGImagePropertyExifPixelXDimension] = image.width
        exif[kCGImagePropertyExifPixelYDimension] = image.height
        props[kCGImagePropertyExifDictionary] = exif
        var tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        tiff[kCGImagePropertyTIFFOrientation] = 1
        tiff[kCGImagePropertyTIFFSoftware] = "SCAMERA"
        props[kCGImagePropertyTIFFDictionary] = tiff
        props[kCGImagePropertyOrientation] = 1
        props[kCGImageDestinationLossyCompressionQuality] = 0.95
        let out = NSMutableData()
        guard let dst = CGImageDestinationCreateWithData(out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dst, image, props as CFDictionary)
        return CGImageDestinationFinalize(dst) ? out as Data : nil
    }

    /// EditSource.memoryBudget: a bit under the memory this process may still take
    static func memoryBudget() -> Int64 {
        let avail = Int64(os_proc_available_memory())
        return Int64(Double(avail > 0 ? avail : 1 << 30) * 0.45)
    }
}

/// The session on its queue (SCAMEditSession is not thread safe; GL work stays on one serial queue).
final class EditEngine: @unchecked Sendable {
    let queue = DispatchQueue(label: "scam.editor", qos: .userInitiated)
    var session: SCAMEditSession?
}

/// PhotoEditorFragment + EditorViewModel: the photo, its state and history, the tab, the rendered frame, the histogram, the copy.
@MainActor
@Observable
final class EditorModel {
    enum Tab: Int, CaseIterable { case crop, styles, look, light, colour, effects, detail, mark }

    let asset: PHAsset
    let editRaw: Bool
    private(set) var name = ""
    private(set) var loading = true
    private(set) var failure: String?
    private(set) var note: String?
    private(set) var picture: UIImage?
    private(set) var histogram: [[Int]]?
    private(set) var exporting = false
    private(set) var exportProgress: Float = 0
    private(set) var savedMessage: String?
    var state = EditState()
    private(set) var history = EditHistory(EditState())
    var tab: Tab = .light { didSet { requestRender(); updateWatermark() } }
    var before = false { didSet { if oldValue != before { requestRender(); updateWatermark() } } }
    var styleIndex = -1
    var styleAmount: Float = 1
    private var styleBase: EditState?
    private(set) var orientedSize = CGSize(width: 1, height: 1)
    private(set) var isRaw = false

    private let engine = EditEngine()
    private var original = Data()
    private var info: PhotoDecode.Info?
    private var viewPixels = CGSize.zero
    private var busy = false
    private var pendingRender = false
    static var clipboard: EditState?

    /// PhotoEditorFragment.margin: 10dp, 28dp in the crop tab
    static func margin(_ crop: Bool) -> CGFloat { crop ? 28 : 10 }
    static let background: [NSNumber] = [NSNumber(value: Float(0x1C) / 255), NSNumber(value: Float(0x1C) / 255), NSNumber(value: Float(0x1E) / 255)]

    nonisolated init(asset: PHAsset, raw: Bool) {
        self.asset = asset
        self.editRaw = raw
    }

    var cropMode: Bool { tab == .crop }

    func open(scale: CGFloat) async {
        let res = GalleryLibrary.resources(asset)
        guard let resource = editRaw ? (res.raw ?? res.photo) : (res.photo ?? res.raw) else {
            failure = L("editor.open_failed")
            loading = false
            return
        }
        name = resource.originalFilename
        do {
            original = try await GalleryLibrary.data(of: resource)
        } catch {
            failure = L("editor.open_failed") + " " + error.localizedDescription
            loading = false
            return
        }
        let shaders = Bundle.main.bundleURL.appendingPathComponent("shaders").path
        let data = original
        let raw = res.raw != nil && (resource == res.raw)
        let pictureInfo = raw ? nil : PhotoDecode.info(data)
        info = pictureInfo
        // P633: the watermark's facts from the photo's own EXIF (an unknown one stays unknown)
        watermarkInfo = WatermarkExif.info(WatermarkExif.properties(of: data) ?? [:])
        let engine = self.engine
        let result: (SCAMEditSession?, String?, SCAMWatermarkRecord?) = await withCheckedContinuation { cont in
            engine.queue.async {
                var session: SCAMEditSession?
                var why: String?
                var record: SCAMWatermarkRecord?
                do {
                    if raw {
                        session = try SCAMEditSession(dng: data, shaders: shaders)
                    } else if let i = pictureInfo, let p = PhotoDecode.decode(data, longSide: PhotoDecode.previewSide(i), p3: i.p3) {
                        session = try SCAMEditSession(preview: p.rgba, width: p.width, height: p.height, displayP3: p.p3,
                                                      orientation: i.orientation, fullWidth: i.width, fullHeight: i.height, shaders: shaders)
                        // P633 EditSource.readWatermark: the record at the end of the file, kept when it is this photo's and reads back
                        if let s = session, let r = WatermarkStore.read(data), s.setWatermarkRecord(r) { record = r }
                    }
                } catch {
                    why = error.localizedDescription
                }
                engine.session = session
                cont.resume(returning: (session, why, record))
            }
        }
        loading = false
        guard let session = result.0 else {
            failure = L("editor.open_failed") + " " + (result.1 ?? "")
            return
        }
        watermarkRecord = result.2
        isRaw = session.isRaw
        if session.quadHalf { note = L("editor.note.quad") }
        let w = Double(session.previewWidth), h = Double(session.previewHeight)
        orientedSize = CropMath.swapsSides(session.orientation) ? CGSize(width: h, height: w) : CGSize(width: w, height: h)
        let fw = Double(session.fullWidth), fh = Double(session.fullHeight)
        fullOriented = CropMath.swapsSides(session.orientation) ? CGSize(width: fh, height: fw) : CGSize(width: fw, height: fh)
        watermarkOutMax = SCAMEditSession.outMax(forBudget: PhotoDecode.memoryBudget())
        history = EditHistory(state)
        requestRender()
        updateWatermark()
    }

    func close() {
        let engine = self.engine
        engine.queue.async { engine.session = nil }
    }

    // ---------------------------------------------------------------- rendering

    func setViewSize(_ size: CGSize, scale: CGFloat) {
        let px = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        displayScale = scale
        if px != viewPixels {
            viewPixels = px
            requestRender()
            updateWatermark()
        }
    }

    func requestRender() {
        pendingRender = true
        pump()
    }

    private func pump() {
        guard pendingRender, !busy, viewPixels.width > 0, viewPixels.height > 0, engine.session != nil else { return }
        pendingRender = false
        busy = true
        let json = state.json, crop = cropMode, before = before
        let w = Int(viewPixels.width), h = Int(viewPixels.height)
        let margin = Double(Self.margin(crop) * (viewPixels.width / max(1, viewSizePoints.width)))
        let engine = self.engine
        let bg = Self.background
        let scale = viewPixels.width / max(1, viewSizePoints.width)
        engine.queue.async {
            guard let s = engine.session else {
                Task { @MainActor in self.busy = false }
                return
            }
            let frame = s.renderState(json, width: w, height: h, margin: margin, cropMode: crop, before: before, background: bg)
            let hist = crop ? nil : s.histogram(forState: json, before: before)
            var image: UIImage?
            if let frame, let cg = PhotoDecode.cgImage(rgba: frame.rgba, width: frame.width, height: frame.height) {
                image = UIImage(cgImage: cg, scale: scale, orientation: .up)
            }
            let counts = hist?.map { $0.map(\.intValue) }
            Task { @MainActor in
                self.picture = image
                if let counts { self.histogram = counts }
                self.busy = false
                self.pump()
            }
        }
    }

    private(set) var viewSizePoints = CGSize(width: 1, height: 1)
    func setViewPoints(_ size: CGSize) { viewSizePoints = size }

    /// A RAW's white balance in K («Как снято · 5200 K») / a photo's relative numbers (EditPanels.wbText)
    func wbText(_ p: EditParam, _ v: Float) -> String {
        if isRaw, let s = engine.session, let w = s.white(forState: state.json), w.count >= 2 {
            let asShot = state[.temperature] == 0 && state[.tint] == 0 ? L("editor.as_shot") + " · " : ""
            if p == .temperature { return asShot + "\(Int((w[0].doubleValue / 50).rounded()) * 50) K" }
            return (v > 0 ? "+" : "") + "\(EditParam.javaRound(v))"
        }
        let i = EditParam.javaRound(v)
        return i > 0 ? "+\(i)" : "\(i)"
    }

    // ---------------------------------------------------------------- state

    /// EditPanels.Host.changed: the state changed; commit: one undo step
    func changed(commit: Bool) {
        if !applyingStyle { styleBase = nil }
        requestRender()
        updateWatermark()
        if commit { history.commit(state) }
    }

    private var applyingStyle = false

    func set(_ p: EditParam, _ v: Float, commit: Bool) {
        state[p] = v
        changed(commit: commit)
    }

    func undo() {
        guard history.canUndo else { return }
        state = history.undo()
        afterJump()
    }

    func redo() {
        guard history.canRedo else { return }
        state = history.redo()
        afterJump()
    }

    private func afterJump() {
        styleBase = nil
        styleIndex = -1
        requestRender()
        updateWatermark()
    }

    func resetAll() {
        state = EditState()
        changed(commit: true)
        afterJump()
    }

    func copySettings() {
        Self.clipboard = state
    }

    func pasteSettings() {
        guard let c = Self.clipboard else { return }
        state.copyTone(c)
        changed(commit: true)
        afterJump()
    }

    func rotate() {
        CropMath.rotateClockwise(&state)
        changed(commit: true)
    }

    func flip() {
        CropMath.flipHorizontal(&state)
        changed(commit: true)
    }

    func resetCrop() {
        state.resetGeometry()
        changed(commit: true)
    }

    func aspect(_ ratio: Float) {
        state.aspect = ratio
        CropMath.applyAspect(&state, Double(orientedSize.width), Double(orientedSize.height))
        changed(commit: true)
    }

    private var straightenAnchor: [Float]?

    func straighten(_ degrees: Float, commit: Bool) {
        if straightenAnchor == nil { straightenAnchor = [state.cropL, state.cropT, state.cropR, state.cropB] }
        state.straighten = degrees
        let b = CropMath.baseSize(Double(orientedSize.width), Double(orientedSize.height), state.quarterTurns)
        let r = CropMath.inset(straightenAnchor!, b.0, b.1, Double(degrees))
        state.cropL = r[0]; state.cropT = r[1]; state.cropR = r[2]; state.cropB = r[3]
        if commit {
            straightenAnchor = nil
            changed(commit: true)
        } else {
            requestRender()
        }
    }

    func crop(_ r: [Float], done: Bool) {
        state.cropL = r[0]; state.cropT = r[1]; state.cropR = r[2]; state.cropB = r[3]
        if done { changed(commit: true) } else { requestRender() }
    }

    func style(_ index: Int, amount: Float, commit: Bool) {
        if styleBase == nil { styleBase = state }
        let base = styleBase!
        let next = index < 0 ? base : EditPresets.apply(base, index, amount)
        state.copyTone(next)
        applyingStyle = true
        changed(commit: commit)
        applyingStyle = false
        if index < 0 { styleBase = nil }
    }

    func setCurve(_ channel: Int, _ points: [Float]?, done: Bool) {
        state.curves[channel] = points
        changed(commit: done)
    }

    // ---------------------------------------------------------------- P619 «LUT»

    /// EditPanels.lutPage: a LUT card chosen (its strength 100 when it was 0)
    func setLut(_ value: String) {
        state.lut = value
        if !value.isEmpty && state[.lutAmount] <= 0 { state[.lutAmount] = 100 }
        changed(commit: true)
    }

    /// EditPanels.filmPage: a film card chosen (its strength 100 when it was 0)
    func setFilm(_ id: String) {
        state.film = id
        if !id.isEmpty && state[.filmAmount] <= 0 { state[.filmAmount] = 100 }
        changed(commit: true)
    }

    func setFilmPrint(_ id: String) {
        state.filmPrint = id
        changed(commit: true)
    }

    func setFilmFormat(_ id: String) {
        state.filmFormat = id
        changed(commit: true)
    }

    /// EditLookThumbs: the cards' pictures - the photo's picture as edited without the look (EditorView.thumbnail, 160 px), through
    /// each LUT / stock at full strength; keyed "lut|value" / "film|id"
    private(set) var lookThumbs: [String: UIImage] = [:]
    private var lookThumbsVersion = 0

    func loadLookThumbs(luts: [String], stocks: [String]) {
        guard engine.session != nil else { return }
        lookThumbsVersion += 1
        let version = lookThumbsVersion, json = state.json, engine = self.engine
        engine.queue.async {
            guard let s = engine.session, let base = s.thumbnail(forState: json, side: 160) else { return }
            let jobs = luts.map { ("lut|" + $0, $0, "") } + stocks.map { ("film|" + $0, "", $0) }
            for (key, lut, film) in jobs {
                let data = lut.isEmpty && film.isEmpty ? base.rgba
                    : SCAMFilm.lookThumbnail(base.rgba, width: base.width, height: base.height, lut: lut, film: film) ?? base.rgba
                guard let cg = PhotoDecode.cgImage(rgba: data, width: base.width, height: base.height) else { continue }
                let image = UIImage(cgImage: cg)
                Task { @MainActor in
                    if version == self.lookThumbsVersion { self.lookThumbs[key] = image }
                }
            }
        }
    }

    // ---------------------------------------------------------------- P633 «Знак»

    /// EditWatermarkPanel's modes: the photo as it is, its own watermark taken off, a watermark drawn («Изменить» / «Добавить»).
    enum WatermarkMode: Int { case keep, remove, draw }

    /// The photo's own watermark record (EditSource.watermark), nil for none - an older photo, another app's, a re-encoded one.
    private(set) var watermarkRecord: SCAMWatermarkRecord?
    /// The watermark's facts of this photo from its own EXIF.
    private(set) var watermarkInfo = WatermarkText.Info()
    /// The live preview (WatermarkOverlayView): the state's watermark laid out for the copy's size, drawn into where the picture is
    /// (`watermarkRect`, view points); nil when none is shown.
    private(set) var watermarkOverlay: UIImage?
    private(set) var watermarkRect = CGRect.zero
    /// A line shown over the picture for a moment (GalleryToast), nil for none.
    var notice: String?
    private var fullOriented = CGSize(width: 1, height: 1)
    private var watermarkOutMax: Int64 = 4_000_000
    private var displayScale: CGFloat = 1
    private var watermarkPlan: WatermarkPainter.Plan?
    private var watermarkPlanKey = ""
    private var inkPicture: (rgba: Data, width: Int, height: Int)?
    private var inkKey: String?

    var watermarkMode: WatermarkMode { !state.wm.isEmpty ? .draw : state.wmRemove ? .remove : .keep }

    /// EditWatermarkPanel.pick: «Убрать» needs the record (greyed without: why, and the mode stays), «Изменить» takes the old one off
    /// first.
    func pickWatermarkMode(_ m: WatermarkMode) {
        if m == .remove && watermarkRecord == nil {
            notice = L("editor.wm.no_record")
            return
        }
        if m == watermarkMode { return }
        switch m {
        case .keep:
            state.wmRemove = false
            state.wm = ""
        case .remove:
            state.wmRemove = true
            state.wm = ""
        case .draw:
            state.wmRemove = watermarkRecord != nil
            state.wm = initialWatermark().toJson()
        }
        changed(commit: true)
    }

    /// A new watermark starts as the photo's own (its record's spec) or as the camera's settings.
    func initialWatermark() -> WatermarkSpec {
        watermarkRecord.flatMap { WatermarkSpec.fromJson($0.spec) } ?? WatermarkSpec.stored()
    }

    /// The watermark the panel shows: the state's, else the one a new watermark would start as.
    var watermarkSpec: WatermarkSpec { state.watermark ?? initialWatermark() }

    func editWatermark(commit: Bool, _ change: (inout WatermarkSpec) -> Void) {
        var s = watermarkSpec
        change(&s)
        state.wm = s.toJson()
        changed(commit: commit)
    }

    /// «Как в настройках камеры»: the camera's watermark settings, as new photos get it.
    func watermarkAsCamera() {
        state.wm = WatermarkSpec.stored().toJson()
        changed(commit: true)
    }

    /// EditExporter.outputSize: the copy's size for a crop of cropW x cropH px and the pixel limit.
    static func outputSize(_ cropW: Double, _ cropH: Double, _ outMax: Int64) -> (Int, Int) {
        let k = Swift.min(1.0, (Double(outMax) / Swift.max(1.0, cropW * cropH)).squareRoot())
        return (Swift.max(1, Int((cropW * k + 0.5).rounded(.down))), Swift.max(1, Int((cropH * k + 0.5).rounded(.down))))
    }

    /// PhotoEditorFragment.updateWatermark: the state's watermark laid out for the copy's size, over the picture (not in «Кадр», not
    /// while the long press shows the original); «Авто» from a small picture of the edited crop, asked again when it changed.
    func updateWatermark() {
        guard let spec = state.watermark, tab != .crop, !before, engine.session != nil, viewPixels.width > 0, viewPixels.height > 0 else {
            watermarkOverlay = nil
            return
        }
        let crop = CropMath.cropPixels(state, Double(fullOriented.width), Double(fullOriented.height))
        let size = Self.outputSize(crop.0, crop.1, watermarkOutMax)
        let key = spec.toJson() + "|\(size.0)x\(size.1)"
        if key != watermarkPlanKey {
            watermarkPlanKey = key
            let plan = WatermarkPainter.plan(spec, WatermarkText.of(spec, watermarkInfo), width: size.0, height: size.1, fonts: WatermarkFonts.shared)
            watermarkPlan = plan.empty ? nil : plan
        }
        guard var plan = watermarkPlan else {
            watermarkOverlay = nil
            return
        }
        if spec.colour == .auto {
            var picture = state
            picture.wm = ""
            let k = picture.json
            if k != inkKey {
                inkKey = k
                let engine = self.engine
                engine.queue.async {
                    guard let s = engine.session, let t = s.thumbnail(forState: k, side: 160) else { return }
                    let shot = (rgba: t.rgba, width: t.width, height: t.height)
                    Task { @MainActor in
                        if self.inkKey == k {
                            self.inkPicture = shot
                            self.updateWatermark()
                        }
                    }
                }
            }
            if var ink = inkPicture?.rgba, let w = inkPicture?.width, let h = inkPicture?.height {
                ink.withUnsafeMutableBytes { raw in
                    guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
                    WatermarkPainter.chooseInkScaled(&plan, spec, small: WatermarkRows(base: base, width: w, height: h, bytesPerRow: w * 4))
                }
            } else {
                WatermarkPainter.chooseInkScaled(&plan, spec, small: nil)
            }
        } else {
            WatermarkPainter.chooseInk(&plan, spec, rows: nil)
        }
        let margin = Double(Self.margin(false) * (viewPixels.width / Swift.max(1, viewSizePoints.width)))
        let r = CropMath.canvas(Double(viewPixels.width), Double(viewPixels.height), margin, state, Double(orientedSize.width),
                                Double(orientedSize.height), false)
        let pw = Int(r.width.rounded()), ph = Int(r.height.rounded())
        guard pw > 0, ph > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: pw, height: ph, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            watermarkOverlay = nil
            return
        }
        ctx.clear(CGRect(x: 0, y: 0, width: pw, height: ph))
        ctx.translateBy(x: 0, y: CGFloat(ph))
        ctx.scaleBy(x: 1, y: -1)
        ctx.scaleBy(x: CGFloat(pw) / CGFloat(plan.width), y: CGFloat(ph) / CGFloat(plan.height))
        WatermarkPainter.draw(ctx, plan, spec, fonts: WatermarkFonts.shared)
        guard let image = ctx.makeImage() else {
            watermarkOverlay = nil
            return
        }
        let k = Swift.max(displayScale, 1)
        watermarkOverlay = UIImage(cgImage: image, scale: k, orientation: .up)
        watermarkRect = CGRect(x: r.minX / k, y: r.minY / k, width: CGFloat(pw) / k, height: CGFloat(ph) / k)
    }

    // ---------------------------------------------------------------- the copy

    /// «Сохранить копию»: EditExporter.export at full resolution, then a new JPEG in the library.
    func save() async {
        guard !exporting, engine.session != nil else { return }
        exporting = true
        exportProgress = 0
        let json = state.json, data = original, info = self.info, raw = isRaw, name = name
        // P633: the «Знак» tab's watermark drawn on the finished copy with this photo's facts, its record kept
        let wmSpec = state.watermark, wmInfo = watermarkInfo
        let cropFraction = max(1e-6, Double((state.cropR - state.cropL) * (state.cropB - state.cropT)))
        let budget = PhotoDecode.memoryBudget()
        let outMax = SCAMEditSession.outMax(forBudget: budget)
        let engine = self.engine
        let progress: @Sendable (Float) -> Void = { [weak self] p in Task { @MainActor in self?.exportProgress = p } }
        let result: Result<(Data, SCAMEditImage), Error> = await withCheckedContinuation { cont in
            engine.queue.async {
                do {
                    guard let s = engine.session else { throw NSError(domain: "SCAMEdit", code: 1) }
                    let copy: SCAMEditImage
                    if raw {
                        copy = try s.exportState(json, dng: data, budget: budget, outMax: outMax, progress: progress)
                    } else {
                        guard let i = info else { throw NSError(domain: "SCAMEdit", code: 2) }
                        let side = PhotoDecode.exportSide(i, cropFraction: cropFraction, outMax: outMax, budget: budget)
                        guard let full = PhotoDecode.decode(data, longSide: side, p3: i.p3) else {
                            throw NSError(domain: "SCAMEdit", code: 3, userInfo: [NSLocalizedDescriptionKey: L("editor.open_failed")])
                        }
                        copy = try s.exportState(json, picture: full.rgba, width: full.width, height: full.height, outMax: outMax,
                                                 progress: progress)
                    }
                    // P633 EditExporter.drawWatermark: drawn as the camera draws it (upright, the chosen corner, on the short
                    // side), the pixels under it kept so this copy's watermark can come off again
                    var rgba = copy.rgba
                    var record: SCAMWatermarkRecord?
                    if let wmSpec {
                        record = PhotoWatermark.drawRecorded(&rgba, width: copy.width, height: copy.height, spec: wmSpec, info: wmInfo,
                                                             record: true, log: { NSLog("SCAMEdit %@", $0) }).record
                    }
                    guard let cg = PhotoDecode.cgImage(rgba: rgba, width: copy.width, height: copy.height),
                          var jpeg = PhotoDecode.jpeg(cg, original: data) else { throw NSError(domain: "SCAMEdit", code: 4) }
                    // EditExporter.writeCopy: the record at the end of the JPEG, after its EXIF (a copy without a watermark carries none)
                    if let record { jpeg = PhotoWatermark.appendRecord(record, to: jpeg, log: { NSLog("SCAMEdit %@", $0) }) }
                    cont.resume(returning: .success((jpeg, copy)))
                } catch {
                    cont.resume(returning: .failure(error))
                }
            }
        }
        var message: String
        switch result {
        case .success(let (jpeg, copy)):
            do {
                try await GalleryLibrary.saveCopy(jpeg, name: name)
                message = L("editor.saved") + " " + GalleryLibrary.copyName(name)
                if copy.shrunk { message += " (\(copy.width)×\(copy.height), " + L("editor.memory_limit") + ")" }
                savedState = state
            } catch {
                message = L("editor.save_failed") + " " + error.localizedDescription
            }
        case .failure(let error):
            message = L("editor.save_failed") + " " + error.localizedDescription
        }
        exporting = false
        exportProgress = 1
        savedMessage = message
    }

    func clearMessage() { savedMessage = nil }
    /// the state the last copy was saved with (leaving after it asks nothing)
    private var savedState: EditState?
    var hasChanges: Bool { state != (savedState ?? history.states[0]) }
}
