import Foundation
import ImageIO
import Photos
import UIKit
import UniformTypeIdentifiers

/// The gallery's photos (Android's SGallery over DCIM/SCAMERA): PhotoKit's «SCAMERA» album the app saves into (MediaLibrary), or
/// every photo of the library; thumbnails, favourites, deletion, the original's data for the viewer and the editor, the edited copy.
@MainActor
@Observable
final class GalleryLibrary {
    enum Source: Int { case camera, all }
    enum Access { case unknown, denied, granted }

    nonisolated static let albumTitle = "SCAMERA"

    private(set) var access: Access = .unknown
    private(set) var assets: [PHAsset] = []
    var source: Source = .camera { didSet { if oldValue != source { reload() } } }
    private(set) var hasAlbum = false
    private let imageManager = PHCachingImageManager()
    private var observer: Observer?

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
    }

    nonisolated static func album() -> PHAssetCollection? {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", albumTitle)
        return PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: options).firstObject
    }

    func reload() {
        guard access == .granted else { return }
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.predicate = NSPredicate(format: "mediaType = %d", PHAssetMediaType.image.rawValue)
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
        var list: [PHAsset] = []
        list.reserveCapacity(result.count)
        result.enumerateObjects { a, _, _ in list.append(a) }
        assets = list
    }

    /// TimelineSections: the photos by month (newest first), «Ноябрь 2026».
    var sections: [(title: String, items: [PHAsset])] {
        var out: [(title: String, items: [PHAsset])] = []
        let cal = Calendar.current
        for a in assets {
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
        let on = !asset.isFavorite
        try? await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest(for: asset).isFavorite = on
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
