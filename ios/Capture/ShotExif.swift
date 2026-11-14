import AVFoundation
import Foundation
import ImageIO

/// The facts every photo of a shot records (Android ShotExif / ParseExif.setAllAttributes / syncWithParameters / describeFilm, P618
/// and P380), as ImageIO properties over the camera's own metadata: the shot's time with its milliseconds and zone, ISO as
/// PhotographicSensitivity + SensitivityType 3 + ISOSpeed, ApertureValue in APEX, the focal length to 0.01 mm, the 35 mm equivalent
/// of the field the image shows, the lens (LensMake, LensModel «<device> back camera 6.77mm f/1.78», LensSpecification), the app's
/// version (Software) and the description (ImageDescription: «SCAMERA», the frames merged, the camera, the film's recipe, the route).
struct ShotExif: Sendable {
    /// The moment the shot was pressed (ImageSaver's name time on Android)
    var time = Date()
    /// The module the shot came from (Parameters.cameraID: the slot here, «back1»)
    var cameraID: String?
    /// "back" / "front" (ShotExif.facing)
    var facing: String?
    /// Frames merged (Parameters.multiFrameCount; 0: one frame, no line)
    var frames = 0
    /// FilmSim.Settings.recipe of the film the photo was rendered with (nil / "" none)
    var filmRecipe: String?
    /// The merge route line ("\n Route=SHDR") for a merged shot
    var merged = false
    /// The lens when the camera's metadata lacks it: f-number, focal length (mm), the 35 mm equivalent of the image's field
    var fNumber = Double.nan
    var focalLength = Double.nan
    var focal35 = 0

    // MARK: ShotExif's arithmetic

    static let diagonal35mm = (36.0 * 36.0 + 24.0 * 24.0).squareRoot()

    /// "yyyy:MM:dd HH:mm:ss" in the phone's zone
    static func dateTime(_ date: Date, zone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        return f.string(from: date)
    }

    /// SubSecTime*: the milliseconds, three digits
    static func subSec(_ date: Date) -> String {
        let ms = Int64((date.timeIntervalSince1970 * 1000).rounded(.down))
        return String(format: "%03d", Int(((ms % 1000) + 1000) % 1000))
    }

    /// OffsetTime*: "+HH:MM" / "-HH:MM" of the zone at that moment
    static func offset(_ date: Date, zone: TimeZone = .current) -> String {
        let minutes = zone.secondsFromGMT(for: date) / 60
        let a = abs(minutes)
        return String(format: "%@%02d:%02d", minutes < 0 ? "-" : "+", a / 60, a % 60)
    }

    /// PhotographicSensitivity (SHORT): 65535 above it
    static func iso16(_ iso: Int) -> Int { iso <= 0 ? 0 : min(65535, iso) }

    /// ApertureValue (APEX): 2 log2(N)
    static func apex(_ fNumber: Double) -> Double { fNumber > 0 ? 2 * log2(fNumber) : .nan }

    /// FocalLengthIn35mmFilm of a lens whose image spans `horizontalFOV` degrees across an image of aspect `aspect` (long / short),
    /// cropped `zoom` times: 35 mm film's diagonal over the image's diagonal at unit focal length (0 when unknown)
    static func focal35(horizontalFOV: Double, aspect: Double, zoom: Double) -> Int {
        guard horizontalFOV > 0, horizontalFOV < 180, aspect > 0, zoom > 0 else { return 0 }
        let width = 2 * tan(horizontalFOV * .pi / 360)
        let diagonal = width * (1 + 1 / (aspect * aspect)).squareRoot()
        let eq = (diagonal35mm / diagonal * zoom).rounded()
        return eq < 1 || eq > 65535 ? 0 : Int(eq)
    }

    /// "9.12" / "23" / "1.7"
    static func trim(_ v: Double) -> String {
        var s = String(format: "%.2f", v)
        if s.contains(".") {
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
        }
        return s
    }

    /// TIFF ASCII: printable ASCII only
    static func ascii(_ s: String) -> String {
        String(s.unicodeScalars.map { $0.value >= 0x20 && $0.value < 0x7F ? Character($0) : "?" })
    }

    /// PhotoInfo.device: the make and the model, once when the model names the brand already
    static func device(make: String?, model: String?) -> String? {
        let ma = (make ?? "").trimmingCharacters(in: .whitespaces), mo = (model ?? "").trimmingCharacters(in: .whitespaces)
        if mo.isEmpty { return ma.isEmpty ? nil : ma }
        if ma.isEmpty { return mo }
        let lowMa = ma.lowercased(), lowMo = mo.lowercased()
        if lowMo == lowMa || lowMo.hasPrefix(lowMa + " ") { return mo }
        return ma + " " + mo
    }

    /// LensModel: "<device> back camera 6.77mm f/1.78"
    static func lensModel(device: String?, facing: String?, focalMm: Double, fNumber: Double) -> String {
        var b = ""
        if let device, !device.isEmpty { b += device + " " }
        b += (facing.map { $0.isEmpty ? "" : $0 + " " } ?? "") + "camera"
        if focalMm > 0, focalMm.isFinite { b += " " + trim(focalMm) + "mm" }
        if fNumber > 0, fNumber.isFinite { b += " f/" + trim(fNumber) }
        return ascii(b)
    }

