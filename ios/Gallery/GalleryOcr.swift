import Photos
import SwiftUI
import UIKit
import Vision

/// P504 OcrEngine on the iPhone: the text of a picture by Vision (VNRecognizeTextRequest, accurate, with language correction) in
/// the languages asked - «Авто» is English, Russian and the phone's language when Vision reads it (Android: Tesseract's eng+rus and
/// the phone's). Lines top to bottom, one a line.
enum OcrEngine {
    /// OcrEngine.LANGUAGES (Tesseract's codes) and Vision's for them; the sheet offers the ones this iPhone's Vision reads.
    static let languages: [(code: String, vision: String)] = [
        ("eng", "en-US"), ("rus", "ru-RU"), ("deu", "de-DE"), ("fra", "fr-FR"), ("spa", "es-ES"), ("ita", "it-IT"), ("por", "pt-BR"),
        ("chi_sim", "zh-Hans"), ("jpn", "ja-JP"), ("kor", "ko-KR"), ("ara", "ar-SA"), ("hin", "hi-IN"),
    ]

    /// The Vision languages this phone recognises (accurate level).
    static let supported: Set<String> = {
        let r = VNRecognizeTextRequest()
        r.recognitionLevel = .accurate
        return Set((try? r.supportedRecognitionLanguages()) ?? ["en-US"])
    }()

    /// OcrEngine.name: the language in the interface's language.
    static func name(_ code: String) -> String { L("ocr.lang." + code) }

    /// The languages of the sheet's menu: Tesseract codes Vision reads here.
    static var offered: [String] { languages.filter { supported.contains($0.vision) }.map(\.code) }

    /// OcrEngine.autoLanguages: English, Russian and the phone's language (when Vision reads it).
    static func autoLanguages() -> [String] {
        var out = ["en-US", "ru-RU"].filter { supported.contains($0) }
        if let phone = Locale.preferredLanguages.first {
            let base = String(phone.prefix(2))
            if let match = supported.first(where: { $0 == phone || $0.hasPrefix(base + "-") || $0 == base }), !out.contains(match) {
                out.append(match)
            }
        }
        return out.isEmpty ? ["en-US"] : out
    }

    /// The languages for a sheet choice: nil «Авто», «eng» English alone, any other with English (as code + "+eng").
    static func visionLanguages(_ code: String?) -> [String] {
        guard let code, let v = languages.first(where: { $0.code == code })?.vision else { return autoLanguages() }
        return code == "eng" ? [v] : [v, "en-US"]
    }

    /// The photo's picture at about `side` px on its long side (upright), nil when it cannot be had (an iCloud photo offline).
    static func load(_ asset: PHAsset, side: CGFloat, network: Bool) async -> UIImage? {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .exact
        options.isNetworkAccessAllowed = network
        options.isSynchronous = false
        return await withCheckedContinuation { cont in
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: side, height: side), contentMode: .aspectFit,
                                                  options: options) { image, _ in cont.resume(returning: image) }
        }
    }

    /// The text of `image` in `languages`: "" when there is none, nil on an error.
    static func recognize(_ image: UIImage, languages: [String]) async -> String? {
        guard let cg = image.cgImage else { return nil }
        let orientation = CGImagePropertyOrientation(image.imageOrientation)
        return await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            DispatchQueue.global(qos: .utility).async {
                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate
                request.usesLanguageCorrection = true
                request.recognitionLanguages = languages
                do {
                    try VNImageRequestHandler(cgImage: cg, orientation: orientation, options: [:]).perform([request])
                } catch {
                    cont.resume(returning: nil)
                    return
                }
                let lines = (request.results ?? [])
                    .sorted { a, b in
                        // top to bottom (Vision's y grows upwards), then left to right on one line
                        abs(a.boundingBox.midY - b.boundingBox.midY) > min(a.boundingBox.height, b.boundingBox.height) / 2
                            ? a.boundingBox.midY > b.boundingBox.midY : a.boundingBox.minX < b.boundingBox.minX
                    }
                    .compactMap { $0.topCandidates(1).first?.string }
                cont.resume(returning: lines.joined(separator: "\n"))
            }
        }
    }
}

