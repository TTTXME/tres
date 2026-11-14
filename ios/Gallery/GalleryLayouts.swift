import ImageIO
import Photos
import SwiftUI
import UIKit

/// P622 GalleryLayout: how the gallery lays its photos out - «Сетка» (cards by day, the default), «Список» (one row per photo: a
/// thumbnail, the name, a secondary line and tags) and «Лента» (an edge-to-edge square grid under month titles, zoomed by a pinch
/// or the floating control between «Годы», «Месяцы» and «Все фото»). Kept in «gallery_ui» with the timeline's level and columns
/// and the last photo opened (the list tints it).
enum GalleryLayout {
    enum Mode: String, CaseIterable {
        case grid = "GRID", list = "LIST", timeline = "TIMELINE"
        var label: String {
            switch self {
            case .grid: return L("gallery.mode.grid")
            case .list: return L("gallery.mode.list")
            case .timeline: return L("gallery.mode.timeline")
            }
        }
    }

    enum Level: String, CaseIterable {
        case years = "YEARS", months = "MONTHS", all = "ALL"
        var label: String {
            switch self {
            case .years: return L("gallery.level.years")
            case .months: return L("gallery.level.months")
            case .all: return L("gallery.level.all")
            }
        }
    }

    /// The timeline's columns in portrait, as the stock library's zoom steps.
    static let tlMinColumns = 3, tlMaxColumns = 7, tlDefaultColumns = 4

    static func clampTimeline(_ n: Int) -> Int { max(tlMinColumns, min(tlMaxColumns, n)) }

    /// GalleryLayout.zoomStep: a spread (fingers apart) zooms in - fewer columns, then months to every photo, years to months; a
    /// pinch zooms out the other way. One step.
    static func zoomStep(_ level: Level, _ columns: Int, spread: Bool) -> (Level, Int) {
        if spread {
            if level == .years { return (.months, columns) }
            if level == .months { return (.all, tlMaxColumns) }
            return (.all, clampTimeline(columns - 1))
        }
        if level == .all {
            if columns >= tlMaxColumns { return (.months, columns) }
            return (.all, columns + 1)
        }
        return (.years, columns)
    }

    // ---------------------------------------------------------------- P622 TimelineRows' shape tokens

    /// The share of the way from the phone library's token to this app's (the owner's «на 20% ближе к нашему»).
    static func lerp(_ library: CGFloat, _ ours: CGFloat) -> CGFloat { library + (ours - library) * 0.2 }
    static let gap = lerp(1, 6), tileRadius = lerp(0, 14), edge = lerp(0, 12), cardRadius = lerp(10, 24), titleSize = lerp(20, 12)

    // ---------------------------------------------------------------- the last photo opened

    static var lastOpened: String {
        get { UserDefaults.standard.dictionary(forKey: "gallery_ui")?["last_opened"] as? String ?? "" }
        set {
            var d = UserDefaults.standard.dictionary(forKey: "gallery_ui") ?? [:]
            d["last_opened"] = newValue
            UserDefaults.standard.set(d, forKey: "gallery_ui")
        }
    }
}

/// The rows' texts (GalleryUi.dayLabel / dayCount / size, RowText, PhotoInfo.megapixels).
enum GalleryText {
    static var ru: Bool { (Bundle.main.preferredLocalizations.first ?? "en").hasPrefix("ru") }