    /// DngMetadata.software as Android writes it: «SCAMERA <version> (<build>) <commit's 8 characters>» (BuildIdentity.shortSha)
    static var software: String {
        let sha = BuildInfo.sha.trimmingCharacters(in: .whitespaces)
        return "SCAMERA \(BuildInfo.version) (\(BuildInfo.build)) \(sha.isEmpty ? "unknown" : String(sha.prefix(8)))"
    }

    /// Parameters.description + ParseExif.routeLine: «SCAMERA», « Frames=», « Camera=», « Film=», « Route=SHDR» lines
    var description: String {
        var b = "SCAMERA"
        if frames > 0 { b += "\n Frames=\(frames)" }
        if let cameraID, !cameraID.isEmpty { b += "\n Camera=\(cameraID)" }
        if let filmRecipe, !filmRecipe.isEmpty { b += "\n Film=\(filmRecipe)" }
        if merged { b += "\n Route=SHDR" }
        return b
    }

    // MARK: the properties

    /// `metadata` (the camera's: {Exif}, {TIFF}, ...) with this shot's facts; values the camera reported are the shot's own
    /// (ISO, exposure, f-number, focal length) and are kept, the rest comes from this record.
    func apply(to metadata: [String: Any]) -> [String: Any] {
        var properties = metadata
        var exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] ?? [:]
        var tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        let number = { (key: CFString) -> Double? in (exif[key as String] as? NSNumber)?.doubleValue }

        // the time: when it was taken, its milliseconds and zone (DateTime, -Original, -Digitized; SubSecTime*, OffsetTime*)
        let time = Self.dateTime(self.time), sub = Self.subSec(self.time), off = Self.offset(self.time)
        tiff[kCGImagePropertyTIFFDateTime as String] = time
        exif[kCGImagePropertyExifDateTimeOriginal as String] = time
        exif[kCGImagePropertyExifDateTimeDigitized as String] = time
        for key in ["SubsecTime", "SubsecTimeOriginal", "SubsecTimeDigitized"] { exif[key] = sub }
        for key in ["OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized"] { exif[key] = off }

        // ISO (P618): PhotographicSensitivity a SHORT, ISOSpeed the value, SensitivityType 3
        let iso = ((exif[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?.intValue) ?? 0
        if iso > 0 {
            exif[kCGImagePropertyExifISOSpeedRatings as String] = [Self.iso16(iso)]
            exif[kCGImagePropertyExifSensitivityType as String] = 3
            exif[kCGImagePropertyExifISOSpeed as String] = iso
        }
        // the lens: the shot's own values, else the lens's declared ones
        let fNumber = number(kCGImagePropertyExifFNumber).flatMap { $0 > 0 ? $0 : nil } ?? self.fNumber
        let focal = number(kCGImagePropertyExifFocalLength).flatMap { $0 > 0 ? $0 : nil } ?? focalLength
        if fNumber > 0 {
            exif[kCGImagePropertyExifFNumber as String] = fNumber
            exif[kCGImagePropertyExifApertureValue as String] = (Self.apex(fNumber) * 1000).rounded() / 1000
        }
        if focal > 0 { exif[kCGImagePropertyExifFocalLength as String] = (focal * 100).rounded() / 100 }
        if (exif[kCGImagePropertyExifFocalLenIn35mmFilm as String] as? NSNumber)?.intValue ?? 0 <= 0, focal35 > 0 {
            exif[kCGImagePropertyExifFocalLenIn35mmFilm as String] = focal35
        }
        // ParseExif: Make / Model as DeviceName names the phone when the camera's metadata has none
        if (tiff[kCGImagePropertyTIFFMake as String] as? String ?? "").isEmpty { tiff[kCGImagePropertyTIFFMake as String] = DeviceName.make }
        if (tiff[kCGImagePropertyTIFFModel as String] as? String ?? "").isEmpty { tiff[kCGImagePropertyTIFFModel as String] = DeviceName.model }
        let make = tiff[kCGImagePropertyTIFFMake as String] as? String
        let model = tiff[kCGImagePropertyTIFFModel as String] as? String
        if let make, !make.isEmpty { exif[kCGImagePropertyExifLensMake as String] = Self.ascii(make) }
        exif[kCGImagePropertyExifLensModel as String] = Self.lensModel(device: Self.device(make: make, model: model), facing: facing,
                                                                       focalMm: focal, fNumber: fNumber)
        // LensSpecification: one focal length and one aperture (a phone lens)
        if focal > 0, fNumber > 0 {
            let r = { (v: Double) in (v * 100).rounded() / 100 }
            exif[kCGImagePropertyExifLensSpecification as String] = [r(focal), r(focal), r(fNumber), r(fNumber)]
        }
        tiff[kCGImagePropertyTIFFSoftware as String] = Self.software
        tiff[kCGImagePropertyTIFFImageDescription as String] = description
        properties[kCGImagePropertyExifDictionary as String] = exif
        properties[kCGImagePropertyTIFFDictionary as String] = tiff
        return properties
    }
}

/// The camera's own photo (HEIC / JPEG) written with the shot's facts (AVCapturePhotoFileDataRepresentationCustomizer).
final class ShotExifCustomizer: NSObject, AVCapturePhotoFileDataRepresentationCustomizer {
    let exif: ShotExif

    init(_ exif: ShotExif) {
        self.exif = exif
    }

    func replacementMetadata(for photo: AVCapturePhoto) -> [String: Any]? {
        exif.apply(to: photo.metadata)
    }
}