extension CGImagePropertyOrientation {
    init(_ o: UIImage.Orientation) {
        switch o {
        case .up: self = .up
        case .down: self = .down
        case .left: self = .left
        case .right: self = .right
        case .upMirrored: self = .upMirrored
        case .downMirrored: self = .downMirrored
        case .leftMirrored: self = .leftMirrored
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}

/// P504 / P617 OcrIndex: the words read on each photo, kept by the photo's id and its modification time (Application Support,
/// gallery_ocr.json), so «Поиск» finds a photo by the text on it. While the gallery is open a background task reads the photos not
/// read yet, the newest first (a 1600 px copy, «Авто»'s languages), as OcrPace allows: 6 s after the gallery opens, 2.5 s after the
/// grid last moved, not while the phone is hot, resting as long as a photo took (at most half the time busy) and 5 s after every
/// 20; it stops on a low battery (under 25 %, not charging) and with the gallery; a read cut short is not stored, a photo Vision
/// fails on is not tried again until the app starts again.
@MainActor
@Observable
final class OcrIndex {
    static let shared = OcrIndex()

    struct Entry: Codable {
        let modified: Double
        let text: String
    }

    /// Grows whenever photos are read: a search made before reads again.
    private(set) var version = 0
    @ObservationIgnored private var entries: [String: Entry] = [:]
    @ObservationIgnored private var lower: [String: String] = [:]
    @ObservationIgnored private var loaded = false
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var library: [PHAsset] = []
    @ObservationIgnored private var libraryVersion = 0
    @ObservationIgnored private var startAt = Date.distantFuture
    @ObservationIgnored private var quietUntil = Date.distantPast
    @ObservationIgnored private var failed: Set<String> = []
    @ObservationIgnored private var dirty = 0

    static let indexSide: CGFloat = 1600, startDelay: TimeInterval = 6, quiet: TimeInterval = 2.5
    static let minRest: TimeInterval = 0.25, batch = 20, batchRest: TimeInterval = 5, minBattery: Float = 0.25, maxFailures = 5

    nonisolated init() {}

    nonisolated private static var file: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("gallery_ocr.json")
    }

    private static func modified(_ a: PHAsset) -> Double { (a.modificationDate ?? a.creationDate)?.timeIntervalSince1970 ?? 0 }

    /// OcrIndex.indexable: photos (not videos, not a RAW alone).
    static func indexable(_ a: PHAsset, kind: GalleryLibrary.AssetKind?) -> Bool {
        guard a.mediaType == .image else { return false }
        if let k = kind, k.format == .raw { return false }
        return true
    }

    /// The text on a photo (lower case, «ё» as «е», for matching), nil when not read yet or changed since.
    func searchText(_ a: PHAsset) -> String? {
        guard let e = entries[a.localIdentifier], e.modified == Self.modified(a) else { return nil }
        return lower[a.localIdentifier]
    }

    /// The text on a photo as read.
    func text(_ a: PHAsset) -> String? {
        guard let e = entries[a.localIdentifier], e.modified == Self.modified(a) else { return nil }
        return e.text
    }

    func load() {
        if loaded { return }
        loaded = true
        if let data = try? Data(contentsOf: Self.file), let stored = try? JSONDecoder().decode([String: Entry].self, from: data) {
            entries = stored
            for (k, e) in stored { lower[k] = GallerySearch.normalise(e.text) }
        }
        version += 1
    }

    /// Stores the text read on a photo (here, and on disk after a few).
    func put(_ a: PHAsset, text: String) {
        load()
        entries[a.localIdentifier] = Entry(modified: Self.modified(a), text: text)
        lower[a.localIdentifier] = GallerySearch.normalise(text)
        version += 1
        dirty += 1
        if dirty >= 5 { save() }
    }

    func save() {
        guard dirty > 0 else { return }
        dirty = 0
        let snapshot = entries
        Task.detached(priority: .utility) {
            if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: OcrIndex.file, options: .atomic) }
        }
    }

    /// The grid moved or got a new list: the index waits `quiet` from now.
    func touch() { quietUntil = Date().addingTimeInterval(Self.quiet) }

    /// Reads the photos of `list` (the newest first) not read yet, in the background; a running index takes the new list and goes on.
    func start(_ list: [PHAsset], kinds: [String: GalleryLibrary.AssetKind]) {
        load()
        library = list.filter { Self.indexable($0, kind: kinds[$0.localIdentifier]) }
        libraryVersion += 1
        touch()
        if worker != nil { return }
        startAt = Date().addingTimeInterval(Self.startDelay)
        worker = Task(priority: .background) { [weak self] in await self?.run() }
    }

    /// The gallery closed: the read in progress is cut short and not stored.
    func stop() {
        worker?.cancel()
        worker = nil
        save()
    }

    private func pending(_ list: [PHAsset]) -> [PHAsset] {
        list.filter { text($0) == nil && !failed.contains($0.localIdentifier) }
    }

    /// OcrPace.waitMs: how long until the next photo may be read (0: now).
    private func waitTime() -> TimeInterval {
        let now = Date()
        var wait = max(startAt.timeIntervalSince(now), quietUntil.timeIntervalSince(now))
        let thermal = ProcessInfo.processInfo.thermalState
        if thermal == .serious || thermal == .critical { wait = max(wait, 1) }
        return max(0, wait)
    }

    private func gate() async -> Bool {
        while !Task.isCancelled {
            let w = waitTime()
            if w <= 0 { return true }
            try? await Task.sleep(nanoseconds: UInt64(min(1, max(0.001, w)) * 1e9))
        }
        return false
    }

    private static func batteryOk() -> Bool {
        let d = UIDevice.current
        let was = d.isBatteryMonitoringEnabled
        d.isBatteryMonitoringEnabled = true
        defer { d.isBatteryMonitoringEnabled = was }
        if d.batteryState == .charging || d.batteryState == .full || d.batteryLevel < 0 { return true }
        return d.batteryLevel >= minBattery
    }

    private func run() async {
        var done = 0, failedInARow = 0
        var seenVersion = -1
        var todo: [PHAsset] = []
        var at = 0
        let languages = OcrEngine.autoLanguages()
        defer {
            if worker != nil, Task.isCancelled == false { worker = nil }
            save()
            SessionLog.i("GalleryOcr", "index: \(done) photos read")
        }
        while !Task.isCancelled {
            if seenVersion != libraryVersion || at >= todo.count {
                seenVersion = libraryVersion
                todo = pending(library)
                at = 0
                if todo.isEmpty { return }
            }
            guard await gate() else { return }
            if done % 10 == 0, !Self.batteryOk() {
                SessionLog.i("GalleryOcr", "index: paused on a low battery after \(done)")
                return
            }
            let a = todo[at]
            at += 1
            if text(a) != nil { continue }
            let t0 = Date()
            var read: String?
            if let image = await OcrEngine.load(a, side: Self.indexSide, network: false) {
                read = await OcrEngine.recognize(image, languages: languages)
            }
            let took = Date().timeIntervalSince(t0)
            if Task.isCancelled { return } // stopped meanwhile: a read cut short is not stored
            guard let read else {
                failed.insert(a.localIdentifier)
                failedInARow += 1
                if failedInARow >= Self.maxFailures {
                    SessionLog.w("GalleryOcr", "index: Vision failed on \(failedInARow) photos in a row, stopped until the next visit")
                    return
                }
                try? await Task.sleep(nanoseconds: UInt64(max(Self.minRest, took) * 1e9))
                continue
            }
            failedInARow = 0
            put(a, text: read)
            done += 1
            var rest = max(Self.minRest, took)
            if done % Self.batch == 0 { rest += Self.batchRest }
            try? await Task.sleep(nanoseconds: UInt64(rest * 1e9))
        }
    }
}

