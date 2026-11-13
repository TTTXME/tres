import AVFoundation
import CoreMedia

enum CameraError: LocalizedError {
    case videoDeviceUnavailable
    case addInputFailed
    case addOutputFailed
    case setupFailed
    case moduleUnavailable
    case captureException(String)

    var errorDescription: String? {
        switch self {
        case .videoDeviceUnavailable: return L("error.no_camera")
        case .addInputFailed, .addOutputFailed, .setupFailed: return L("error.setup")
        case .moduleUnavailable: return L("error.module")
        case .captureException(let reason): return L("error.capture") + "\n" + reason
        }
    }
}

enum CameraStatus: Equatable {
    case unknown
    case unauthorized
    case failed(String)
    case running
    case interrupted
}

enum CaptureActivity: Equatable {
    case idle
    case capturing
    case saving
}

/// RAW the user asked for. Bayer is sensor data (input of the RAW merge); ProRAW is Apple's demosaiced linear RGB.
enum RawChoice: String, CaseIterable, Identifiable, Codable {
    case off, bayer, proRAW
    var id: String { rawValue }
    var title: String {
        switch self {
        case .off: return L("raw.off")
        case .bayer: return "Bayer RAW"
        case .proRAW: return "ProRAW"
        }
    }
}

struct PhotoRequest: Sendable {
    var raw: RawChoice
    var fullResolution: Bool
    /// The flash of this shot (FlashMode.Shot: on, or auto when the scene asks for it); off for the torch and off
    var flash: AVCaptureDevice.FlashMode = .off
    /// The facts the camera's own photo is written with (nil: its metadata as it is)
    var exif: ShotExif?
}

/// The flash modes of pref_ae_mode_key (Android FlashMode, P590: PhotonCamera's values 0 torch, 1 off, 2 auto flash, 3 always flash):
/// a tap goes Выкл. -> Авто -> Вкл. -> Фонарик -> Выкл.; a lens without a flash unit (the front lens: its torch is the screen's
/// light, P507) only Выкл. <-> Фонарик, Auto / On act as off there.
enum FlashMode: Int, Sendable {
    case torch = 0, off = 1, auto = 2, on = 3

    static let key = "pref_ae_mode_key"
    static let cycle: [FlashMode] = [.off, .auto, .on, .torch]

    /// A stored value outside 0..3 is off (the default "1")
    static func normalize(_ value: Int) -> FlashMode { FlashMode(rawValue: value) ?? .off }

    /// The mode in force on a lens with / without a flash unit
    static func effective(_ mode: FlashMode, flashUnit: Bool) -> FlashMode {
        (mode == .auto || mode == .on) && !flashUnit ? .off : mode
    }

    /// FlashMode.next: the next of the cycle this lens allows
    static func next(_ mode: FlashMode, flashUnit: Bool) -> FlashMode {
        let current = effective(mode, flashUnit: flashUnit)
        let at = cycle.firstIndex(of: current) ?? 0
        for k in 1...cycle.count {
            let m = cycle[(at + k) % cycle.count]
            if flashUnit || m == .off || m == .torch { return m }
        }
        return .off
    }

    /// FlashMode.flashShot: a flash shot in On, and in Auto when the scene asks for the flash (AVCapturePhotoOutput.isFlashScene)
    static func flashShot(_ mode: FlashMode, flashScene: Bool, flashUnit: Bool) -> Bool {
        guard flashUnit else { return false }
        return mode == .on || mode == .auto && flashScene
    }

    var name: String {
        switch self { case .torch: return "torch"; case .off: return "off"; case .auto: return "auto"; case .on: return "on" }
    }

    /// The top row's icon (FlashMode.icon)
    var icon: String {
        switch self { case .torch: return "cv_torch"; case .off: return "cv_bolt_off"; case .auto: return "cv_bolt_auto"; case .on: return "cv_bolt_fill" }
    }

    /// The toast and the item's description (FlashMode.label)
    var label: String {
        switch self {
        case .torch: return L("flash.torch")
        case .off: return L("flash.off")
        case .auto: return L("flash.auto")
        case .on: return L("flash.on")
        }
    }
}

struct CapturedPhoto: Sendable {
    var processed: Data?
    var processedUTI: String
    var rawFile: URL?
    var report: [String]
}

extension FourCharCode {
    var fourCC: String {
        let bytes = [UInt8((self >> 24) & 0xff), UInt8((self >> 16) & 0xff), UInt8((self >> 8) & 0xff), UInt8(self & 0xff)]
        if bytes.allSatisfy({ $0 >= 32 && $0 < 127 }) { return String(decoding: bytes, as: UTF8.self) }
        return String(format: "0x%08x", self)
    }
}

extension CMVideoDimensions {
    var text: String { "\(width)x\(height)" }
    var megapixels: Double { Double(width) * Double(height) / 1_000_000 }
}

/// The stored settings (SettingsStore's values: Android's keys under PostSettings.prefsKey, lists and sliders as strings), read
/// where SettingsStore (main actor) is out of reach: the capture actor, the viewfinder's queues.
enum StoredPrefs {
    static func string(_ key: String, _ fallback: String) -> String {
        let value = UserDefaults.standard.dictionary(forKey: PostSettings.prefsKey)?[key]
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        return fallback
    }

    static func bool(_ key: String, _ fallback: Bool) -> Bool {
        (UserDefaults.standard.dictionary(forKey: PostSettings.prefsKey)?[key] as? NSNumber)?.boolValue ?? fallback
    }
}
