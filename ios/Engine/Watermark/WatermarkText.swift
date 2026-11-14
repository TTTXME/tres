import Foundation

/// What the watermark says (Android WatermarkText.java, P628): three groups built from the fields that are on, each empty when none
/// of its fields is - the title (the custom text, the phone), the exposure (focal length, aperture, shutter, ISO) and the time (in
/// the chosen format). Nothing is forced; a field without a value is left out with its separator.
struct WatermarkText: Equatable {
    /// Between the title's parts and between the groups joined on one line.
    static let join = "  ·  "
    /// Between the exposure's values («24mm   f/1.8   1/120s   ISO 100»).
    static let exifJoin = "   "

    let title: String
    let exif: String
    let time: String
    let logo: Bool

    /// The shot's facts the watermark may show (each unknown: nil / NaN / 0).
    struct Info {
        var device: String?
        /// mm: the lens's own and its 35 mm equivalent (0: unknown; the equivalent is shown when known).
        var focalLength = Double.nan
        var focal35 = 0
        var fNumber = Double.nan
        /// Seconds.
        var exposureTime = Double.nan
        var iso = 0
        /// Wall clock ms since 1970 (0: unknown) and its zone (nil: the phone's).
        var timeMs: Int64 = 0
        var zone: TimeZone?

        init() {}

        /// The settings page's sample shot: the phone it runs on, 24 mm, f/1.8, 1/120 s, ISO 100, now.
        static func sample(device: String?, now: Int64) -> Info {
            var i = Info()
            i.device = device
            i.focalLength = 6.9
            i.focal35 = 24
            i.fNumber = 1.8
            i.exposureTime = 1 / 120.0
            i.iso = 100
            i.timeMs = now
            i.zone = TimeZone.current
            return i
        }
    }

    /// The groups of `spec` for the shot `info`; `ru`: the month word in Russian (the interface language, Lang.ru).
    static func of(_ spec: WatermarkSpec, _ info: Info, ru: Bool = WatermarkText.interfaceRussian) -> WatermarkText {
        var title: [String] = []
        if spec.showText { add(&title, clean(spec.text)) }
        if spec.showDevice { add(&title, clean(info.device)) }
        var exif: [String] = []
        if spec.showLens { add(&exif, focal(info)) }
        if spec.showAperture { add(&exif, aperture(info.fNumber)) }
        if spec.showShutter { add(&exif, shutter(info.exposureTime)) }
        if spec.showIso { add(&exif, info.iso > 0 ? "ISO \(info.iso)" : "") }
        let time = spec.showTime && info.timeMs > 0 ? WatermarkText.time(info.timeMs, zone: info.zone, format: spec.dateFormat, ru: ru) : ""
        return WatermarkText(title: title.joined(separator: join), exif: exif.joined(separator: exifJoin), time: time, logo: spec.logo)
    }

    /// The interface language is Russian (the app's Localizable.strings in use).
    static var interfaceRussian: Bool { (Bundle.main.preferredLocalizations.first ?? "en").hasPrefix("ru") }

    /// Whether anything at all is drawn (a text group or the glyph).
    var any: Bool { logo || !title.isEmpty || !exif.isEmpty || !time.isEmpty }

    /// The non-empty groups in order: title, exposure, time.
    var groups: [String] {
        var out: [String] = []
        WatermarkText.add(&out, title)
        WatermarkText.add(&out, exif)
        WatermarkText.add(&out, time)
        return out
    }

    /// One column of at most two lines: the first group present is the main line, the others share the second line.
    var column: [String] {
        let g = groups
        if g.count <= 1 { return g }
        return [g[0], g[1...].joined(separator: WatermarkText.join)]
    }

    /// The two sides of a band (bar, frame): the title over the time on one side, the exposure on the other.
    var leading: [String] {
        var out: [String] = []
        WatermarkText.add(&out, title)
        WatermarkText.add(&out, time)
        if out.isEmpty && !exif.isEmpty { out.append(exif) } // a lone exposure moves to the leading side
        return out
    }

    var trailing: [String] {
        title.isEmpty && time.isEmpty || exif.isEmpty ? [] : [exif]
    }

    private static func add(_ out: inout [String], _ s: String?) {
        if let s, !s.isEmpty { out.append(s) }
    }

    // ───── Values ─────

