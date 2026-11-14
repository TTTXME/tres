import ImageIO
import Photos
import SwiftUI
import UIKit

/// ImageCompareFragment (P59b / P579): «Сравнение» with back and swap; two panes (stacked; side by side in landscape), each with
/// its label card (the EXIF line - ISO, shutter, aperture, focal length -, the size · the phone, the file name; a tap on either
/// pane switches both to «time · ISO» and back) and pinch / double-tap zoom; the «Зум и сдвиг вместе» switch card (on: both panes
/// follow each other) and the share disc (both panes as one picture).
@MainActor
struct CompareView: View {
    let library: GalleryLibrary
    @State private var first: PHAsset
    @State private var second: PHAsset
    @State private var images: [UIImage?] = [nil, nil]
    @State private var labels: [CompareLabel] = [CompareLabel(), CompareLabel()]
    @State private var timeMode = false
    @State private var share: ShareFiles?
    @State private var failed = false
    @AppStorage("gallery_compare_sync") private var sync = true
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var scale
    @Environment(\.verticalSizeClass) private var vertical

    init(library: GalleryLibrary, first: PHAsset, second: PHAsset) {
        self.library = library
        _first = State(initialValue: first)
        _second = State(initialValue: second)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                RoundIconButton(icon: "ui_ic_back", label: L("gallery.back")) { dismiss() }
                Text(L("gallery.compare_title")).font(.app(22, 700)).foregroundStyle(.white)
                Spacer()
                RoundIconButton(icon: "kb_ui_ic_swap_rest", label: L("gallery.swap")) {
                    let was = first
                    first = second
                    second = was
                    images.swapAt(0, 1)
                    labels.swapAt(0, 1)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(GalleryStyle.page)
            GeometryReader { geo in
                CompareZoomPair(first: images[0], second: images[1], landscape: vertical == .compact, sync: sync) {
                    withAnimation(.easeOut(duration: 0.2)) { timeMode.toggle() }
                }
                .overlay(alignment: .topLeading) { labelCard(labels[0]).padding(8) }
                .overlay(alignment: vertical == .compact ? .topTrailing : .bottomLeading) {
                    labelCard(labels[1]).padding(8)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            HStack(spacing: 12) {
                HStack {
                    Text(L("gallery.compare_sync")).font(.app(15)).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7)
                    Spacer()
                    Toggle("", isOn: $sync).labelsHidden().tint(GalleryStyle.accent)
                }
                .padding(.horizontal, 16).frame(minHeight: 52)
                .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
                .accessibilityElement(children: .combine)
                Button { shareComparison() } label: {
                    GalleryIcon(name: "kb_ui_ic_share_rest", size: 22).frame(width: 48, height: 48).background(GalleryStyle.card, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("gallery.compare_share"))
            }
            .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 12)
            .background(GalleryStyle.page)
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task {
            let side = UIScreen.main.bounds.height * scale * 1.5
            async let a = library.display(first, side: side)
            async let b = library.display(second, side: side)
            async let la = CompareLabel.of(first)
            async let lb = CompareLabel.of(second)
            images = [await a, await b]
            labels = [await la, await lb]
        }
        .sheet(item: $share) { s in ActivitySheet(items: s.urls) }
        .alert(L("gallery.failed"), isPresented: $failed) { Button(L("gallery.ok"), role: .cancel) {} }
    }

    private func labelCard(_ l: CompareLabel) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(timeMode ? l.timeLine : l.exifLine).font(.app(13, 600).monospacedDigit()).foregroundStyle(.white)
            if !timeMode {
                if !l.sizeLine.isEmpty { Text(l.sizeLine).font(.app(11)).foregroundStyle(GalleryStyle.muted) }
                if !l.fileName.isEmpty { Text(l.fileName).font(.app(11)).foregroundStyle(GalleryStyle.muted).lineLimit(1) }
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 12))
        .allowsHitTesting(false)
    }

    /// The share card: both pictures as one (stacked, or side by side in landscape), as the panes show them whole.
    private func shareComparison() {
        guard let a = images[0], let b = images[1] else {
            failed = true
            return
        }
        let side = vertical == .compact
        let w = side ? a.size.width + b.size.width : max(a.size.width, b.size.width)
        let h = side ? max(a.size.height, b.size.height) : a.size.height + b.size.height
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let joined = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { ctx in
            UIColor.black.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            a.draw(at: .zero)
            b.draw(at: side ? CGPoint(x: a.size.width, y: 0) : CGPoint(x: 0, y: a.size.height))
        }
        guard let data = joined.jpegData(compressionQuality: 0.92) else {
            failed = true
            return
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("SCAMERA_compare.jpg")
        try? FileManager.default.removeItem(at: url)
        if (try? data.write(to: url)) != nil { share = ShareFiles(urls: [url]) } else { failed = true }
    }
}

/// A pane's label: «ISO 100 · 1/120 s · f/1.8 · 6.9 mm», «12 Мп · vivo X200 Ultra», the file name; or «Сегодня, 11:17 · ISO 100».
struct CompareLabel {
    var exifLine = "", sizeLine = "", fileName = "", timeLine = ""

