import Foundation
import ImageIO
import Photos
import UniformTypeIdentifiers

/// Writes photos to the library (AVCam MediaLibrary): the processed HEIC/JPEG as .photo, the DNG as .alternatePhoto; a DNG alone
/// («Только RAW») as the .photo itself.
actor MediaLibrary {
    enum Error: Swift.Error, LocalizedError {
        case unauthorized
        case saveFailed(String)
        var errorDescription: String? {
            switch self {
            case .unauthorized: return L("error.photos_denied")
            case .saveFailed(let reason): return L("error.save") + " " + reason
            }
        }
    }

    private var isAuthorized: Bool {
        get async {
            let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
            if status == .notDetermined {
                return await PHPhotoLibrary.requestAuthorization(for: .addOnly) == .authorized
            }
            return status == .authorized || status == .limited
        }
    }

    func save(_ photo: CapturedPhoto) async throws {
        guard await isAuthorized else {
            if let raw = photo.rawFile { try? FileManager.default.removeItem(at: raw) }
            throw Error.unauthorized
        }
        let processed = photo.processed
        let processedUTI = photo.processedUTI
        let raw = photo.rawFile
        // «Только RAW»: the DNG alone is the asset's photo resource (below); with neither there is nothing to save
        guard processed != nil || raw != nil else { throw Error.saveFailed(L("capture.not_saved")) }
        // the gallery's «Камера» (Android: DCIM/SCAMERA): the shot also goes into the «SCAMERA» album when the app may read the
        // library (an album needs full access; with add-only access the photo is saved as before)
        let fullAccess = PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized
        let album = fullAccess ? GalleryLibrary.album() : nil
        // «Название фото» (PhotoFileName) and «Копия в JPEG» (PhotoEncoding.companionJPEG: a HEIC's JPEG as a photo of its own)
        let savedAt = Date()
        let companion = PhotoEncoding.companionJPEG(for: processed, uti: processedUTI)
        do {
            try await PHPhotoLibrary.shared().performChanges {
                if let companion {
                    let copy = PHAssetCreationRequest.forAsset()
                    let options = PHAssetResourceCreationOptions()
                    options.uniformTypeIdentifier = UTType.jpeg.identifier
                    options.originalFilename = PhotoFileName.make(date: savedAt, uti: UTType.jpeg.identifier)
                    copy.addResource(with: .photo, data: companion, options: options)
                    if let album, let placeholder = copy.placeholderForCreatedAsset {
                        PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
                    }
                }
                let request = PHAssetCreationRequest.forAsset()
                defer {
                    if fullAccess, let placeholder = request.placeholderForCreatedAsset {
                        if let album {
                            PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
                        } else {
                            PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: GalleryLibrary.albumTitle)
                                .addAssets([placeholder] as NSArray)
                        }
                    }
                }
                let rawOptions = PHAssetResourceCreationOptions()
                rawOptions.shouldMoveFile = true
                rawOptions.uniformTypeIdentifier = "com.adobe.raw-image"
                rawOptions.originalFilename = PhotoFileName.make(date: savedAt, uti: "com.adobe.raw-image")
                if let processed {
                    let options = PHAssetResourceCreationOptions()
                    options.uniformTypeIdentifier = processedUTI
                    options.originalFilename = PhotoFileName.make(date: savedAt, uti: processedUTI)
                    request.addResource(with: .photo, data: processed, options: options)
                    if let raw { request.addResource(with: .alternatePhoto, fileURL: raw, options: rawOptions) }
                } else if let raw {
                    request.addResource(with: .photo, fileURL: raw, options: rawOptions)
                }
            }
        } catch {
            if let raw { try? FileManager.default.removeItem(at: raw) }
            throw Error.saveFailed(error.localizedDescription)
        }
    }

    nonisolated static func thumbnail(of data: Data, size: Int = 160) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: size,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
