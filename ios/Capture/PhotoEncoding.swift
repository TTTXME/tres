import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// «Формат фото» and its rows as the SHDR photo is written (PhotoFormat / OutputColour / Heic10Support on Android):
/// - the codec (pref_photo_format): JPEG (pref_jpeg_quality, 98) or HEIC (pref_heic_quality, 90). WebP and AVIF have no ImageIO
///   encoder on iOS (it reads them only): a WebP / AVIF choice (a config from Android) is written as JPEG;
/// - «Цветовое пространство» (pref_photo_color_space): Display P3 - the post pipeline renders the photo in P3 primaries
///   (PostPipeline.p3Output, ShotRun) and the file carries the Display P3 profile; sRGB as before;
/// - «HEIC 10 бит» (pref_heic_10bit): a 10-bit HEVC HEIC through CIContext.heif10Representation. ImageIO writes the ISO gain map of
///   «HDR-фото» only beside an 8-bit image here, so a photo with a gain map stays 8-bit (its log says so);
/// - «Копия в JPEG» (pref_photo_also_jpeg): a HEIC photo also saved as a JPEG of its own (MediaLibrary, companionJPEG).
/// Read from StoredPrefs (any thread). The defaults (JPEG 98, sRGB, 8 bits, no copy) write the files exactly as before.
enum PhotoEncoding {
    struct Format {
        var type: UTType
        var quality: Double
        var displayP3 = false
        var tenBit = false
    }

    static func quality(_ key: String, _ fallback: Int) -> Double {
        Double(Int(PrefValue.number(StoredPrefs.string(key, String(fallback)), Double(fallback)).rounded())) / 100
    }

    /// The stored codec (webp / avif: JPEG, no encoder)
    static var codec: String {
        let f = StoredPrefs.string("pref_photo_format", "jpeg").trimmingCharacters(in: .whitespaces).lowercased()
        return f == "heic" ? "heic" : "jpeg"
    }

    static var displayP3: Bool { StoredPrefs.string("pref_photo_color_space", "srgb").trimmingCharacters(in: .whitespaces).lowercased() == "p3" }

    /// The SHDR photo's format as the settings are now
    static func current() -> Format {
        if codec == "heic" {
            return Format(type: .heic, quality: quality("pref_heic_quality", 90), displayP3: displayP3,
                          tenBit: StoredPrefs.bool("pref_heic_10bit", false))
        }
        return Format(type: .jpeg, quality: quality("pref_jpeg_quality", 98), displayP3: displayP3)
    }

    /// The colour space the photo's pixels are written in
    static func colorSpace(_ f: Format) -> CGColorSpace? {
        CGColorSpace(name: f.displayP3 ? CGColorSpace.displayP3 : CGColorSpace.sRGB)
    }

    /// «HEIC 10 бит»: the image as a 10-bit HEIC with `properties` as its metadata; nil when Core Image refuses it (the caller writes
    /// the 8-bit HEIC then)
    static func heif10(_ image: CGImage, properties: [String: Any], format: Format, log: ((String) -> Void)?) -> Data? {
        guard let space = colorSpace(format) else { return nil }
        let ci = CIImage(cgImage: image, options: [.properties: properties, .colorSpace: space])
        let options = [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): format.quality]
        do {
            return try CIContext(options: [.workingColorSpace: space]).heif10Representation(of: ci, colorSpace: space, options: options)
        } catch {
            log?("HEIC 10-bit refused (\(error.localizedDescription)): 8-bit HEIC written")
            return nil
        }
    }

    /// «Копия в JPEG»: a HEIC photo's JPEG copy (its metadata kept, pref_jpeg_quality); nil when the switch is off, the photo is a
    /// JPEG already or cannot be read
    static func companionJPEG(for data: Data?, uti: String) -> Data? {
        guard let data, uti != UTType.jpeg.identifier, StoredPrefs.bool("pref_photo_also_jpeg", false),
              let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0 else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: quality("pref_jpeg_quality", 98)]
        CGImageDestinationAddImageFromSource(destination, source, 0, options as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }
}
