import CoreGraphics
import CoreText
import UIKit

/// The watermark's fonts on iOS (Android WatermarkFonts.java). Android draws «SCAM» in Roboto Flex, a third-party font the app
/// bundles (not its own file), and the others in the system's condensed sans, serif and monospace; here every one is the iOS system
/// family closest to it: «SCAM» SF Pro, «Узкий» SF Pro condensed, «Засечки» New York, «Моно» SF Mono, at the weight asked
/// (500 medium, 600 semibold). The measures are CoreText's (UIFont ascender / descender as Paint's FontMetrics ascent / descent,
/// the typographic width of the line with the letter spacing as kerning, as Paint.measureText with setLetterSpacing).
final class WatermarkFonts: WatermarkFontSource {
    static let shared = WatermarkFonts()

    private var cache: [String: UIFont] = [:]
    private let lock = NSLock()

    func font(_ font: WatermarkSpec.Font, weight: Int, size: Float) -> UIFont {
        let key = "\(font.rawValue)/\(weight)/\(size)"
        lock.lock()
        defer { lock.unlock() }
        if let f = cache[key] { return f }
        let s = CGFloat(size), w = Self.uiWeight(weight)
        let made: UIFont
        switch font {
        case .scam:
            made = UIFont.systemFont(ofSize: s, weight: w)
        case .condensed:
            made = UIFont.systemFont(ofSize: s, weight: w, width: .condensed)
        case .serif:
            let base = UIFont.systemFont(ofSize: s, weight: w)
            made = base.fontDescriptor.withDesign(.serif).map { UIFont(descriptor: $0, size: s) } ?? base
        case .mono:
            made = UIFont.monospacedSystemFont(ofSize: s, weight: w)
        }
        if cache.count > 64 { cache.removeAll() }
        cache[key] = made
        return made
    }

    /// Typeface.create(family, weight, false): 400 regular, 500 medium, 600 semibold, 700 bold, 800 heavy.
    static func uiWeight(_ weight: Int) -> UIFont.Weight {
        switch weight {
        case ..<450: return .regular
        case ..<550: return .medium
        case ..<650: return .semibold
        case ..<750: return .bold
        default: return .heavy
        }
    }

    func metrics(_ font: WatermarkSpec.Font, weight: Int, size: Float) -> (ascent: Float, descent: Float) {
        let f = self.font(font, weight: weight, size: size)
        return (-Float(f.ascender), -Float(f.descender))
    }

    func measure(_ text: String, font: WatermarkSpec.Font, weight: Int, size: Float, spacing: Float) -> Float {
        let line = self.line(text, font: font, weight: weight, size: size, spacing: spacing, colour: nil)
        return Float(CTLineGetTypographicBounds(line, nil, nil, nil))
    }

    /// The line as drawn: the font, the letter spacing (em × size as kerning), the colour (nil: none, for measuring).
    func line(_ text: String, font: WatermarkSpec.Font, weight: Int, size: Float, spacing: Float, colour: CGColor?) -> CTLine {
        var attributes: [NSAttributedString.Key: Any] = [.font: self.font(font, weight: weight, size: size)]
        if spacing != 0 { attributes[.kern] = NSNumber(value: Double(spacing * size)) }
        if let colour { attributes[NSAttributedString.Key(kCTForegroundColorAttributeName as String)] = colour }
        return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
    }
}