    @MainActor static func of(_ asset: PHAsset) async -> CompareLabel {
        let d = await PhotoDetails.of(asset)
        var l = CompareLabel()
        l.exifLine = [d.iso, d.shutter, d.fnum, d.focal].compactMap { $0 }.joined(separator: " · ")
        if l.exifLine.isEmpty { l.exifLine = "—" }
        var size: [String] = []
        if d.width > 0, d.height > 0 { size.append("\(max(1, Int((Double(d.width) * Double(d.height) / 1e6).rounded()))) " + L("gallery.mp")) }
        if let dev = d.device { size.append(dev) }
        l.sizeLine = size.joined(separator: " · ")
        l.fileName = d.fileName
        l.timeLine = [d.when, d.iso].compactMap { $0 }.joined(separator: " · ")
        return l
    }
}

/// The two panes: a UIScrollView each (pinch, double tap), PaneLink - with `sync` on, a zoom or a pan of one moves the other to the
/// same place; a single tap on either calls `tap`.
private struct CompareZoomPair: UIViewRepresentable {
    let first: UIImage?
    let second: UIImage?
    let landscape: Bool
    let sync: Bool
    let tap: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(tap: tap) }

    func makeUIView(context: Context) -> UIStackView {
        let stack = UIStackView()
        stack.distribution = .fillEqually
        stack.spacing = 4
        stack.backgroundColor = .black
        for _ in 0..<2 {
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
            let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
            double.numberOfTapsRequired = 2
            s.addGestureRecognizer(double)
            let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap))
            single.require(toFail: double)
            s.addGestureRecognizer(single)
            stack.addArrangedSubview(s)
            context.coordinator.panes.append(s)
            context.coordinator.views.append(v)
        }
        return stack
    }

    func updateUIView(_ stack: UIStackView, context: Context) {
        let c = context.coordinator
        c.tap = tap
        c.sync = sync
        stack.axis = landscape ? .horizontal : .vertical
        for (i, image) in [first, second].enumerated() where i < c.views.count {
            let v = c.views[i], s = c.panes[i]
            if v.image !== image {
                v.image = image
                s.zoomScale = 1
            }
        }
        DispatchQueue.main.async {
            for (s, v) in zip(c.panes, c.views) where s.zoomScale == 1 {
                v.frame = s.bounds
                s.contentSize = s.bounds.size
            }
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var tap: () -> Void
        var sync = true
        var panes: [UIScrollView] = []
        var views: [UIImageView] = []
        private var following = false

        init(tap: @escaping () -> Void) { self.tap = tap }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            guard let i = panes.firstIndex(of: scrollView) else { return nil }
            return views[i]
        }

        /// PaneLink: the other pane to this one's zoom and offset (scaled to its size).
        private func follow(_ s: UIScrollView) {
            guard sync, !following, let i = panes.firstIndex(of: s), panes.count == 2 else { return }
            let o = panes[1 - i]
            following = true
            if abs(o.zoomScale - s.zoomScale) > 0.001 { o.setZoomScale(s.zoomScale, animated: false) }
            let kx = s.bounds.width > 0 ? o.bounds.width / s.bounds.width : 1, ky = s.bounds.height > 0 ? o.bounds.height / s.bounds.height : 1
            let maxX = max(0, o.contentSize.width - o.bounds.width), maxY = max(0, o.contentSize.height - o.bounds.height)
            o.contentOffset = CGPoint(x: min(maxX, max(0, s.contentOffset.x * kx)), y: min(maxY, max(0, s.contentOffset.y * ky)))
            following = false
        }

        func scrollViewDidZoom(_ scrollView: UIScrollView) { follow(scrollView) }
        func scrollViewDidScroll(_ scrollView: UIScrollView) { follow(scrollView) }

        @objc func singleTap() { tap() }

        @objc func doubleTap(_ g: UITapGestureRecognizer) {
            guard let s = g.view as? UIScrollView, let i = panes.firstIndex(of: s) else { return }
            if s.zoomScale > 1.01 {
                s.setZoomScale(1, animated: true)
            } else {
                let p = g.location(in: views[i])
                let k: CGFloat = 3
                let size = CGSize(width: s.bounds.width / k, height: s.bounds.height / k)
                s.zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: true)
            }
        }
    }
}
