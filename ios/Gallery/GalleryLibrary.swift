import Foundation
import ImageIO
import Photos
import UIKit
import UniformTypeIdentifiers

/// The gallery's photos (Android's SGallery over DCIM/SCAMERA): PhotoKit's «SCAMERA» album the app saves into (MediaLibrary), or
/// every photo of the library; what the «Фото» tab shows of them (GalleryView: the order, the format filter, favourites only, the
/// years, the columns), the albums (AlbumsPage), the search (GallerySearch), thumbnails, favourites, deletion, the original's data
/// for the viewer and the editor, the edited copy. Favourites are PhotoKit's own (Android keeps its own list because MediaStore
/// asks the user per photo of another app; PhotoKit does not). Videos are listed with the photos (Android's gallery shows both);
/// a video opens in the gallery's player (Video/VideoPlayerView).
@MainActor
@Observable
final class GalleryLibrary {
    enum Source: Int { case camera, all }
    enum Access { case unknown, denied, granted }

    nonisolated static let albumTitle = "SCAMERA"

    private(set) var access: Access = .unknown
    /// Every photo of the source, in the chosen order (before the filter).
    private(set) var assets: [PHAsset] = []
    var source: Source = GalleryPrefs.source { didSet { if oldValue != source { GalleryPrefs.source = source; reload() } } }
    private(set) var hasAlbum = false
    /// What the gallery knows of each photo beyond PHAsset (its file name and format), filled off the main thread.
    private(set) var kinds: [String: AssetKind] = [:]
    /// GalleryView's view settings; a change re-reads the library (the order) or only the filter.
    var prefs = GalleryPrefs.load() { didSet { prefs.save(); if oldValue.oldestFirst != prefs.oldestFirst { reload() } } }
    /// Bumped by every change of the library (the albums page and open albums read again).
    private(set) var version = 0
    private let imageManager = PHCachingImageManager()
    private var observer: Observer?
    private var kindTask: Task<Void, Never>?

    nonisolated init() {}

    func start() async {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let granted: Bool
        if status == .notDetermined {
            let s = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            granted = s == .authorized || s == .limited
        } else {
            granted = status == .authorized || status == .limited
        }
        access = granted ? .granted : .denied
        guard granted else { return }
        if observer == nil {
            let o = Observer { [weak self] in Task { @MainActor in self?.reload() } }
            PHPhotoLibrary.shared().register(o)
            observer = o
        }
        reload()
    }

    func stop() {
        if let observer { PHPhotoLibrary.shared().unregisterChangeObserver(observer) }
        observer = nil
        kindTask?.cancel()
    }