    /// Trimmed, control characters and line breaks as spaces, runs of spaces as one, at most maxText UTF-16 units (Java's
    /// Character.isWhitespace and String.length).
    static func clean(_ s: String?) -> String {
        guard let s else { return "" }
        var b: [UInt16] = []
        b.reserveCapacity(s.utf16.count)
        var space = false
        for c in s.utf16 {
            if c < 0x20 || c == 0x7F || javaWhitespace(c) { space = !b.isEmpty; continue }
            if space { b.append(0x20) }
            space = false
            b.append(c)
        }
        if b.count > WatermarkSpec.maxText {
            var end = WatermarkSpec.maxText
            if UTF16.isLeadSurrogate(b[end - 1]) { end -= 1 }
            b = javaTrim(Array(b[0..<end]))
        }
        return String(decoding: b, as: UTF16.self)
    }

    /// java.lang.Character.isWhitespace for a UTF-16 unit: the Unicode space separators except the no-break ones, the line and
    /// paragraph separators, and U+0009..U+000D, U+001C..U+001F.
    static func javaWhitespace(_ c: UInt16) -> Bool {
        switch c {
        case 0x09...0x0D, 0x1C...0x1F, 0x20, 0x1680, 0x2000...0x2006, 0x2008...0x200A, 0x2028, 0x2029, 0x205F, 0x3000: return true
        default: return false
        }
    }

    /// String.trim: the units up to U+0020 removed from both ends.
    static func javaTrim(_ units: [UInt16]) -> [UInt16] {
        var lo = 0, hi = units.count
        while lo < hi && units[lo] <= 0x20 { lo += 1 }
        while hi > lo && units[hi - 1] <= 0x20 { hi -= 1 }
        return Array(units[lo..<hi])
    }

    /// «24mm» (the 35 mm equivalent), else the lens's own «6.9mm»; "" unknown.
    static func focal(_ info: Info) -> String {
        if info.focal35 > 0 { return "\(info.focal35)mm" }
        if !(info.focalLength > 0) { return "" }
        return number(info.focalLength) + "mm"
    }

    /// «f/1.8»; "" unknown.
    static func aperture(_ f: Double) -> String {
        if !(f > 0) || f.isInfinite { return "" }
        return "f/" + javaFixed1(f)
    }

    /// «1/120s», «0.5s», «2s», «1.5s»; "" unknown.
    static func shutter(_ t: Double) -> String {
        if !(t > 0) || t.isInfinite { return "" }
        if t < 0.3 { return "1/\(Swift.max(1, javaRound(1 / t)))s" }
        return number(t) + "s"
    }

    /// One decimal, a whole number without it.
    static func number(_ v: Double) -> String {
        let r = Double(javaRound(v * 10)) / 10.0
        return r == r.rounded(.toNearestOrEven) ? String(Int64(r)) : javaFixed1(r)
    }

    /// Math.round(double): floor(x + 0.5).
    static func javaRound(_ x: Double) -> Int64 {
        guard x.isFinite else { return 0 }
        return Int64((x + 0.5).rounded(.down))
    }

    /// String.format(Locale.US, "%.1f", v): Java rounds the shortest decimal form of the double half up (C's printf would round
    /// the binary value: 0.15 is «0.2» in Java, «0.1» in C).
    static func javaFixed1(_ v: Double) -> String {
        guard v.isFinite else { return String(v) }
        var d = Decimal(string: "\(v)", locale: Locale(identifier: "en_US_POSIX")) ?? Decimal(v)
        var r = Decimal()
        NSDecimalRound(&r, &d, 1, .plain)
        var s = NSDecimalNumber(decimal: r).description(withLocale: Locale(identifier: "en_US_POSIX"))
        if !s.contains(".") { s += ".0" }
        return s
    }

    /// The time of `ms` in `zone` (nil: the phone's) in `format`, 24 hours; `ru`: the month word in Russian (SimpleDateFormat's
    /// patterns: Locale.US for the numbers, Locale("ru") / Locale.ENGLISH for the month).
    static func time(_ ms: Int64, zone: TimeZone?, format: WatermarkSpec.DateFormat, ru: Bool) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        switch format {
        case .date: f.dateFormat = "dd.MM.yyyy"
        case .time: f.dateFormat = "HH:mm"
        case .iso: f.dateFormat = "yyyy-MM-dd HH:mm"
        case .text:
            f.locale = Locale(identifier: ru ? "ru_RU" : "en_US")
            f.dateFormat = "d MMMM yyyy, HH:mm"
        case .dateTime: f.dateFormat = "dd.MM.yyyy  HH:mm"
        }
        f.timeZone = zone ?? TimeZone.current
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}
