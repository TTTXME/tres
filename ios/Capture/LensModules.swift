import AVFoundation

/// A button of the module row: a physical lens, or an in-sensor zoom (ISZ) crop of one, as Android ModuleRegistry slots.
struct LensModule: Identifiable, Hashable, Sendable {
    enum Kind: String, Sendable { case lens, isz }

    let id: String
    let deviceID: String
    let isFront: Bool
    let kind: Kind
    /// Field of view relative to the back wide camera (label of the button; ModuleSettings: the user's ratio).
    var zoom: Double
    /// videoZoomFactor applied on the device (1 for a lens, the native-resolution factor for ISZ, times a copy's crop).
    let deviceZoom: Double
    let deviceName: String
    /// A copy's crop (ModuleSettings, P148: its frame cropped by it); 1 for every other module.
    var crop: Double = 1

    /// The module's own name (ModuleSettings), else its ratio
    var label: String { ModuleSettings.name(id) ?? (LensMath.label(zoom) + (isFront ? " F" : "")) }
}

enum LensMath {
    /// Equivalent zoom of a lens with horizontal field of view `fov` against the reference lens (degrees).
    static func equivalentZoom(referenceFOV: Double, fov: Double) -> Double {
        guard referenceFOV > 0, fov > 0 else { return 1 }
        let r = tan(referenceFOV * .pi / 360), f = tan(fov * .pi / 360)
        return r / f
    }

    /// "0.5×", "1×", "2×", "4.8×": whole numbers within 0.15, one decimal otherwise.
    static func label(_ zoom: Double) -> String {
        let rounded = zoom.rounded()
        if abs(zoom - rounded) < 0.15, rounded >= 1 { return "\(Int(rounded))×" }
        let tenth = (zoom * 10).rounded() / 10
        if tenth == tenth.rounded() { return "\(Int(tenth))×" }
        return String(format: "%.1f×", tenth)
    }
}

enum DeviceLookup {
    static let backTypes: [AVCaptureDevice.DeviceType] = [.builtInUltraWideCamera, .builtInWideAngleCamera, .builtInTelephotoCamera]

    /// Physical cameras only: Bayer RAW and per-lens formats are not available through the virtual multi-camera devices.
    static var backCameras: [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(deviceTypes: backTypes, mediaType: .video, position: .back).devices
    }

    static var frontCameras: [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera], mediaType: .video, position: .front).devices
    }

    /// Format with the largest still size that still previews at 30 fps; ties keep the device's current format.
    static func bestPhotoFormat(for device: AVCaptureDevice) -> AVCaptureDevice.Format {
        var best = device.activeFormat
        var bestArea = maxPhotoArea(best)
        for format in device.formats {
            let area = maxPhotoArea(format)
            let fps = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 0
            let subtype = CMFormatDescriptionGetMediaSubType(format.formatDescription)
            guard fps >= 29, subtype == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange else { continue }
            if area > bestArea { best = format; bestArea = area }
        }
        return best
    }

    static func maxPhotoArea(_ format: AVCaptureDevice.Format) -> Int64 {
        format.supportedMaxPhotoDimensions.map { Int64($0.width) * Int64($0.height) }.max() ?? 0
    }

    static func modules() -> [LensModule] {
        let back = backCameras
        let reference = back.first(where: { $0.deviceType == .builtInWideAngleCamera }) ?? back.first
        let referenceFOV = Double(reference?.activeFormat.videoFieldOfView ?? 0)
        var entries: [(zoom: Double, module: (String) -> LensModule)] = []
        for device in back {
            let zoom = LensMath.equivalentZoom(referenceFOV: referenceFOV, fov: Double(device.activeFormat.videoFieldOfView))
            let lensZoom = device == reference ? 1 : zoom
            entries.append((lensZoom, { id in
                LensModule(id: id, deviceID: device.uniqueID, isFront: false, kind: .lens, zoom: lensZoom, deviceZoom: 1,
                           deviceName: device.localizedName)
            }))
            for factor in bestPhotoFormat(for: device).secondaryNativeResolutionZoomFactors where factor > 1 {
                let iszZoom = lensZoom * Double(factor)
                entries.append((iszZoom, { id in
                    LensModule(id: id, deviceID: device.uniqueID, isFront: false, kind: .isz, zoom: iszZoom,
                               deviceZoom: Double(factor), deviceName: device.localizedName)
                }))
            }
        }
        var result: [LensModule] = []
        for (index, entry) in entries.sorted(by: { $0.zoom < $1.zoom }).enumerated() {
            result.append(entry.module("back\(index)"))
        }
        for (index, device) in frontCameras.enumerated() {
            result.append(LensModule(id: "front\(index)", deviceID: device.uniqueID, isFront: true, kind: .lens, zoom: 1,
                                     deviceZoom: 1, deviceName: device.localizedName))
        }
        return result
    }
}
