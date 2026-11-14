import Foundation
import UniformTypeIdentifiers

/// FileNamePattern (processing/FileNamePattern.java, P119): the file name of a new photo from the template «Название фото»
/// (pref_file_name_pattern). The date / time variables are replaced, everything else is kept as it is; characters a file name
/// cannot hold are dropped; an empty result is the default SCA_yyyyMMdd_HHmmss.
enum FileNamePattern {
    static let key = "pref_file_name_pattern"
    static let defaultPattern = "SCA_yyyyMMdd_HHmmss"
    /// Variables, longest first where one starts another (yyyy before yy)
    static let variables = ["yyyy", "yy", "MM", "dd", "HH", "mm", "ss", "SSS"]
    static let presets = [defaultPattern, "SCA_yyyyMMdd_HHmmss_SSS", "IMG_yyyyMMdd_HHmmss", "yyyy-MM-dd_HH-mm-ss"]
    static let maxLength = 96

    /// The stored template (StoredPrefs: any thread)
    static var stored: String { StoredPrefs.string(key, defaultPattern) }

    /// The name (no extension) of a photo taken at `date` with `pattern`
    static func format(_ pattern: String, date: Date) -> String {
        let p = Array(clean(pattern))
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second, .nanosecond], from: date)
        var out = ""
        var i = 0
        while i < p.count {
            guard let token = variables.first(where: { v in i + v.count <= p.count && String(p[i..<(i + v.count)]) == v }) else {
                out.append(p[i])
                i += 1
                continue
            }
            switch token {
            case "yyyy": out += String(format: "%04d", c.year ?? 0)
            case "yy": out += String(format: "%02d", (c.year ?? 0) % 100)
            case "MM": out += String(format: "%02d", c.month ?? 0)
            case "dd": out += String(format: "%02d", c.day ?? 0)
            case "HH": out += String(format: "%02d", c.hour ?? 0)
            case "mm": out += String(format: "%02d", c.minute ?? 0)
            case "ss": out += String(format: "%02d", c.second ?? 0)
            default: out += String(format: "%03d", (c.nanosecond ?? 0) / 1_000_000)
            }
            i += token.count
        }
        return out
    }

    /// The template as it is used: no characters a file name cannot hold, no dots at the ends, at most 96 characters
    static func clean(_ pattern: String?) -> String {
        guard let pattern else { return defaultPattern }
        var s = String(pattern.unicodeScalars.filter { $0.value >= 0x20 && !"/\\:*?\"<>|".unicodeScalars.contains($0) }.map(Character.init))
        s = s.trimmingCharacters(in: .whitespaces)
        while s.hasPrefix(".") { s.removeFirst() }
        while s.hasSuffix(".") { s.removeLast() }
        s = s.trimmingCharacters(in: .whitespaces)
        if s.count > maxLength { s = String(s.prefix(maxLength)) }
        return s.isEmpty ? defaultPattern : s
    }
}

/// The original file name a saved photo gets in the library (PHAssetResourceCreationOptions.originalFilename): the template's name
/// at `date` and the extension of the file's type (a JPEG «.jpg», HEIC «.heic», a DNG «.dng»).
enum PhotoFileName {
    static func make(date: Date = Date(), uti: String) -> String {
        FileNamePattern.format(FileNamePattern.stored, date: date) + "." + ext(uti)
    }

    static func ext(_ uti: String) -> String {
        if uti == UTType.jpeg.identifier { return "jpg" }
        if uti == "com.adobe.raw-image" { return "dng" }
        return UTType(uti)?.preferredFilenameExtension ?? "jpg"
    }
}