    nonisolated static func album() -> PHAssetCollection? {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", albumTitle)
        return PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: options).firstObject
    }

    /// Photos and videos, in the chosen order (GalleryView.oldestFirst).
    func photoOptions() -> PHFetchOptions {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: prefs.oldestFirst)]
        options.predicate = NSPredicate(format: "mediaType = %d || mediaType = %d", PHAssetMediaType.image.rawValue,
                                        PHAssetMediaType.video.rawValue)
        return options
    }

    func reload() {
        guard access == .granted else { return }
        let options = photoOptions()
        let album = Self.album()
        hasAlbum = album != nil
        let result: PHFetchResult<PHAsset>
        if source == .camera, let album {
            result = PHAsset.fetchAssets(in: album, options: options)
        } else if source == .camera {
            result = PHFetchResult<PHAsset>()
        } else {
            result = PHAsset.fetchAssets(with: options)
        }
        assets = Self.list(result)
        version += 1
        learnKinds(of: Self.list(PHAsset.fetchAssets(with: options)))
    }

    nonisolated static func list(_ result: PHFetchResult<PHAsset>) -> [PHAsset] {
        var list: [PHAsset] = []
        list.reserveCapacity(result.count)
        result.enumerateObjects { a, _, _ in list.append(a) }
        return list
    }

    // ---------------------------------------------------------------- what the «Фото» tab shows

    /// The photos the grid and its viewer show: the source's, through the filter sheet (format, favourites, years).
    var shown: [PHAsset] { filtered(assets) }

    func filtered(_ list: [PHAsset]) -> [PHAsset] {
        let p = prefs
        if !p.filtering { return list }
        let cal = Calendar.current
        return list.filter { a in
            if p.favouritesOnly && !a.isFavorite { return false }
            if !p.years.isEmpty {
                guard let d = a.creationDate, p.years.contains(cal.component(.year, from: d)) else { return false }
            }
            if p.type != .all {
                guard let k = kinds[a.localIdentifier] else { return false }
                switch p.type {
                case .raw: return k.raw
                case .jpeg: return k.format == .jpeg
                case .heic: return k.format == .heic
                case .all: return true
                }
            }
            return true
        }
    }

    /// The years of the library's photos, newest first (the filter's «Время», the search's quick chips).
    var years: [Int] {
        let cal = Calendar.current
        return Array(Set(assets.compactMap { $0.creationDate.map { cal.component(.year, from: $0) } })).sorted(by: >)
    }

    /// TimelineSections: the photos by month (in the list's order), «Ноябрь 2026».
    static func sections(_ list: [PHAsset]) -> [(title: String, items: [PHAsset])] {
        var out: [(title: String, items: [PHAsset])] = []
        let cal = Calendar.current
        for a in list {
            let d = a.creationDate ?? Date.distantPast
            let c = cal.dateComponents([.year, .month], from: d)
            let title = "\(Self.monthName((c.month ?? 1) - 1)) \(c.year ?? 0)"
            if out.last?.title == title { out[out.count - 1].items.append(a) } else { out.append((title: title, items: [a])) }
        }
        return out
    }

    nonisolated static func monthName(_ m: Int) -> String {
        switch m {
        case 0: return L("gallery.month.1")
        case 1: return L("gallery.month.2")
        case 2: return L("gallery.month.3")
        case 3: return L("gallery.month.4")
        case 4: return L("gallery.month.5")
        case 5: return L("gallery.month.6")
        case 6: return L("gallery.month.7")
        case 7: return L("gallery.month.8")
        case 8: return L("gallery.month.9")
        case 9: return L("gallery.month.10")
        case 10: return L("gallery.month.11")
        default: return L("gallery.month.12")
        }
    }

    // ---------------------------------------------------------------- file names and formats

    /// A photo's file as the gallery shows it (GalleryFormat): its name, its picture's format, whether a RAW comes with it.
    struct AssetKind: Sendable {
        enum Format: Sendable { case jpeg, heic, raw, other }
        let fileName: String
        let format: Format
        let raw: Bool
    }

    nonisolated static func kind(of asset: PHAsset) -> AssetKind {
        if asset.mediaType == .video {
            return AssetKind(fileName: videoResource(asset)?.originalFilename ?? "", format: .other, raw: false)
        }
        let res = resources(asset)
        let r = res.photo ?? res.raw
        let name = r?.originalFilename ?? ""
        var format = AssetKind.Format.other
        if let r, let t = UTType(r.uniformTypeIdentifier) {
            if t.conforms(to: .jpeg) { format = .jpeg } else if t.conforms(to: .heic) || t.conforms(to: .heif) { format = .heic }
            else if t.conforms(to: .rawImage) { format = .raw }
        }
        if format == .other, name.lowercased().hasSuffix(".dng") { format = .raw }
        return AssetKind(fileName: name, format: format, raw: res.raw != nil)
    }

    /// The kinds of photos not known yet, read in the background (PHAssetResource lists), published in batches.
    private func learnKinds(of list: [PHAsset]) {
        let missing = list.filter { kinds[$0.localIdentifier] == nil }
        if missing.isEmpty { return }
        kindTask?.cancel()
        kindTask = Task.detached(priority: .utility) { [weak self] in
            var batch: [String: AssetKind] = [:]
            for (i, a) in missing.enumerated() {
                if Task.isCancelled { return }
                batch[a.localIdentifier] = GalleryLibrary.kind(of: a)
                if batch.count >= 200 || i == missing.count - 1 {
                    let done = batch
                    batch = [:]
                    await MainActor.run { self?.kinds.merge(done) { _, new in new } }
                }
            }
        }
    }

    // ---------------------------------------------------------------- albums

    /// An album of the albums page: its title, an icon, its photos (newest first in the chosen order).
    struct Album: Identifiable {
        let id: String
        let title: String
        let icon: String
        let assets: [PHAsset]
        var count: Int { assets.count }
    }

    /// AlbumsPage's lists: «Часто используемые» (Все, Камера, Избранное, RAW, Скриншоты: those that have photos), «Другие альбомы»
    /// (every other album of the library, the latest photo first), «Тип» (RAW, HEIC, JPEG).
    func albums() -> (frequent: [Album], others: [Album], types: [Album]) {
        guard access == .granted else { return ([], [], []) }
        let options = photoOptions()
        let all = Self.list(PHAsset.fetchAssets(with: options))
        var frequent: [Album] = []
        func add(_ list: inout [Album], _ id: String, _ title: String, _ icon: String, _ items: [PHAsset]) {
            if !items.isEmpty { list.append(Album(id: id, title: title, icon: icon, assets: items)) }
        }
        add(&frequent, "all", L("gallery.album.all"), "kb_ui_ic_photos_rest", all)
        if let camera = Self.album() {
            add(&frequent, "camera", L("gallery.camera"), "kb_ui_ic_camera_rest", Self.list(PHAsset.fetchAssets(in: camera, options: options)))
        }
        add(&frequent, "favourites", L("gallery.album.favourites"), "ui_ic_heart", all.filter(\.isFavorite))
        add(&frequent, "raw", "RAW", "kb_ui_ic_raw_rest", all.filter { kinds[$0.localIdentifier]?.raw ?? false })
        add(&frequent, "screenshots", L("gallery.album.screenshots"), "kb_ui_ic_screenshot_rest",
            all.filter { $0.mediaSubtypes.contains(.photoScreenshot) })
        add(&frequent, "videos", L("gallery.album.videos"), "kb_ui_ic_video_rest", all.filter { $0.mediaType == .video })
        var others: [Album] = []
        let user = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: nil)
        user.enumerateObjects { c, _, _ in
            if c.localizedTitle == Self.albumTitle { return }
            let items = Self.list(PHAsset.fetchAssets(in: c, options: options))
            if !items.isEmpty { others.append(Album(id: c.localIdentifier, title: c.localizedTitle ?? "", icon: "kb_ui_ic_folder_rest", assets: items)) }
        }
        func latest(_ a: Album) -> Date { a.assets.compactMap(\.creationDate).max() ?? .distantPast }
        others = others.map { ($0, latest($0)) }.sorted { $0.1 > $1.1 }.map(\.0)
        var types: [Album] = []
        add(&types, "type.raw", "RAW", "kb_ui_ic_raw_rest", all.filter { kinds[$0.localIdentifier]?.raw ?? false })
        add(&types, "type.heic", "HEIC", "kb_ui_ic_format_rest", all.filter { kinds[$0.localIdentifier]?.format == .heic })
        add(&types, "type.jpeg", "JPEG", "kb_ui_ic_format_rest", all.filter { kinds[$0.localIdentifier]?.format == .jpeg })
        return (frequent, others, types)
    }

    /// The albums a photo is in (the details sheet's «Папка»).
    nonisolated static func albumTitles(of asset: PHAsset) -> [String] {
        var out: [String] = []
        PHAssetCollection.fetchAssetCollectionsContaining(asset, with: .album, options: nil).enumerateObjects { c, _, _ in
            if let t = c.localizedTitle, !t.isEmpty { out.append(t) }
        }
        return out
    }

    // ---------------------------------------------------------------- pictures

    func thumbnail(_ asset: PHAsset, side: CGFloat) async -> UIImage? {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true
        let size = CGSize(width: side, height: side)
        return await withCheckedContinuation { cont in
            imageManager.requestImage(for: asset, targetSize: size, contentMode: .aspectFill, options: options) { image, _ in
                cont.resume(returning: image)
            }
        }
    }

    /// The viewer's picture (screen size, high quality).
    func display(_ asset: PHAsset, side: CGFloat) async -> UIImage? {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        let size = CGSize(width: side, height: side)
        return await withCheckedContinuation { cont in
            PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: .aspectFit, options: options) { image, _ in
                cont.resume(returning: image)
            }
        }
    }

    // ---------------------------------------------------------------- files

    /// The resources of an asset: the picture shown (.photo / .fullSizePhoto) and a RAW (.alternatePhoto or a DNG .photo).
    nonisolated static func resources(_ asset: PHAsset) -> (photo: PHAssetResource?, raw: PHAssetResource?) {
        let all = PHAssetResource.assetResources(for: asset)
        func isRaw(_ r: PHAssetResource) -> Bool {
            guard let t = UTType(r.uniformTypeIdentifier) else { return r.originalFilename.lowercased().hasSuffix(".dng") }
            return t.conforms(to: .rawImage) || r.originalFilename.lowercased().hasSuffix(".dng")
        }
        let raw = all.first { ($0.type == .alternatePhoto || $0.type == .photo) && isRaw($0) }
        let photo = all.first { $0.type == .fullSizePhoto } ?? all.first { $0.type == .photo && !isRaw($0) }
        return (photo, raw)
    }

    /// A video's file (the edited version first, else the original)
    nonisolated static func videoResource(_ asset: PHAsset) -> PHAssetResource? {
        let all = PHAssetResource.assetResources(for: asset)
        return all.first { $0.type == .fullSizeVideo } ?? all.first { $0.type == .video }
    }

    /// A video's file written to the temporary folder (not read into memory), nil when it could not be read
    nonisolated static func exportVideo(_ asset: PHAsset) async -> URL? {
        guard let r = videoResource(asset) else { return nil }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("share/\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(r.originalFilename.isEmpty ? "SCAMERA.mov" : r.originalFilename)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { (c: CheckedContinuation<URL?, Never>) in
            PHAssetResourceManager.default().writeData(for: r, toFile: url, options: options) { error in
                c.resume(returning: error == nil ? url : nil)
            }
        }
    }

    nonisolated static func data(of resource: PHAssetResource) async throws -> Data {
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        return try await withCheckedThrowingContinuation { cont in
            var data = Data()
            PHAssetResourceManager.default().requestData(for: resource, options: options, dataReceivedHandler: { chunk in
                data.append(chunk)
            }, completionHandler: { error in
                if let error { cont.resume(throwing: error) } else { cont.resume(returning: data) }
            })
        }
    }

    /// The photos' files in the temporary folder for the share sheet (the picture, else the RAW), in order; the ones read.
    nonisolated static func shareFiles(_ assets: [PHAsset]) async -> [URL] {
        var urls: [URL] = []
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for (i, a) in assets.enumerated() {
            if a.mediaType == .video {
                if let url = await exportVideo(a) { urls.append(url) }
                continue
            }
            let res = resources(a)
            guard let r = res.photo ?? res.raw, let data = try? await data(of: r) else { continue }
            let name = r.originalFilename.isEmpty ? "SCAMERA_\(i).jpg" : r.originalFilename
            let sub = folder.appendingPathComponent("\(i)", isDirectory: true)
            try? FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true)
            let url = sub.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: url)
            if (try? data.write(to: url)) != nil { urls.append(url) }
        }
        return urls
    }

    func delete(_ assets: [PHAsset]) async -> Bool {
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.deleteAssets(assets as NSArray)
            }
            reload()
            return true
        } catch {
            return false
        }
    }

    func toggleFavourite(_ asset: PHAsset) async {
        await setFavourite([asset], !asset.isFavorite)
    }

    /// SelectionBar's «Избранное»: the hearts of `assets` on (or off).
    func setFavourite(_ assets: [PHAsset], _ on: Bool) async {
        try? await PHPhotoLibrary.shared().performChanges {
            for a in assets { PHAssetChangeRequest(for: a).isFavorite = on }
        }
        reload()
    }

    /// EditExporter.save: the copy as a NEW photo (the original is never written) in the «SCAMERA» album; «IMG_1_edit.jpg».
    nonisolated static func saveCopy(_ jpeg: Data, name: String) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = copyName(name)
            options.uniformTypeIdentifier = UTType.jpeg.identifier
            request.addResource(with: .photo, data: jpeg, options: options)
            if let placeholder = request.placeholderForCreatedAsset {
                if let album = album() {
                    PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
                } else {
                    PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: albumTitle).addAssets([placeholder] as NSArray)
                }
            }
        }
    }

    nonisolated static func copyName(_ original: String) -> String {
        var base = original.isEmpty ? "SCAMERA" : original
        if let dot = base.lastIndex(of: "."), dot > base.startIndex { base = String(base[..<dot]) }
        return base + "_edit.jpg"
    }

    /// PhotoKit's change notices (the library changed: a photo saved, deleted elsewhere).
    final class Observer: NSObject, PHPhotoLibraryChangeObserver {
        let changed: @Sendable () -> Void
        init(_ changed: @escaping @Sendable () -> Void) { self.changed = changed }
        func photoLibraryDidChange(_ changeInstance: PHChange) { changed() }
    }
}

