import CoreImage
import ImageIO
import Photos
import SwiftUI
import UIKit

/// ViewerChrome's facts of a page (SIMPLE_UI §5): with what it was taken («24 мм · 12 Мп · JPEG»; a video: «Видео · 0:42 · MP4»)
/// and whether it is an Ultra HDR / gain-map photo (the «HDR: вкл. / выкл.» pill), read from the head of the file; the HDR
/// rendition is shown by default, the pill turns it off and on per photo.
@MainActor
@Observable
final class ViewerFacts {
    struct Facts { var sub = ""; var hdr = false }
    static let shared = ViewerFacts()
    private(set) var facts: [String: Facts] = [:]
    /// the photos whose HDR rendition the pill turned off
    var hdrOff: Set<String> = []
    @ObservationIgnored private var asked: Set<String> = []

    nonisolated init() {}

    func hdrOn(_ id: String) -> Bool { (facts[id]?.hdr ?? false) && !hdrOff.contains(id) }

    func load(_ asset: PHAsset, kind: GalleryLibrary.AssetKind?) {
        let id = asset.localIdentifier
        if asked.contains(id) { return }
        asked.insert(id)
        if asset.mediaType == .video {
            // P498: «Видео · 0:42 · MP4»
            let ext = ((kind?.fileName ?? GalleryLibrary.videoResource(asset)?.originalFilename ?? "") as NSString).pathExtension.uppercased()
            facts[id] = Facts(sub: Self.joinDot([L("viewer.video"), asset.duration > 0 ? VideoResume.clock(Int64(asset.duration * 1000)) : nil,
                                                 ext.isEmpty ? nil : ext]))
            return
        }
        let w = asset.pixelWidth, h = asset.pixelHeight
        let format = Self.formatLabel(kind)
        Task.detached(priority: .userInitiated) {
            let res = GalleryLibrary.resources(asset)
            var lens: String?
            var hdr = false
            if let r = res.photo ?? res.raw {
                let head = await RowMeta.headData(r, limit: 512 * 1024)
                hdr = ViewerFacts.hasGainMap(head)
                if let src = CGImageSourceCreateWithData(head as CFData, nil),
                   let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] {
                    let exif = p[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
                    if let e = (exif[kCGImagePropertyExifFocalLenIn35mmFilm] as? NSNumber)?.intValue, e > 0 {
                        lens = "\(e) " + L("gallery.mm")
                    } else if let f = (exif[kCGImagePropertyExifFocalLength] as? NSNumber)?.doubleValue, f > 0 {
                        lens = PhotoDetails.decimal(f, 1) + " " + L("gallery.mm")
                    }
                }
            }
            // ImageViewerFragment.megapixels: whole megapixels, at least 1
            let mp = w > 0 && h > 0 ? "\(max(1, Int((Double(w) * Double(h) / 1e6).rounded()))) " + L("gallery.mp") : nil
            let sub = ViewerFacts.joinDot([lens, mp, format])
            await MainActor.run { ViewerFacts.shared.facts[id] = Facts(sub: sub, hdr: hdr && ViewerHdr.screenCan) }
        }
    }

    /// GalleryFormat.label: «JPEG», «HEIC», «RAW», «RAW + JPEG», «RAW + HEIC» (others: none)
    nonisolated static func formatLabel(_ k: GalleryLibrary.AssetKind?) -> String? {
        guard let k else { return nil }
        switch k.format {
        case .jpeg: return k.raw ? "RAW + JPEG" : "JPEG"
        case .heic: return k.raw ? "RAW + HEIC" : "HEIC"
        case .raw: return "RAW"
        case .other: return nil
        }
    }

    /// A gain map in the file: Ultra HDR's XMP, Apple's HDR gain map, an ISO 21496-1 gain map
    nonisolated static func hasGainMap(_ head: Data) -> Bool {
        for marker in ["hdrgm:Version", "urn:com:apple:photo:2020:aux:hdrgainmap", "urn:iso:std:iso:ts:21496:-1"] {
            if head.range(of: Data(marker.utf8)) != nil { return true }
        }
        return false
    }