    /// GalleryUi.dayLabel: «Сегодня», «Вчера», «6 октября» (the year when it is not this one).
    static func dayLabel(_ date: Date, now: Date) -> String {
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return L("gallery.today") }
        if let y = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(date, inSameDayAs: y) { return L("gallery.yesterday") }
        let c = cal.dateComponents([.year, .month, .day], from: date)
        var s = String(format: L("gallery.day_month"), c.day ?? 1, PhotoDetails.monthOf((c.month ?? 1) - 1))
        if c.year != cal.component(.year, from: now) { s = String(format: L("gallery.day_year"), s, c.year ?? 0) }
        return s
    }

    /// GalleryUi.dayCount: «3 фото», «3 фото · 1 видео», «2 видео».
    static func dayCount(_ photos: Int, _ videos: Int = 0) -> String {
        let p = String(format: L(photos == 1 ? "gallery.n_photo" : "gallery.n_photos"), photos)
        if videos <= 0 { return p }
        let v = String(format: L(videos == 1 ? "gallery.n_video" : "gallery.n_videos"), videos)
        return photos > 0 ? p + " · " + v : v
    }

    /// RowText.counts: «128 фото · 3 видео · 12 RAW» (zero parts left out), «Пусто».
    static func counts(_ items: ArraySlice<PHAsset>, kinds: [String: GalleryLibrary.AssetKind]) -> String {
        var photos = 0, videos = 0, raws = 0
        for a in items {
            if a.mediaType == .video { videos += 1 } else {
                photos += 1
                if kinds[a.localIdentifier]?.format == .raw { raws += 1 }
            }
        }
        var parts: [String] = []
        if photos > 0 { parts.append(String(format: L(photos == 1 ? "gallery.n_photo" : "gallery.n_photos"), photos)) }
        if videos > 0 { parts.append(String(format: L(videos == 1 ? "gallery.n_video" : "gallery.n_videos"), videos)) }
        if raws > 0 { parts.append("\(raws) RAW") }
        return parts.isEmpty ? L("gallery.empty_counts") : parts.joined(separator: " · ")
    }

    /// GalleryUi.size: «12,4 МБ», «830 КБ», «512 Б».
    static func size(_ bytes: Int64) -> String {
        if bytes >= 1 << 20 { return String(format: L("gallery.size_mb"), PhotoDetails.decimal(Double(bytes) / Double(1 << 20), 1)) }
        if bytes >= 1 << 10 { return String(format: L("gallery.size_kb"), Int((Double(bytes) / 1024).rounded())) }
        return String(format: L("gallery.size_b"), Int(bytes))
    }

    /// PhotoInfo.megapixels: whole megapixels from 20 MP up, one decimal below (12,6), two below 1 MP.
    static func megapixels(_ w: Int, _ h: Int) -> String? {
        guard w > 0, h > 0 else { return nil }
        let mp = Double(w) * Double(h) / 1e6
        let digits = mp >= 19.95 ? 0 : mp >= 0.995 ? 1 : 2
        var s = String(format: "%.\(digits)f", mp)
        if digits > 0 {
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
        }
        return s.replacingOccurrences(of: ".", with: L("gallery.decimal_separator")) + " " + L("gallery.mp")
    }

    /// A tag's look (RowText): the format (accent tint), a fact about the picture (grey), the merge / HDR (warm).
    enum TagKind { case format, plain, hdr }
    struct Tag: Hashable { let label: String; let kind: TagKind }

    /// RowText.BIG_MP: from here the megapixels are a tag of their own.
    static let bigMp = 40e6

    /// RowText.secondary: «Сегодня, 11:17 · 12,6 Мп · 12,4 МБ» (a big photo: «8192×6144»), a video: the date and the size.
    static func secondary(_ asset: PHAsset, bytes: Int64, now: Date) -> String {
        var s = asset.creationDate.map { PhotoDetails.dayTime($0, now: now) } ?? ""
        let w = asset.pixelWidth, h = asset.pixelHeight
        if asset.mediaType != .video, w > 0, h > 0, let mp = megapixels(w, h) {
            s += Double(w) * Double(h) < bigMp ? " · " + mp : " · \(w)×\(h)"
        }
        if bytes > 0 { s += " · " + size(bytes) }
        return s
    }

    /// RowText.tags: the format («DNG», «RAW + JPEG», «HEIC», a video's container), big megapixels, the route, Ultra HDR.
    static func tags(_ asset: PHAsset, kind: GalleryLibrary.AssetKind?, route: String?, ultraHdr: Bool) -> [Tag] {
        var out: [Tag] = []
        let ext = ((kind?.fileName ?? "") as NSString).pathExtension.uppercased()
        if asset.mediaType == .video {
            if !ext.isEmpty { out.append(Tag(label: ext, kind: .format)) }
        } else if let k = kind {
            switch k.format {
            case .raw: out.append(Tag(label: ext.isEmpty ? "RAW" : ext, kind: .format))
            case .jpeg: out.append(Tag(label: k.raw ? "RAW + JPEG" : "JPEG", kind: .format))
            case .heic: out.append(Tag(label: k.raw ? "RAW + HEIC" : "HEIC", kind: .format))
            case .other: if !ext.isEmpty { out.append(Tag(label: ext, kind: .format)) }
            }
        }
        let w = asset.pixelWidth, h = asset.pixelHeight
        if asset.mediaType != .video, Double(w) * Double(h) >= bigMp, let mp = megapixels(w, h) { out.append(Tag(label: mp, kind: .plain)) }
        if let route, !route.isEmpty { out.append(Tag(label: route, kind: .hdr)) }
        if ultraHdr { out.append(Tag(label: "Ultra HDR", kind: .hdr)) }
        return out
    }
}

/// ItemMeta: what the list's rows show beyond PHAsset - the file's bytes, the merge route of a SCAMERA photo and whether it is
/// Ultra HDR - read in the background from the head of the file (its properties), cached for the gallery's life.
@MainActor
@Observable
final class RowMeta {
    struct Info: Sendable { var bytes: Int64 = 0; var route: String?; var ultraHdr = false }
    static let shared = RowMeta()
    private(set) var infos: [String: Info] = [:]
    @ObservationIgnored private var asked: Set<String> = []

    func info(_ asset: PHAsset) -> Info? {
        let id = asset.localIdentifier
        if let i = infos[id] { return i }
        if !asked.contains(id) {
            asked.insert(id)
            Task.detached(priority: .utility) {
                let i = await RowMeta.read(asset)
                await MainActor.run { RowMeta.shared.infos[id] = i }
            }
        }
        return nil
    }

