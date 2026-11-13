import ImageIO
import Photos
import SwiftUI
import UIKit

/// The gallery (Android SGallery: ImageLibraryFragment's «Фото» grid by month, ImageViewerFragment's viewer with its chrome -
/// share, edit, details, delete, favourite - and the photo editor) over the photos the app saved («SCAMERA» album) or the whole
/// library. The entry point of the camera screen's gallery button.
@MainActor
struct GalleryView: View {
    @State private var library = GalleryLibrary()
    @State private var viewer: ViewerStart?
    @Environment(\.dismiss) private var dismiss

    struct ViewerStart: Identifiable {
        let id = UUID()
        let index: Int
    }

    init() {}

    var body: some View {
        ZStack {
            GalleryStyle.page.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                content
            }
        }
        .preferredColorScheme(.dark)
        .task { await library.start() }
        .onDisappear { library.stop() }
        .fullScreenCover(item: $viewer) { start in
            PhotoViewer(library: library, startIndex: start.index)
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            HStack(spacing: 10) {
                RoundIconButton(icon: "ui_ic_back", label: L("gallery.back")) { dismiss() }
                VStack(alignment: .leading, spacing: 1) {
                    Text(L("gallery.photos")).font(.system(size: 28, weight: .bold)).foregroundStyle(.white)
                    if library.access == .granted {
                        Text(countText(library.assets.count)).font(.system(size: 13)).foregroundStyle(GalleryStyle.muted)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, 16).padding(.top, 8)
            if library.access == .granted {
                GallerySegments(names: [L("gallery.camera"), L("gallery.all")], selected: library.source.rawValue) { i in
                    library.source = GalleryLibrary.Source(rawValue: i) ?? .camera
                }
            }
        }
    }

    /// GalleryUi.shots: «1 снимок», «3 снимка», «5 снимков»
    private func countText(_ n: Int) -> String {
        let m10 = n % 10, m100 = n % 100
        let key = n == 1 ? L("gallery.count.single") : m10 == 1 && m100 != 11 ? L("gallery.count.one")
            : (2...4).contains(m10) && !(12...14).contains(m100) ? L("gallery.count.few") : L("gallery.count.many")
        return String(format: key, n)
    }

    @ViewBuilder private var content: some View {
        switch library.access {
        case .unknown:
            Spacer()
            ProgressView().tint(.white)
            Spacer()
        case .denied:
            Spacer()
            VStack(spacing: 14) {
                Text(L("gallery.no_access")).font(.system(size: 15)).foregroundStyle(GalleryStyle.muted).multilineTextAlignment(.center)
                Button(L("camera.open_settings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(GalleryStyle.ink)
                .padding(.horizontal, 18).frame(minHeight: 40).background(GalleryStyle.accent, in: Capsule())
            }
            .padding(32)
            Spacer()
        case .granted:
            if library.assets.isEmpty {
                Spacer()
                Text(library.source == .camera ? L("gallery.empty_camera") : L("gallery.empty"))
                    .font(.system(size: 15)).foregroundStyle(GalleryStyle.muted).multilineTextAlignment(.center).padding(32)
                Spacer()
            } else {
                grid
            }
        }
    }

    private var grid: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 4)
        let sections = library.sections
        let index = Dictionary(uniqueKeysWithValues: library.assets.enumerated().map { ($1.localIdentifier, $0) })
        return ScrollView {
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: [.sectionHeaders]) {
                ForEach(sections.indices, id: \.self) { s in
                    Section {
                        ForEach(sections[s].items, id: \.localIdentifier) { asset in
                            GalleryThumb(library: library, asset: asset)
                                .onTapGesture { viewer = ViewerStart(index: index[asset.localIdentifier] ?? 0) }
                        }
                    } header: {
                        Text(sections[s].title).font(.system(size: 17, weight: .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16).padding(.vertical, 8)
                            .background(GalleryStyle.page.opacity(0.94))
                    }
                }
            }
        }
    }
}

/// ThumbGrid's cell: a square thumbnail, the RAW / favourite badges.
@MainActor
private struct GalleryThumb: View {
    let library: GalleryLibrary
    let asset: PHAsset
    @State private var image: UIImage?
    @State private var raw = false
    @Environment(\.displayScale) private var scale