/// GalleryView (Android gallery/model): what the gallery shows - the order (newest or oldest first), the grid's column count, the
/// filter sheet's format, favourites only and years - kept in the app's own defaults («gallery_ui»).
struct GalleryPrefs: Equatable {
    enum TypeFilter: Int, CaseIterable { case all, raw, jpeg, heic }

    static let minColumns = 2, maxColumns = 6, defaultColumns = 3
    private static let key = "gallery_ui"

    var oldestFirst = false
    var columns = GalleryPrefs.defaultColumns
    var type = TypeFilter.all
    var favouritesOnly = false
    var years: Set<Int> = []

    /// Whether the filter sheet narrows the list (its chip shows; «Сбросить» clears it).
    var filtering: Bool { type != .all || favouritesOnly || !years.isEmpty }

    mutating func clearFilter() {
        type = .all
        favouritesOnly = false
        years = []
    }

    static func load() -> GalleryPrefs {
        var p = GalleryPrefs()
        guard let d = UserDefaults.standard.dictionary(forKey: key) else { return p }
        p.oldestFirst = d["oldest_first"] as? Bool ?? false
        p.columns = Swift.max(minColumns, Swift.min(maxColumns, d["grid_columns"] as? Int ?? defaultColumns))
        p.type = TypeFilter(rawValue: d["type_filter"] as? Int ?? 0) ?? .all
        p.favouritesOnly = d["filter_favourites"] as? Bool ?? false
        p.years = Set(d["filter_years"] as? [Int] ?? [])
        return p
    }