    nonisolated static func joinDot(_ parts: [String?]) -> String {
        parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// The HDR rendition of a gain-map photo (iOS 17 Core Image: CIImage's expandToHDR reads the gain map and gives the extended range),
/// drawn into a half-float extended Display P3 picture that a UIImageView shows on the screen's headroom (preferredImageDynamicRange
/// .high). Ultra HDR JPEGs and ISO gain maps are expanded where the system reads them (ISO 21496-1 from iOS 18).
enum ViewerHdr {
    static let context = CIContext(options: [.cacheIntermediates: false])

    /// Whether this screen shows more than SDR white.
    @MainActor static var screenCan: Bool { UIScreen.main.potentialEDRHeadroom > 1.01 }

    static func image(_ data: Data) -> CIImage? {
        CIImage(data: data, options: [.expandToHDR: true, .applyOrientationProperty: true])
    }

    /// The oriented rect `r` (px, top-left origin) of `image` at `target` px.
    static func render(_ image: CIImage, _ r: CGRect, target: CGSize) -> UIImage? {
        let ext = image.extent
        let ci = CGRect(x: ext.minX + r.minX, y: ext.minY + ext.height - r.maxY, width: r.width, height: r.height)
        let s = min(1, min(target.width / max(1, r.width), target.height / max(1, r.height)))
        let out = image.cropped(to: ci).transformed(by: CGAffineTransform(scaleX: s, y: s))
        guard let space = CGColorSpace(name: CGColorSpace.extendedLinearDisplayP3),
              let cg = context.createCGImage(out, from: out.extent.integral, format: .RGBAh, colorSpace: space) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// FdRegionDecoder (P620) / SubsamplingScaleImageView's tiles on the iPhone: the original decoded where the zoom needs it - the
/// visible part at the screen's resolution, from a power-of-two subsampled decode (ImageIO's subsample factor 2 / 4 / 8) until 1:1,
/// then from the full decode - instead of one screen-sized picture scaled up. One decode is kept per page while it is zoomed
/// (released at the fit and when the page goes). HDR: the region from the gain-map rendition (ViewerHdr).
final class ViewerSource: @unchecked Sendable {
    let data: Data
    /// EXIF orientation 1..8, the stored size, the size as shown
    let orientation: Int
    let stored: CGSize
    let oriented: CGSize
    private let lock = NSLock()
    private var cached: (factor: Int, image: CGImage)?
    private var hdrImage: CIImage?

    init?(data: Data) {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = (p[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue, let h = (p[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              w > 0, h > 0 else { return nil }
        self.data = data
        let o = (p[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        orientation = (1...8).contains(o) ? o : 1
        stored = CGSize(width: w, height: h)
        oriented = orientation >= 5 ? CGSize(width: h, height: w) : stored
    }

    /// The part `r` of the photo as shown (px) at about `target` px, upright; nil when it cannot be decoded.
    func region(_ r: CGRect, target: CGSize, hdr: Bool) async -> UIImage? {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                cont.resume(returning: hdr ? self.hdrRegion(r, target) : self.sdrRegion(r, target))
            }
        }
    }

    func release() {
        lock.lock()
        cached = nil
        hdrImage = nil
        lock.unlock()
    }

    private func hdrRegion(_ r: CGRect, _ target: CGSize) -> UIImage? {
        lock.lock()
        if hdrImage == nil { hdrImage = ViewerHdr.image(data) }
        let image = hdrImage
        lock.unlock()
        guard let image else { return nil }
        return ViewerHdr.render(image, r, target: target)
    }

    private func sdrRegion(_ r: CGRect, _ target: CGSize) -> UIImage? {
        // FdRegionDecoder's sample size: the largest power of two the screen does not need
        let need = max(target.width / max(1, r.width), target.height / max(1, r.height))
        var factor = 1
        while factor < 8, CGFloat(factor * 2) <= 1 / max(need, 1e-3) { factor *= 2 }
        guard let full = fullImage(factor) else { return nil }
        let raw = Self.storedRect(r, orientation: orientation, stored: stored)
        let kx = CGFloat(full.width) / stored.width, ky = CGFloat(full.height) / stored.height
        let crop = CGRect(x: raw.minX * kx, y: raw.minY * ky, width: raw.width * kx, height: raw.height * ky).integral
            .intersection(CGRect(x: 0, y: 0, width: full.width, height: full.height))
        guard !crop.isEmpty, let part = full.cropping(to: crop) else { return nil }
        let s = min(1, min(target.width / max(1, r.width), target.height / max(1, r.height)) * CGFloat(factor))
        let tw = max(1, Int((CGFloat(part.width) * s).rounded())), th = max(1, Int((CGFloat(part.height) * s).rounded()))
        let space = part.colorSpace.flatMap { $0.model == .rgb ? $0 : nil } ?? CGColorSpace(name: CGColorSpace.sRGB)!
        guard let ctx = CGContext(data: nil, width: tw, height: th, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(part, in: CGRect(x: 0, y: 0, width: tw, height: th))
        guard let out = ctx.makeImage() else { return nil }
        return UIImage(cgImage: out, scale: 1, orientation: Self.uiOrientation(orientation))
    }

    private func fullImage(_ factor: Int) -> CGImage? {
        lock.lock()
        defer { lock.unlock() }
        if let c = cached, c.factor == factor { return c.image }
        cached = nil
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        var options: [CFString: Any] = [kCGImageSourceShouldCacheImmediately: true]
        if factor > 1 { options[kCGImageSourceSubsampleFactor] = factor }
        guard let image = CGImageSourceCreateImageAtIndex(src, 0, options as CFDictionary) else { return nil }
        cached = (factor, image)
        return image
    }

    /// A rect of the photo as shown → the same pixels in the stored picture (EXIF orientation 1..8).
    static func storedRect(_ r: CGRect, orientation o: Int, stored: CGSize) -> CGRect {
        let w = stored.width, h = stored.height
        func map(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            switch o {
            case 2: return CGPoint(x: w - x, y: y)
            case 3: return CGPoint(x: w - x, y: h - y)
            case 4: return CGPoint(x: x, y: h - y)
            case 5: return CGPoint(x: y, y: x)
            case 6: return CGPoint(x: y, y: h - x)
            case 7: return CGPoint(x: w - y, y: h - x)
            case 8: return CGPoint(x: w - y, y: x)
            default: return CGPoint(x: x, y: y)
            }
        }
        let a = map(r.minX, r.minY), b = map(r.maxX, r.maxY)
        return CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(a.x - b.x), height: abs(a.y - b.y))
    }

    static func uiOrientation(_ o: Int) -> UIImage.Orientation {
        switch o {
        case 2: return .upMirrored
        case 3: return .down
        case 4: return .downMirrored
        case 5: return .leftMirrored
        case 6: return .right
        case 7: return .rightMirrored
        case 8: return .left
        default: return .up
        }
    }
}

/// SubsamplingScaleImageView for one page (ImageViewerFragment): the photo fitted, pinch and double tap to zoom (2.5x the fit), up
/// to twice the original's pixels on the screen's; past the screen picture's resolution the visible part is decoded from the
/// original (ViewerSource) when the zoom or the pan settles. A tap toggles the chrome. HDR: the picture on the screen's headroom.
final class ZoomPhotoView: UIScrollView, UIScrollViewDelegate {
    let imageView = UIImageView()
    private let detail = UIImageView()
    var onTap: () -> Void = {}
    private(set) var source: ViewerSource?
    private var hdr = false
    private var aspect = CGSize.zero
    private var lastBounds = CGSize.zero
    private var detailTask: Task<Void, Never>?

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        backgroundColor = .black
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .fast
        minimumZoomScale = 1
        maximumZoomScale = 8
        imageView.contentMode = .scaleToFill
        imageView.isUserInteractionEnabled = true
        addSubview(imageView)
        detail.contentMode = .scaleToFill
        detail.isHidden = true
        imageView.addSubview(detail)
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTap(_:)))
        double.numberOfTapsRequired = 2
        addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: self, action: #selector(singleTap))
        single.require(toFail: double)
        addGestureRecognizer(single)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// A new picture of the page (the screen picture, its HDR rendition), the original's decoder and the dynamic range.
    func show(_ image: UIImage?, source: ViewerSource?, hdr: Bool) {
        if imageView.image !== image { imageView.image = image }
        imageView.preferredImageDynamicRange = hdr ? .high : .standard
        detail.preferredImageDynamicRange = hdr ? .high : .standard
        let sourceChanged = self.source !== source, hdrChanged = self.hdr != hdr
        self.source = source
        self.hdr = hdr
        let size = source?.oriented ?? image?.size ?? .zero
        if size.width > 0, size.height > 0 {
            let a = CGSize(width: size.width / size.height, height: 1)
            if abs(a.width - aspect.width) > 0.01 {
                aspect = a
                resetFit()
            }
        }
        updateMaxZoom()
        if sourceChanged || hdrChanged {
            detail.isHidden = true
            detail.image = nil
            refreshDetail()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastBounds {
            lastBounds = bounds.size
            resetFit()
        }
    }

    private func fitSize() -> CGSize {
        guard aspect.width > 0, bounds.width > 0, bounds.height > 0 else { return bounds.size }
        let s = min(bounds.width / aspect.width, bounds.height / aspect.height)
        return CGSize(width: aspect.width * s, height: aspect.height * s)
    }

    private func resetFit() {
        detailTask?.cancel()
        zoomScale = 1
        let fit = fitSize()
        imageView.frame = CGRect(origin: .zero, size: fit)
        contentSize = fit
        detail.isHidden = true
        centre()
        contentOffset = CGPoint(x: -contentInset.left, y: -contentInset.top)
        updateMaxZoom()
    }

    private var screenScale: CGFloat { window?.screen.scale ?? UIScreen.main.scale }

    /// Up to twice the original's pixels on the screen's (at least 4x the fit)
    private func updateMaxZoom() {
        let fit = fitSize()
        guard let source, fit.width > 0 else {
            maximumZoomScale = 4
            return
        }
        let oneToOne = source.oriented.width / (fit.width * screenScale)
        maximumZoomScale = max(4, oneToOne * 2)
    }

    private func centre() {
        let w = imageView.frame.width, h = imageView.frame.height
        let inset = UIEdgeInsets(top: max(0, (bounds.height - h) / 2), left: max(0, (bounds.width - w) / 2), bottom: 0, right: 0)
        if inset != contentInset { contentInset = inset }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centre()
        if zoomScale <= minimumZoomScale + 0.01 {
            detailTask?.cancel()
            detail.isHidden = true
        }
    }

    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) { refreshDetail() }
    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { refreshDetail() }
    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) { if !decelerate { refreshDetail() } }
    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { refreshDetail() }

    /// The visible part decoded from the original once the screen picture is coarser than the screen (FdRegionDecoder's tiles).
    private func refreshDetail() {
        detailTask?.cancel()
        guard let source, let base = imageView.image, imageView.bounds.width > 0 else { return }
        let shownPx = imageView.frame.width * screenScale
        let basePx = base.size.width * base.scale
        if shownPx <= basePx * 1.02 || zoomScale <= minimumZoomScale + 0.01 {
            detail.isHidden = true
            detail.image = nil
            source.release()
            return
        }
        let b = imageView.bounds.size
        let visible = convert(bounds, to: imageView).intersection(CGRect(origin: .zero, size: b))
        guard visible.width > 1, visible.height > 1 else { return }
        let o = source.oriented
        let region = CGRect(x: visible.minX / b.width * o.width, y: visible.minY / b.height * o.height,
                            width: visible.width / b.width * o.width, height: visible.height / b.height * o.height).integral
            .intersection(CGRect(origin: .zero, size: o))
        // the region at the screen's pixels, never above its own
        let k = min(1, visible.width * zoomScale * screenScale / max(1, region.width))
        let target = CGSize(width: region.width * k, height: region.height * k)
        // the region drawn back where it is in the picture (its integral rect in the view's units)
        let placed = CGRect(x: region.minX / o.width * b.width, y: region.minY / o.height * b.height,
                            width: region.width / o.width * b.width, height: region.height / o.height * b.height)
        let hdr = self.hdr
        detailTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 60_000_000)
            if Task.isCancelled { return }
            let image = await source.region(region, target: target, hdr: hdr)
            guard let self, !Task.isCancelled, let image, self.source === source else { return }
            self.detail.image = image
            self.detail.frame = placed
            self.detail.isHidden = false
        }
    }

    /// The page left the screen: its decode goes.
    func releaseDetail() {
        detailTask?.cancel()
        detail.isHidden = true
        detail.image = nil
        source?.release()
    }

    @objc private func singleTap() { onTap() }

    @objc private func doubleTap(_ g: UITapGestureRecognizer) {
        if zoomScale > minimumZoomScale + 0.01 {
            setZoomScale(minimumZoomScale, animated: true)
            return
        }
        let p = g.location(in: imageView)
        let k: CGFloat = 2.5
        let size = CGSize(width: bounds.width / k, height: bounds.height / k)
        zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: true)
    }
}

struct ZoomPhoto: UIViewRepresentable {
    let image: UIImage?
    let source: ViewerSource?
    let hdr: Bool
    let tap: () -> Void

    func makeUIView(context: Context) -> ZoomPhotoView {
        let v = ZoomPhotoView()
        v.onTap = tap
        return v
    }

    func updateUIView(_ v: ZoomPhotoView, context: Context) {
        v.onTap = tap
        v.show(image, source: source, hdr: hdr)
    }

    static func dismantleUIView(_ v: ZoomPhotoView, coordinator: ()) {
        v.releaseDetail()
    }
}

/// One page of the viewer: the photo at screen size (PhotoKit), its original's decoder for the zoom and, for a gain-map photo, the
/// HDR rendition while the pill has it on; a video page is its poster with the play disc (P498).
@MainActor
struct ViewerPage: View {
    let library: GalleryLibrary
    let asset: PHAsset
    var play: () -> Void = {}
    let tap: () -> Void
    @State private var image: UIImage?
    @State private var hdrImage: UIImage?
    @State private var source: ViewerSource?
    @Environment(\.displayScale) private var scale

    var body: some View {
        let hdr = asset.mediaType == .image && ViewerFacts.shared.hdrOn(asset.localIdentifier) && hdrImage != nil
        GeometryReader { geo in
            ZoomPhoto(image: hdr ? hdrImage : image, source: source, hdr: hdr, tap: tap)
                .frame(width: geo.size.width, height: geo.size.height)
                .overlay {
                    if asset.mediaType == .video {
                        Button(action: play) {
                            GalleryIcon(name: "ui_ic_play", size: 34)
                                .frame(width: 76, height: 76)
                                .background(GalleryStyle.bar, in: Circle())
                        }
                        .buttonStyle(PressScaleStyle())
                        .accessibilityLabel(L("video.play_video"))
                    }
                }
                .task(id: asset.localIdentifier) {
                    let side = max(geo.size.width, geo.size.height) * scale * 1.5
                    image = await library.display(asset, side: side)
                    ViewerFacts.shared.load(asset, kind: library.kinds[asset.localIdentifier])
                    guard asset.mediaType == .image else { return }
                    let res = GalleryLibrary.resources(asset)
                    guard let r = res.photo ?? res.raw, let data = try? await GalleryLibrary.data(of: r) else { return }
                    let s = await Task.detached(priority: .userInitiated) { ViewerSource(data: data) }.value
                    source = s
                    // the gain-map rendition at the screen picture's size (Android: the HDR bitmap of the page)
                    if let s, ViewerFacts.hasGainMap(data.prefix(512 * 1024)), ViewerHdr.screenCan {
                        let k = min(1, side / max(s.oriented.width, s.oriented.height))
                        hdrImage = await s.region(CGRect(origin: .zero, size: s.oriented),
                                                  target: CGSize(width: s.oriented.width * k, height: s.oriented.height * k), hdr: true)
                    }
                }
        }
    }
}

// ---------------------------------------------------------------- «Ещё»: open in another app

/// «Открыть в другом приложении» / «Изменить в другом приложении» (ExternalEdit): the photo's file offered to the apps that open it
/// (UIDocumentInteractionController's «Open in» menu; iOS has no edit chooser like Android's ACTION_EDIT).
@MainActor
final class OpenInApp: NSObject, UIDocumentInteractionControllerDelegate {
    static let shared = OpenInApp()
    private var controller: UIDocumentInteractionController?

    /// false when no app was offered (the caller says so)
    func open(_ url: URL) -> Bool {
        guard let host = Self.topController() else { return false }
        let c = UIDocumentInteractionController(url: url)
        c.delegate = self
        controller = c
        let r = CGRect(x: host.view.bounds.midX, y: host.view.bounds.maxY - 80, width: 1, height: 1)
        return c.presentOpenInMenu(from: r, in: host.view, animated: true)
    }

    func documentInteractionControllerDidDismissOpenInMenu(_ controller: UIDocumentInteractionController) {
        self.controller = nil
    }

    static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard var top = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController else { return nil }
        while let next = top.presentedViewController { top = next }
        return top
    }
}
