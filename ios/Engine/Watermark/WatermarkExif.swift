import Foundation
import ImageIO

/// The watermark's facts of a shot from its ImageIO properties (Android PhotoWatermark.info from the shot's EXIF, WatermarkExif
/// from a file's): the phone (Make / Model, as PhotoInfo.device names it), the focal length (its 35 mm equivalent first), aperture,
/// exposure time, ISO (ISOSpeed, else PhotographicSensitivity) and the time it was taken (DateTimeOriginal in its own
/// OffsetTimeOriginal, else DateTime / OffsetTime, else the phone's zone). A fact the properties lack stays unknown.
enum WatermarkExif {
    /// The facts of `properties` (CGImageSourceCopyPropertiesAtIndex), each missing one taken from `fallback` (the DNG's).
    static func info(_ properties: [String: Any], fallback: [String: Any]? = nil) -> WatermarkText.Info {
        var i = facts(properties)
        guard let fallback else { return i }
        let f = facts(fallback)
        if i.device == nil { i.device = f.device }
        if !(i.focalLength > 0) { i.focalLength = f.focalLength }
        if i.focal35 <= 0 { i.focal35 = f.focal35 }
        if !(i.fNumber > 0) { i.fNumber = f.fNumber }
        if !(i.exposureTime > 0) { i.exposureTime = f.exposureTime }
        if i.iso <= 0 { i.iso = f.iso }
        if i.timeMs <= 0 { i.timeMs = f.timeMs; i.zone = f.zone }
        return i
    }

    /// The facts of one properties dictionary.
    static func facts(_ properties: [String: Any]) -> WatermarkText.Info {
        var i = WatermarkText.Info()
        let tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] ?? [:]
        i.device = device(make: text(tiff[kCGImagePropertyTIFFMake as String]), model: text(tiff[kCGImagePropertyTIFFModel as String]))
        i.focalLength = number(exif[kCGImagePropertyExifFocalLength as String])
        let f35 = number(exif[kCGImagePropertyExifFocalLenIn35mmFilm as String])
        i.focal35 = f35 > 0 ? Int(WatermarkText.javaRound(f35)) : 0
        i.fNumber = number(exif[kCGImagePropertyExifFNumber as String])
        i.exposureTime = number(exif[kCGImagePropertyExifExposureTime as String])
        var iso = number(exif[kCGImagePropertyExifISOSpeed as String])
        if !(iso > 0) { iso = number(exif[kCGImagePropertyExifISOSpeedRatings as String]) }
        i.iso = iso > 0 ? Int(WatermarkText.javaRound(iso)) : 0
        var date = text(exif[kCGImagePropertyExifDateTimeOriginal as String])
        var offset = text(exif[kCGImagePropertyExifOffsetTimeOriginal as String])
        if date == nil {
            date = text(tiff[kCGImagePropertyTIFFDateTime as String])
            offset = text(exif[kCGImagePropertyExifOffsetTime as String])
        }
        let zone = self.zone(offset)
        i.zone = zone
        i.timeMs = time(date, zone: zone)
        return i
    }

    /// PhotoInfo.device: «Make Model», the model alone when it already names the brand (or there is no make); nil for neither.
    static func device(make: String?, model: String?) -> String? {
        let ma = clean(make), mo = clean(model)
        if mo.isEmpty { return ma.isEmpty ? nil : ma }
        if ma.isEmpty { return mo }
        let lowMa = ma.lowercased(), lowMo = mo.lowercased()
        if lowMo == lowMa || lowMo.hasPrefix(lowMa + " ") { return mo }
        return ma + " " + mo
    }

    /// Trimmed, NUL padding and control characters removed, runs of whitespace as one space (EXIF ASCII strings may carry them).
    private static func clean(_ s: String?) -> String {
        guard let s else { return "" }
        var kept = String.UnicodeScalarView()
        kept.append(contentsOf: s.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7F })
        // with the control characters gone, Java's trim and «\s+» see only the plain space
        return String(kept).split(separator: " ").joined(separator: " ")
    }

    private static func text(_ value: Any?) -> String? {
        switch value {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }

    /// A number, a rational «1/120», a decimal string, the first of an array; NaN for none.
    static func number(_ value: Any?) -> Double {
        switch value {
        case let n as NSNumber: return n.doubleValue
        case let a as [Any]: return a.first.map { number($0) } ?? .nan
        case let s as String:
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty { return .nan }
            if let slash = t.firstIndex(of: "/") {
                guard let n = Double(t[..<slash]), let d = Double(t[t.index(after: slash)...]), d > 0 else { return .nan }
                return n / d
            }
            if let comma = t.firstIndex(of: ","), comma != t.startIndex { return number(String(t[..<comma])) } // «100,100»
            return Double(t) ?? .nan
        default:
            return .nan
        }
    }

    /// «+03:00» → that offset; nil / anything else: the phone's zone.
    static func zone(_ offset: String?) -> TimeZone {
        guard let o = offset?.trimmingCharacters(in: .whitespacesAndNewlines),
              o.range(of: #"^[+-]\d{2}:\d{2}$"#, options: .regularExpression) != nil,
              let h = Int(o.dropFirst().prefix(2)), let m = Int(o.suffix(2)) else { return TimeZone.current }
        let seconds = (h * 3600 + m * 60) * (o.hasPrefix("-") ? -1 : 1)
        return TimeZone(secondsFromGMT: seconds) ?? TimeZone.current
    }

    /// «2026:11:11 14:05:09» in `zone` → ms since 1970; 0 for none.
    static func time(_ date: String?, zone: TimeZone) -> Int64 {
        guard let d = date?.trimmingCharacters(in: .whitespacesAndNewlines), d.count >= 19 else { return 0 }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = zone
        f.isLenient = false
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        guard let parsed = f.date(from: String(d.prefix(19))) else { return 0 }
        return Int64((parsed.timeIntervalSince1970 * 1000).rounded())
    }

    /// The properties of the first image of `data` (a DNG, a HEIC); nil when ImageIO cannot read it.
    static func properties(of data: Data) -> [String: Any]? {
        guard !data.isEmpty, let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any]
    }
}