/// P504 TextSheet: «Текст на фото» - the photo read by Vision («Авто», or a language from the menu), the text selectable to copy a
/// part, «Копировать всё» and «Поделиться» for the whole; a read in «Авто» also goes into the search index.
@MainActor
struct TextSheet: View {
    let asset: PHAsset
    @State private var language: String?
    @State private var status = ""
    @State private var result = ""
    @State private var reading = false
    @State private var run = 0
    @State private var share: [Any]?
    @State private var toast: String?

    /// TextSheet.SHEET_SIDE: small print needs more than the index's 1600
    static let side: CGFloat = 2600

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("ocr.title")).font(.app(22, 700)).foregroundStyle(.white)
                HStack {
                    Text(L("ocr.language")).font(.app(16)).foregroundStyle(.white)
                    Spacer()
                    Menu {
                        Button(L("ocr.auto")) { pick(nil) }
                        ForEach(OcrEngine.offered, id: \.self) { code in
                            Button(OcrEngine.name(code)) { pick(code) }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(language.map(OcrEngine.name) ?? L("ocr.auto")).font(.app(16)).foregroundStyle(GalleryStyle.accent)
                            Image(systemName: "chevron.up.chevron.down").font(.app(12)).foregroundStyle(GalleryStyle.accent)
                        }
                    }
                    .accessibilityIdentifier("ocr_language")
                }
                .padding(.horizontal, 16).frame(minHeight: 52)
                .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 16))
                .padding(.top, 10)
                HStack(spacing: 8) {
                    if reading { ProgressView().tint(.white).controlSize(.small) }
                    Text(status).font(.app(14)).foregroundStyle(GalleryStyle.muted)
                }
                if !result.isEmpty {
                    SelectableText(text: result)
                        .frame(minHeight: 96)
                        .padding(16)
                        .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
                        .transition(.opacity)
                }
                HStack(spacing: 10) {
                    Button {
                        if !result.isEmpty { share = [result] }
                    } label: {
                        Text(L("gallery.share")).font(.app(17, 600)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 50).background(GalleryStyle.card, in: Capsule())
                    }
                    .buttonStyle(PressScaleStyle(scale: 0.97))
                    Button {
                        guard !result.isEmpty else { return }
                        UIPasteboard.general.string = result
                        toast = L("ocr.copied")
                    } label: {
                        Text(L("ocr.copy_all")).font(.app(17, 600)).foregroundStyle(GalleryStyle.ink)
                            .frame(maxWidth: .infinity, minHeight: 50).background(GalleryStyle.accent, in: Capsule())
                    }
                    .buttonStyle(PressScaleStyle(scale: 0.97))
                }
                .opacity(result.isEmpty ? 0.4 : 1)
                .padding(.top, 14)
            }
            .padding(20)
            .animation(.easeOut(duration: 0.2), value: result)
        }
        .background(GalleryStyle.page.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let toast {
                GalleryPill(text: toast).padding(.bottom, 20).transition(.opacity)
                    .task(id: toast) {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        self.toast = nil
                    }
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: Binding(get: { share != nil }, set: { if !$0 { share = nil } })) { ActivitySheet(items: share ?? []) }
        .task { await read() }
    }

    private func pick(_ code: String?) {
        language = code
        Task { await read() }
    }

    private func read() async {
        run += 1
        let id = run
        let code = language
        status = L("ocr.reading")
        result = ""
        reading = true
        var text: String?
        if let image = await OcrEngine.load(asset, side: Self.side, network: true) {
            text = await OcrEngine.recognize(image, languages: OcrEngine.visionLanguages(code))
        }
        guard id == run else { return }
        reading = false
        if let text {
            if code == nil { OcrIndex.shared.put(asset, text: text) }
            status = text.isEmpty ? L("ocr.none") : L("ocr.ready")
            result = text
        } else {
            status = L("ocr.failed")
        }
    }
}

/// A read-only UITextView: a part of the text can be selected and copied (SwiftUI's Text selects only the whole).
struct SelectableText: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let v = UITextView()
        v.isEditable = false
        v.isSelectable = true
        v.isScrollEnabled = false
        v.backgroundColor = .clear
        v.textContainerInset = .zero
        v.textContainer.lineFragmentPadding = 0
        v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        let style = NSMutableParagraphStyle()
        style.lineHeightMultiple = 1.25
        v.attributedText = NSAttributedString(string: text, attributes: [
            .font: AppFont.uiFont(size: 15, weight: AppFont.regular), .foregroundColor: UIColor.white, .paragraphStyle: style,
        ])
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let w = proposal.width ?? UIScreen.main.bounds.width - 72
        let fit = uiView.sizeThatFits(CGSize(width: w, height: .greatestFiniteMagnitude))
        return CGSize(width: w, height: fit.height)
    }
}
