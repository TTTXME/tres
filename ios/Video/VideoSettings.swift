import AVFoundation
import CoreMedia

/// The video mode's settings as the Android app has them (CaptureController.setUpMediaRecorder / resolveVideoProfile,
/// getPreferredVideoStabilizationMode, CameraMode.VIDEO). Android's only stored video choice is the size
/// (pref_video_resolution_key: "3840x2160", "1920x1080" (default), "1280x720"; a size the lens lacks falls to the next of its
/// order); the rest is the CamcorderProfile's: H.264 + AAC from the microphone in an MPEG-4 file, the profile's frame rate (30),
/// its bit rate, and the video stabilisation ON while recording when pref_eis_photo_key (default on) allows it. On the iPhone:
/// AVCaptureMovieFileOutput with the H.264 codec, the microphone's AAC, 30 fps from the lens's format of that size, the
/// connection's standard stabilisation (preview-optimised where the format has no standard one), the encoder's own bit rate
/// (the movie output takes no bit rate: the iPhone's profile), a QuickTime file (the movie output writes .mov).
enum VideoSettings {
    static let resolutionKey = "pref_video_resolution_key"
    static let defaultResolution = "1920x1080"
    static let resolutions = ["3840x2160", "1920x1080", "1280x720"]
    /// CamcorderProfile.videoFrameRate of the QUALITY_* profiles
    static let frameRate: Int32 = 30
    /// pref_camera_mode_key (CameraMode ordinals): MOTION 2 is the photo the screen shows, VIDEO 5
    static let modeKey = "pref_camera_mode_key"
    static let photoOrdinal = 2, videoOrdinal = 5

    /// The stored size (an unknown value reads as the default)
    static var resolution: String {
        let s = StoredPrefs.string(resolutionKey, defaultResolution)
        return resolutions.contains(s) ? s : defaultResolution
    }

    /// resolveVideoProfile: the chosen size first, then the order Android tries
    static func order(_ resolution: String) -> [(width: Int32, height: Int32)] {
        switch resolution {
        case "3840x2160": return [(3840, 2160), (1920, 1080), (1280, 720)]
        case "1280x720": return [(1280, 720), (1920, 1080), (3840, 2160)]
        default: return [(1920, 1080), (1280, 720), (3840, 2160)]
        }
    }

    /// «4K» / «1080p» / «720p»
    static func name(width: Int32, height: Int32) -> String {
        let short = min(width, height)
        if short >= 2160 { return "4K" }
        return "\(short)p"
    }

    static func name(_ resolution: String) -> String {
        let parts = resolution.split(separator: "x").compactMap { Int32($0) }
        guard parts.count == 2 else { return resolution }
        return name(width: parts[0], height: parts[1])
    }

    struct Pick {
        let format: AVCaptureDevice.Format
        let width: Int32
        let height: Int32
    }

    /// The lens's format that records the size at 30 fps: exactly that size, 420v / 420f, 30 fps in a range; of those the one
    /// with standard stabilisation, then not a high-frame-rate one (over 60 fps), the video range, not binned.
    static func pick(for device: AVCaptureDevice, resolution: String) -> Pick? {
        for size in order(resolution) {
            var best: AVCaptureDevice.Format?
            var bestScore = -1
            for f in device.formats {
                let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
                let sub = CMFormatDescriptionGetMediaSubType(f.formatDescription)
                guard d.width == size.width, d.height == size.height,
                      sub == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange || sub == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                      f.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= Double(frameRate) && $0.maxFrameRate >= Double(frameRate) })
                else { continue }
                let maxFps = f.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 0
                var score = 0
                if f.isVideoStabilizationModeSupported(.standard) { score += 8 }
                if maxFps <= 60 { score += 4 }
                if sub == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange { score += 2 }
                if !f.isVideoBinned { score += 1 }
                if score > bestScore { best = f; bestScore = score }
            }
            if let best { return Pick(format: best, width: size.width, height: size.height) }
        }
        return nil
    }

    /// getPreferredVideoStabilizationMode while recording: ON, else the preview's stabilisation, else off; off when
    /// pref_eis_photo_key is off
    static func stabilization(for format: AVCaptureDevice.Format) -> AVCaptureVideoStabilizationMode {
        guard StoredPrefs.bool("pref_eis_photo_key", true) else { return .off }
        if format.isVideoStabilizationModeSupported(.standard) { return .standard }
        if format.isVideoStabilizationModeSupported(.previewOptimized) { return .previewOptimized }
        return .off
    }

    static func stabilizationName(_ mode: AVCaptureVideoStabilizationMode) -> String {
        switch mode {
        case .off: return "off"
        case .standard: return "standard"
        case .cinematic: return "cinematic"
        case .cinematicExtended: return "cinematicExtended"
        case .previewOptimized: return "previewOptimized"
        case .auto: return "auto"
        default: return "\(mode.rawValue)"
        }
    }

    /// The file's name (CaptureController: VID_yyyyMMdd_HHmmss), in the temporary folder until the library takes it
    static func newFileURL(_ date: Date = Date()) -> URL {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return FileManager.default.temporaryDirectory.appendingPathComponent("VID_\(f.string(from: date)).mov")
    }

    /// updateVideoRecordingInfo: «00:12  0.02/101.3 GB» (the elapsed time, the file's size, the free space)
    static func recordingInfo(seconds: Double, bytes: Int64, available: Int64) -> String {
        let total = max(0, Int(seconds))
        let gb = 1_073_741_824.0
        return String(format: "%02ld:%02ld  %.2f/%.1f GB", total / 60, total % 60, Double(bytes) / gb, Double(max(0, available)) / gb)
    }

    /// The free space of the app's volume for its important data
    static func availableBytes() -> Int64 {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }
}