    func save() {
        var d = UserDefaults.standard.dictionary(forKey: Self.key) ?? [:]
        d["oldest_first"] = oldestFirst
        d["grid_columns"] = columns
        d["type_filter"] = type.rawValue
        d["filter_favourites"] = favouritesOnly
        d["filter_years"] = Array(years)
        UserDefaults.standard.set(d, forKey: Self.key)
    }

    /// The «Фото» tab's folder (Камера / Все), remembered.
    static var source: GalleryLibrary.Source {
        get { GalleryLibrary.Source(rawValue: UserDefaults.standard.dictionary(forKey: key)?["source"] as? Int ?? 0) ?? .camera }
        set {
            var d = UserDefaults.standard.dictionary(forKey: key) ?? [:]
            d["source"] = newValue.rawValue
            UserDefaults.standard.set(d, forKey: key)
        }
    }

    /// SearchPage's recent queries (at most 12, newest first).
    static var recentSearches: [String] {
        get { UserDefaults.standard.dictionary(forKey: key)?["search_recent"] as? [String] ?? [] }
        set {
            var d = UserDefaults.standard.dictionary(forKey: key) ?? [:]
            d["search_recent"] = Array(newValue.prefix(12))
            UserDefaults.standard.set(d, forKey: key)
        }
    }

