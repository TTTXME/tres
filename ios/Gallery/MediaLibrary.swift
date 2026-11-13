import Foundation
import ImageIO
import Photos

/// Writes photos to the library (AVCam MediaLibrary): the processed HEIC/JPEG as .photo, the DNG as .alternatePhoto.
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
        // the gallery's «Камера» (Android: DCIM/SCAMERA): the shot also goes into the «SCAMERA» album when the app may read the
        // library (an album needs full access; with add-only access the photo is saved as before)
        let fullAccess = PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized
        let album = fullAccess ? GalleryLibrary.album() : nil
        do {
            try await PHPhotoLibrary.shared().performChanges {
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
                if let processed {
                    let options = PHAssetResourceCreationOptions()
                    options.uniformTypeIdentifier = processedUTI
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

    /// stopRecordingVideo + onRequestTriggerMediaScanner: the recorded video into the library (the file moved, its name kept), in
    /// the «SCAMERA» album as the photos when the app may read the library
    func saveVideo(_ url: URL) async throws {
        guard await isAuthorized else {
            try? FileManager.default.removeItem(at: url)
            throw Error.unauthorized
        }
        let fullAccess = PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized
        let album = fullAccess ? GalleryLibrary.album() : nil
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.shouldMoveFile = true
                options.originalFilename = url.lastPathComponent
                request.addResource(with: .video, fileURL: url, options: options)
                if fullAccess, let placeholder = request.placeholderForCreatedAsset {
                    if let album {
                        PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
                    } else {
                        PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: GalleryLibrary.albumTitle)
                            .addAssets([placeholder] as NSArray)
                    }
                }
            }
        } catch {
            try? FileManager.default.removeItem(at: url)
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