    /// The first 512 KB of the file (where a JPEG's EXIF and XMP and a HEIC's item properties sit): the route, Ultra HDR.
    nonisolated static func read(_ asset: PHAsset) async -> Info {
        var info = Info()
        let res: PHAssetResource? = asset.mediaType == .video ? GalleryLibrary.videoResource(asset)
            : { let r = GalleryLibrary.resources(asset); return r.photo ?? r.raw }()
        guard let r = res else { return info }
        if let n = r.value(forKey: "fileSize") as? NSNumber { info.bytes = n.int64Value }
        if asset.mediaType == .video { return info }
        let head = await headData(r, limit: 512 * 1024)
        info.ultraHdr = head.range(of: Data("hdrgm:Version".utf8)) != nil
            || head.range(of: Data("urn:com:apple:photo:2020:aux:hdrgainmap".utf8)) != nil
        if let src = CGImageSourceCreateWithData(head as CFData, nil),
           let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
           let tiff = p[kCGImagePropertyTIFFDictionary] as? [CFString: Any],
           let desc = tiff[kCGImagePropertyTIFFImageDescription] as? String {
            info.route = routeOf(desc)
        }
        return info
    }

    /// GalleryFormat.routeOf: «Route=Hybrid» / «Route=SHDR» → SHDR, «Route=SCAM HDR» as it is.
    nonisolated static func routeOf(_ description: String) -> String? {
        guard let m = description.range(of: "Route=(SHDR|Hybrid|SCAM HDR)", options: .regularExpression) else { return nil }
        let route = String(description[m].dropFirst("Route=".count))
        return route == "Hybrid" ? "SHDR" : route
    }

    /// Up to `limit` bytes from the start of a resource (the request is cancelled once they are in).
    nonisolated static func headData(_ r: PHAssetResource, limit: Int) async -> Data {
        final class Box: @unchecked Sendable { var data = Data(); var id: PHAssetResourceDataRequestID = 0; var done = false }
        let box = Box()
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = false
        return await withCheckedContinuation { (c: CheckedContinuation<Data, Never>) in
            let lock = NSLock()
            func finish() {
                lock.lock()
                let first = !box.done
                box.done = true
                lock.unlock()
                if first { c.resume(returning: box.data) }
            }
            box.id = PHAssetResourceManager.default().requestData(for: r, options: options, dataReceivedHandler: { chunk in
                lock.lock()
                box.data.append(chunk)
                let enough = box.data.count >= limit
                lock.unlock()
                if enough {
                    PHAssetResourceManager.default().cancelDataRequest(box.id)
                    finish()
                }
            }, completionHandler: { _ in finish() })
        }
    }
}

/// The sections of the views (LibraryAdapter.rowsOf by day, TimelineSections by month / year): runs of the list's items in its
/// order, a title and the run.
struct GalleryRun: Identifiable {
    let id: String
    let title: String
    let range: Range<Int>
}

enum GallerySections {
    /// The grid's and the list's days («Сегодня», «6 октября»).
    static func days(_ items: [PHAsset], now: Date) -> [GalleryRun] {
        runs(items) { d in
            let c = Calendar.current.dateComponents([.year, .month, .day], from: d)
            return "d\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)"
        } title: { GalleryText.dayLabel($0, now: now) }
    }

    /// The timeline's months («Ноябрь 2026»).
    static func months(_ items: [PHAsset]) -> [GalleryRun] {
        runs(items) { d in
            let c = Calendar.current.dateComponents([.year, .month], from: d)
            return "m\(c.year ?? 0)-\(c.month ?? 0)"
        } title: { d in
            let c = Calendar.current.dateComponents([.year, .month], from: d)
            return GalleryLibrary.monthName((c.month ?? 1) - 1) + " \(c.year ?? 0)"
        }
    }

    /// The timeline's years («2026»).
    static func years(_ items: [PHAsset]) -> [GalleryRun] {
        runs(items) { "y\(Calendar.current.component(.year, from: $0))" } title: { String(Calendar.current.component(.year, from: $0)) }
    }

    private static func runs(_ items: [PHAsset], key: (Date) -> String, title: (Date) -> String) -> [GalleryRun] {
        var out: [GalleryRun] = []
        var i = 0
        while i < items.count {
            let d = items[i].creationDate ?? .distantPast
            let k = key(d)
            var j = i + 1
            while j < items.count, key(items[j].creationDate ?? .distantPast) == k { j += 1 }
            out.append(GalleryRun(id: k + "#\(i)", title: title(d), range: i..<j))
            i = j
        }
        return out
    }

    /// TimelineSections.monthCovers: the first item of up to `max` different months in `range` (a year card's covers).
    static func monthCovers(_ items: [PHAsset], _ range: Range<Int>, max: Int) -> [Int] {
        var out: [Int] = []
        var last = ""
        for i in range where out.count < max {
            let c = Calendar.current.dateComponents([.year, .month], from: items[i].creationDate ?? .distantPast)
            let k = "\(c.year ?? 0)-\(c.month ?? 0)"
            if k == last { continue }
            last = k
            out.append(i)
        }
        return out
    }
}