    /// AlbumsPage: the sections folded by the user.
    static var collapsed: Set<String> {
        get { Set(UserDefaults.standard.dictionary(forKey: key)?["albums_collapsed"] as? [String] ?? []) }
        set {
            var d = UserDefaults.standard.dictionary(forKey: key) ?? [:]
            d["albums_collapsed"] = Array(newValue)
            UserDefaults.standard.set(d, forKey: key)
        }
    }
}

/// GallerySearch (P502): the gallery's search without an on-device recogniser - every word of the query must appear in what is
/// known of a photo: its file name, its kind (raw / dng, heic, jpeg / jpg, скриншот / screenshot, избранное / favourite, фото /
/// photo) and its date (the year, the month in ru / en, «сегодня» / «вчера», the day «31»). Case and «ё» do not matter. The text
/// read on the photos (Android's OcrIndex, P504) is not ported.
enum GallerySearch {
    private static let ruMonths = ["январь января", "февраль февраля", "март марта", "апрель апреля", "май мая", "июнь июня", "июль июля",
                                   "август августа", "сентябрь сентября", "октябрь октября", "ноябрь ноября", "декабрь декабря"]
    private static let enMonths = ["january jan", "february feb", "march mar", "april apr", "may", "june jun", "july jul", "august aug",
                                   "september sep sept", "october oct", "november nov", "december dec"]