    var body: some View {
        Color(white: 0.12)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill() }
            }
            .clipped()
            .overlay(alignment: .bottomLeading) {
                HStack(spacing: 4) {
                    if asset.isFavorite { GalleryIcon(name: "ui_ic_heart_on", size: 14) }
                    if raw {
                        Text("RAW").font(.system(size: 9, weight: .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 4).padding(.vertical, 1).background(GalleryStyle.bar, in: Capsule())
                    }
                }
                .padding(4)
            }
            .contentShape(Rectangle())
            .task(id: asset.localIdentifier) {
                raw = GalleryLibrary.resources(asset).raw != nil
                image = await library.thumbnail(asset, side: 200 * scale / 2)
            }
    }
}

// ---------------------------------------------------------------- the viewer

/// ImageViewerFragment + ViewerChrome: the photos paged, pinch / double-tap zoom, a tap hides the chrome; the top discs (back,
/// favourite, more), the bottom bar (share, edit, details, delete).
@MainActor
struct PhotoViewer: View {
    let library: GalleryLibrary
    @State private var index: Int
    @State private var chrome = true
    @State private var details: PhotoDetails?
    @State private var confirmDelete = false
    @State private var share: ShareItem?
    @State private var editor: EditorStart?
    @State private var toast: String?
    @Environment(\.dismiss) private var dismiss

    struct ShareItem: Identifiable { let id = UUID(); let url: URL }
    struct EditorStart: Identifiable { let id = UUID(); let asset: PHAsset; let raw: Bool }

    init(library: GalleryLibrary, startIndex: Int) {
        self.library = library
        _index = State(initialValue: startIndex)
    }

