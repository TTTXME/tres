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