    /// The words of a query: lower case, «ё» as «е», split on spaces, commas and semicolons.
    static func words(_ query: String) -> [String] {
        normalise(query).split(whereSeparator: { $0 == " " || $0 == "," || $0 == ";" || $0.isWhitespace }).map(String.init)
    }

    static func normalise(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: "ё", with: "е")
    }

    /// What is known of a photo as searchable text.
    static func haystack(_ asset: PHAsset, kind: GalleryLibrary.AssetKind?, now: Date) -> String {
        var b = (kind?.fileName ?? "") + " "
        if asset.mediaSubtypes.contains(.photoScreenshot) { b += "скриншот скриншоты screenshot " }
        if let k = kind {
            if k.raw || k.format == .raw { b += "raw dng рав " }
            if k.format == .heic { b += "heic heif " }
            if k.format == .jpeg { b += "jpeg jpg " }
        }
        b += asset.mediaType == .video ? "видео video ролик " : "фото photo снимок "
        if asset.isFavorite { b += "избранное избранные favourite favorite " }
        if let d = asset.creationDate {
            let cal = Calendar.current
            let m = cal.component(.month, from: d) - 1
            b += "\(cal.component(.year, from: d)) \(ruMonths[m]) \(enMonths[m]) \(cal.component(.day, from: d)) "
            if cal.isDate(d, inSameDayAs: now) { b += "сегодня today " }
            if let y = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(d, inSameDayAs: y) { b += "вчера yesterday " }
        }
        return normalise(b)
    }

    /// The photos of `all` whose text has every word of `query` (all of them for an empty query), in their order.
    static func find(_ all: [PHAsset], _ query: String, kinds: [String: GalleryLibrary.AssetKind], now: Date) -> [PHAsset] {
        let w = words(query)
        if w.isEmpty { return all }
        return all.filter { a in
            let text = haystack(a, kind: kinds[a.localIdentifier], now: now)
            return w.allSatisfy { text.contains($0) }
        }
    }
}