    private var asset: PHAsset? { library.assets.indices.contains(index) ? library.assets[index] : nil }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(library.assets.enumerated()), id: \.element.localIdentifier) { i, a in
                    ViewerPage(library: library, asset: a) { withAnimation(.easeOut(duration: 0.2)) { chrome.toggle() } }
                        .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            if chrome { topBar; bottomBar }
            if let toast {
                VStack { Spacer(); GalleryPill(text: toast).padding(.bottom, 110) }
                    .transition(.opacity)
                    .task(id: toast) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        self.toast = nil
                    }
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(!chrome)
        .onChange(of: library.assets.count) { _, n in
            if n == 0 { dismiss() } else if index >= n { index = n - 1 }
        }
        .sheet(item: $details) { d in DetailsSheet(details: d).presentationDetents([.medium, .large]) }
        .sheet(item: $share) { s in ActivitySheet(items: [s.url]) }
        .fullScreenCover(item: $editor) { e in PhotoEditorView(asset: e.asset, raw: e.raw) }
        .confirmationDialog(L("gallery.delete_title"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("gallery.delete"), role: .destructive) { delete() }
            Button(L("gallery.cancel"), role: .cancel) {}
        } message: {
            Text(L("gallery.delete_message"))
        }
    }

    private var topBar: some View {
        VStack {
            HStack(spacing: 10) {
                RoundIconButton(icon: "ui_ic_back", label: L("gallery.back_grid"), background: GalleryStyle.bar) { dismiss() }
                if let a = asset, let d = a.creationDate {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(d.formatted(date: .abbreviated, time: .omitted)).font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                        Text(d.formatted(date: .omitted, time: .shortened)).font(.system(size: 12)).foregroundStyle(GalleryStyle.muted)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 4).background(GalleryStyle.bar, in: Capsule())
                }
                Spacer()
                if let a = asset {
                    RoundIconButton(icon: a.isFavorite ? "ui_ic_heart_on" : "ui_ic_heart",
                                    label: a.isFavorite ? L("gallery.unfavourite") : L("gallery.favourite"), background: GalleryStyle.bar) {
                        Task { await library.toggleFavourite(a) }
                    }
                    Menu {
                        Button(L("gallery.edit")) { editor = EditorStart(asset: a, raw: false) }
                        if GalleryLibrary.resources(a).raw != nil, GalleryLibrary.resources(a).photo != nil {
                            Button(L("gallery.edit_raw")) { editor = EditorStart(asset: a, raw: true) }
                        }
                        Button(L("gallery.share")) { shareCurrent() }
                    } label: {
                        GalleryIcon(name: "ui_ic_more", size: 22).frame(width: 40, height: 40).background(GalleryStyle.bar, in: Circle())
                    }
                    .accessibilityLabel(L("gallery.more"))
                }
            }
            .padding(.horizontal, 12).padding(.top, 6)
            Spacer()
        }
    }

    private var bottomBar: some View {
        VStack {
            Spacer()
            HStack(spacing: 0) {
                barButton("kb_ui_ic_share_rest", L("gallery.share")) { shareCurrent() }
                barButton("kb_ui_ic_edit_rest", L("gallery.edit")) {
                    if let a = asset { editor = EditorStart(asset: a, raw: GalleryLibrary.resources(a).photo == nil) }
                }
                barButton("kb_ui_ic_info_rest", L("gallery.details")) { showDetails() }
                barButton("kb_ui_ic_trash_rest", L("gallery.delete"), color: GalleryStyle.warn) { confirmDelete = true }
            }
            .padding(.vertical, 8).padding(.horizontal, 8)
            .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 24))
            .padding(.horizontal, 16).padding(.bottom, 10)
        }
    }

    private func barButton(_ icon: String, _ label: String, color: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                GalleryIcon(name: icon, size: 24, color: color)
                Text(label).font(.system(size: 11, weight: .medium)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func delete() {
        guard let a = asset else { return }
        Task {
            let ok = await library.delete([a])
            toast = ok ? L("gallery.deleted") : L("gallery.delete_failed")
        }
    }

    private func shareCurrent() {
        guard let a = asset, let r = GalleryLibrary.resources(a).photo ?? GalleryLibrary.resources(a).raw else { return }
        Task {
            guard let data = try? await GalleryLibrary.data(of: r) else { return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(r.originalFilename)
            try? FileManager.default.removeItem(at: url)
            guard (try? data.write(to: url)) != nil else { return }
            share = ShareItem(url: url)
        }
    }

    private func showDetails() {
        guard let a = asset else { return }
        Task { details = await PhotoDetails.of(a) }
    }
}

/// One page: the photo at screen size, pinch and double tap to zoom (a UIScrollView), a tap toggles the chrome.
@MainActor
private struct ViewerPage: View {
    let library: GalleryLibrary
    let asset: PHAsset
    let tap: () -> Void
    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { geo in
            ZoomImage(image: image, tap: tap)
                .frame(width: geo.size.width, height: geo.size.height)
                .task(id: asset.localIdentifier) {
                    image = await library.display(asset, side: max(geo.size.width, geo.size.height) * scale * 1.5)
                }
        }
    }
}

private struct ZoomImage: UIViewRepresentable {
    let image: UIImage?
    let tap: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(tap: tap) }

    func makeUIView(context: Context) -> UIScrollView {
        let s = UIScrollView()
        s.delegate = context.coordinator
        s.minimumZoomScale = 1
        s.maximumZoomScale = 8
        s.showsVerticalScrollIndicator = false
        s.showsHorizontalScrollIndicator = false
        s.backgroundColor = .black
        s.contentInsetAdjustmentBehavior = .never
        let v = UIImageView()
        v.contentMode = .scaleAspectFit
        v.isUserInteractionEnabled = true
        s.addSubview(v)
        context.coordinator.imageView = v
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        s.addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap))
        single.require(toFail: double)
        s.addGestureRecognizer(single)
        return s
    }

    func updateUIView(_ s: UIScrollView, context: Context) {
        context.coordinator.tap = tap
        guard let v = context.coordinator.imageView else { return }
        if v.image !== image {
            v.image = image
            s.zoomScale = 1
        }
        if v.frame.size != s.bounds.size, s.zoomScale == 1 { v.frame = s.bounds }
        DispatchQueue.main.async { if s.zoomScale == 1 { v.frame = s.bounds; s.contentSize = s.bounds.size } }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var tap: () -> Void
        weak var imageView: UIImageView?
        init(tap: @escaping () -> Void) { self.tap = tap }
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
        @objc func singleTap() { tap() }
        @objc func doubleTap(_ g: UITapGestureRecognizer) {
            guard let s = g.view as? UIScrollView else { return }
            if s.zoomScale > 1.01 {
                s.setZoomScale(1, animated: true)
            } else {
                let p = g.location(in: imageView)
                let k: CGFloat = 3
                let size = CGSize(width: s.bounds.width / k, height: s.bounds.height / k)
                s.zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: true)
            }
        }
    }
}

private struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// ---------------------------------------------------------------- «Сведения»

/// GallerySheets.Details + PhotoMore: when, the lens and device, the size; «Как снято» (ISO, shutter, aperture, focal length);
/// «Подробнее» from the file's EXIF.
struct PhotoDetails: Identifiable {
    let id = UUID()
    var fileName = ""
    var when: String?
    var lens: String?
    var device: String?
    var width = 0, height = 0
    var bytes: Int64 = 0
    var format: String?
    var iso: String?, shutter: String?, fnum: String?, focal: String?
    var more: [(String, String)] = []

    @MainActor static func of(_ asset: PHAsset) async -> PhotoDetails {
        var d = PhotoDetails()
        let res = GalleryLibrary.resources(asset)
        let r = res.photo ?? res.raw
        d.fileName = r?.originalFilename ?? ""
        d.width = asset.pixelWidth
        d.height = asset.pixelHeight
        if let date = asset.creationDate { d.when = date.formatted(date: .long, time: .shortened) }
        if let r, let size = r.value(forKey: "fileSize") as? NSNumber { d.bytes = size.int64Value }
        d.format = (d.fileName as NSString).pathExtension.uppercased() + (res.raw != nil && res.photo != nil ? " + RAW" : "")
        guard let r, let data = try? await GalleryLibrary.data(of: r),
              let src = CGImageSourceCreateWithData(data as CFData, nil),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return d }
        if d.bytes == 0 { d.bytes = Int64(data.count) }
        let exif = p[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let tiff = p[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        let make = tiff[kCGImagePropertyTIFFMake] as? String, model = tiff[kCGImagePropertyTIFFModel] as? String
        d.device = [make, model].compactMap { $0 }.joined(separator: " ").nilIfEmpty
        d.lens = exif[kCGImagePropertyExifLensModel] as? String
        if let iso = (exif[kCGImagePropertyExifISOSpeedRatings] as? [NSNumber])?.first { d.iso = "ISO \(iso.intValue)" }
        if let t = (exif[kCGImagePropertyExifExposureTime] as? NSNumber)?.doubleValue, t > 0 {
            d.shutter = t >= 1 ? String(format: "%.1f s", t) : "1/\(Int((1 / t).rounded())) s"
        }
        if let f = (exif[kCGImagePropertyExifFNumber] as? NSNumber)?.doubleValue { d.fnum = String(format: "ƒ/%.1f", f) }
        if let f = (exif[kCGImagePropertyExifFocalLength] as? NSNumber)?.doubleValue {
            var s = String(format: "%.1f mm", f)
            if let e = (exif[kCGImagePropertyExifFocalLenIn35mmFilm] as? NSNumber)?.intValue, e > 0 { s += " (\(e) " + L("gallery.equiv") + ")" }
            d.focal = s
        }
        if let v = exif[kCGImagePropertyExifDateTimeOriginal] as? String { d.more.append((L("gallery.taken"), v)) }
        if let v = (exif[kCGImagePropertyExifExposureBiasValue] as? NSNumber)?.doubleValue {
            d.more.append((L("gallery.exposure_bias"), String(format: "%+.1f EV", v)))
        }
        if let v = (exif[kCGImagePropertyExifWhiteBalance] as? NSNumber)?.intValue {
            d.more.append((L("gallery.white_balance"), v == 0 ? L("gallery.auto") : L("gallery.manual")))
        }
        if let v = (exif[kCGImagePropertyExifFlash] as? NSNumber)?.intValue {
            d.more.append((L("gallery.flash"), v & 1 == 1 ? L("gallery.flash_on") : L("gallery.flash_off")))
        }
        if let v = tiff[kCGImagePropertyTIFFSoftware] as? String { d.more.append((L("gallery.software"), v)) }
        if let v = p[kCGImagePropertyProfileName] as? String { d.more.append((L("gallery.colour_space"), v)) }
        return d
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

@MainActor
private struct DetailsSheet: View {
    let details: PhotoDetails

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(L("gallery.details")).font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
                if !details.fileName.isEmpty { Text(details.fileName).font(.system(size: 14)).foregroundStyle(GalleryStyle.muted) }
                VStack(spacing: 0) {
                    row("kb_ui_ic_info_rest", details.when ?? "—", nil)
                    Divider().background(GalleryStyle.hairline)
                    row("kb_ui_ic_raw_rest", details.lens ?? L("gallery.no_lens"), details.device)
                    Divider().background(GalleryStyle.hairline)
                    row("kb_ui_ic_grid_rest", sizeLine, whatLine)
                }
                .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
                GallerySection(text: L("gallery.how_shot")).padding(.horizontal, -18)
                VStack(spacing: 0) {
                    HStack(spacing: 0) { cell(L("gallery.iso"), details.iso); cell(L("gallery.shutter"), details.shutter) }
                    Divider().background(GalleryStyle.hairline)
                    HStack(spacing: 0) { cell(L("gallery.aperture"), details.fnum); cell(L("gallery.focal"), details.focal) }
                }
                .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
                if !details.more.isEmpty {
                    GallerySection(text: L("gallery.more_details")).padding(.horizontal, -18)
                    VStack(spacing: 0) {
                        ForEach(details.more.indices, id: \.self) { i in
                            HStack(alignment: .top) {
                                Text(details.more[i].0).font(.system(size: 14)).foregroundStyle(.white)
                                Spacer(minLength: 10)
                                Text(details.more[i].1).font(.system(size: 14)).foregroundStyle(GalleryStyle.muted)
                                    .multilineTextAlignment(.trailing).textSelection(.enabled)
                            }
                            .padding(.horizontal, 16).frame(minHeight: 44)
                            if i < details.more.count - 1 { Divider().background(GalleryStyle.hairline) }
                        }
                    }
                    .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 16))
                }
            }
            .padding(20)
        }
        .background(GalleryStyle.page.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }

    private var sizeLine: String {
        let size = ByteCountFormatter.string(fromByteCount: details.bytes, countStyle: .file)
        guard details.width > 0, details.height > 0 else { return size }
        let mp = max(1, Int((Double(details.width) * Double(details.height) / 1e6).rounded()))
        return "\(mp) " + L("gallery.mp") + " · " + size
    }

    private var whatLine: String {
        var s = details.width > 0 && details.height > 0 ? "\(details.width) × \(details.height)" : ""
        if let f = details.format, !f.isEmpty { s += s.isEmpty ? f : " · " + f }
        return s
    }

    private func row(_ icon: String, _ title: String, _ sub: String?) -> some View {
        HStack(spacing: 14) {
            GalleryIcon(name: icon, size: 22, color: GalleryStyle.muted)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(.white)
                if let sub, !sub.isEmpty { Text(sub).font(.system(size: 13)).foregroundStyle(GalleryStyle.muted) }
            }
            Spacer()
        }
        .padding(.horizontal, 16).frame(minHeight: 64)
    }

    private func cell(_ title: String, _ value: String?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.system(size: 12.5)).foregroundStyle(GalleryStyle.muted).lineLimit(1).minimumScaleFactor(0.7)
            Text(value ?? "—").font(.system(size: 16, weight: .semibold).monospacedDigit()).foregroundStyle(.white).lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 11)
    }
}
