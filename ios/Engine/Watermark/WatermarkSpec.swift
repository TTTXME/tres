import Foundation

/// The watermark's settings as one value (Android processing/watermark/WatermarkSpec.java, P628): its layout, where it sits, which
/// fields it shows (each on its own), the custom text, the font, size, colour, opacity, margin and the camera glyph. Read from the
/// stored settings (`from`) by the shot and by the settings page's preview, so both draw the same thing. The keys and defaults are
/// the Android app's (res/xml/preferences.xml), stored by SettingsStore in the Android forms (lists and sliders as strings).
struct WatermarkSpec: Equatable {
    static let keyOn = "pref_show_watermark_key"
    static let keyLayout = "pref_watermark_layout", keyPosition = "pref_watermark_position",
               keyShowText = "pref_watermark_show_text", keyText = "pref_watermark_text", keyShowDevice = "pref_watermark_show_device",
               keyShowLens = "pref_watermark_show_lens", keyShowAperture = "pref_watermark_show_aperture",
               keyShowShutter = "pref_watermark_show_shutter", keyShowIso = "pref_watermark_show_iso",
               keyShowTime = "pref_watermark_show_time", keyDateFormat = "pref_watermark_date_format", keyLogo = "pref_watermark_logo",
               keyFont = "pref_watermark_font", keySize = "pref_watermark_size", keyColor = "pref_watermark_color",
               keyOpacity = "pref_watermark_opacity", keyMargin = "pref_watermark_margin"

    /// Every key whose change redraws the settings page's preview.
    static let keys = [keyOn, keyLayout, keyPosition, keyShowText, keyText, keyShowDevice, keyShowLens, keyShowAperture, keyShowShutter,
                       keyShowIso, keyShowTime, keyDateFormat, keyLogo, keyFont, keySize, keyColor, keyOpacity, keyMargin]

    /// R.bool.pref_show_watermark_default (res/values/default_prefs.xml).
    static let defaultOn = true

    /// «Подпись» (text and glyph, no plate), «Плашка» (a glass pill), «Полоса» (a band across the edge), «Рамка».
    enum Layout: String, CaseIterable { case text, badge, bar, frame }

    /// The corner or edge the watermark sits on, as the photo is viewed (upright). Raw values: the stored codes.
    enum Position: String, CaseIterable {
        case bottomLeft = "bl", bottomCenter = "bc", bottomRight = "br", topLeft = "tl", topCenter = "tc", topRight = "tr"

        var top: Bool { self == .topLeft || self == .topCenter || self == .topRight }
        var right: Bool { self == .bottomRight || self == .topRight }
        var center: Bool { self == .bottomCenter || self == .topCenter }
    }

    /// The app's font, a condensed sans, a serif, a monospace (WatermarkFonts maps them to the iOS system families).
    enum Font: String, CaseIterable { case scam, condensed, serif, mono }

    /// The text colour: chosen by the photo under the watermark, white, black (a plate / frame takes the opposite).
    enum Colour: String, CaseIterable { case auto, white, black }

    /// Date and time, the date, the time, ISO 8601, the month as a word.
    enum DateFormat: String, CaseIterable { case dateTime = "datetime", date, time, iso, text }

    /// The default custom text: the app's name.
    static let defaultText = "SCAMERA"
    /// The longest custom text drawn (UTF-16 units, as Java counts); the rest is cut, then the line is shortened to fit its width.
    static let maxText = 64
    static let defaultSize: Float = 7, defaultOpacity: Float = 100, defaultMargin: Float = 3
    /// The settings' ranges (preferences.xml: size 3..20, opacity 10..100, margin 0..10).
    static let minSize: Float = 3, maxSize: Float = 20, minOpacity: Float = 10, maxMargin: Float = 10
    /// The title's height per size step: the default 7 gives 2.8 % of the short side.
    static let titleShare: Float = 0.4

    var layout = Layout.text
    var position = Position.bottomLeft
    var showText = true, showDevice = false, showLens = false, showAperture = false, showShutter = false, showIso = false,
        showTime = false, logo = true
    var text = WatermarkSpec.defaultText
    var dateFormat = DateFormat.dateTime
    var font = Font.scam
    var colour = Colour.auto
    /// The title's text height as a share of the photo's short side: size / 100 * titleShare.
    var size: Float = WatermarkSpec.defaultSize
    /// 0..1.
    var opacity: Float = WatermarkSpec.defaultOpacity / 100
    /// Percent of the short side between the watermark and the photo's edges (the frame's border).
    var margin: Float = WatermarkSpec.defaultMargin

    init() {}

    /// Only the fields named on (everything else off), for tests and the preview of a subset.
    func only(text: Bool, device: Bool, lens: Bool, aperture: Bool, shutter: Bool, iso: Bool, time: Bool, logo: Bool) -> WatermarkSpec {
        var s = self
        s.showText = text; s.showDevice = device; s.showLens = lens; s.showAperture = aperture; s.showShutter = shutter
        s.showIso = iso; s.showTime = time; s.logo = logo
        return s
    }

    /// The stored settings (`values` as SettingsStore keeps them; a missing key: its default). Unknown values fall back to the
    /// defaults; the sliders as SettingsNumericRules.value reads them (size clamped to 3..20, opacity 10..100 and margin 0..10
    /// clamped and rounded half-even, as Math.rint).
    static func from(_ values: [String: Any]) -> WatermarkSpec {
        var s = WatermarkSpec()
        s.layout = Layout(rawValue: lower(str(values, keyLayout, "text"))) ?? .text
        s.position = Position(rawValue: lower(str(values, keyPosition, "bl"))) ?? .bottomLeft
        s.showText = bool(values, keyShowText, true)
        s.text = str(values, keyText, defaultText)
        s.showDevice = bool(values, keyShowDevice, false)
        s.showLens = bool(values, keyShowLens, false)
        s.showAperture = bool(values, keyShowAperture, false)
        s.showShutter = bool(values, keyShowShutter, false)
        s.showIso = bool(values, keyShowIso, false)
        s.showTime = bool(values, keyShowTime, false)
        s.dateFormat = DateFormat(rawValue: lower(str(values, keyDateFormat, "datetime"))) ?? .dateTime
        s.logo = bool(values, keyLogo, true)
        s.font = Font(rawValue: lower(str(values, keyFont, "scam"))) ?? .scam
        s.colour = Colour(rawValue: lower(str(values, keyColor, "auto"))) ?? .auto
        s.size = Float(rule(read(values[keySize], Double(defaultSize)), minSize, maxSize, whole: false))
        s.opacity = Float(rule(read(values[keyOpacity], Double(defaultOpacity)), minOpacity, 100, whole: true)) / 100
        s.margin = Float(rule(read(values[keyMargin], Double(defaultMargin)), 0, maxMargin, whole: true))
        return s
    }

    /// The settings as stored now.
    static func stored(_ store: SettingsStore = .shared) -> WatermarkSpec { from(store.values) }

    /// Whether the watermark switch is on (PreferenceKeys.isShowWatermarkOn: the stored value, else the default on).
    static func isOn(_ values: [String: Any]) -> Bool { bool(values, keyOn, defaultOn) }

    // ───── P633: the spec as JSON (the watermark record's and EditState.wm's form) ─────

    /// WatermarkSpec.toJson: org.json's JSONObject as Android writes it - the keys in this order, a whole number without «.0»
    /// (JSONObject.numberToString), any other the double's shortest form (the float widened, «0.8500000238418579»), strings
    /// escaped as JSONStringer does («/» as «\/», control characters as \uXXXX).
    func toJson() -> String {
        var o = "{"
        func key(_ k: String) { o += (o.count > 1 ? "," : "") + Self.quote(k) + ":" }
        key("layout"); o += Self.quote(layout.rawValue)
        key("position"); o += Self.quote(position.rawValue)
        key("showText"); o += showText ? "true" : "false"
        key("text"); o += Self.quote(text)
        key("device"); o += showDevice ? "true" : "false"
        key("lens"); o += showLens ? "true" : "false"
        key("aperture"); o += showAperture ? "true" : "false"
        key("shutter"); o += showShutter ? "true" : "false"
        key("iso"); o += showIso ? "true" : "false"
        key("time"); o += showTime ? "true" : "false"
        key("dateFormat"); o += Self.quote(dateFormat.rawValue)
        key("logo"); o += logo ? "true" : "false"
        key("font"); o += Self.quote(font.rawValue)
        key("colour"); o += Self.quote(colour.rawValue)
        key("size"); o += Self.number(Double(size))
        key("opacity"); o += Self.number(Double(opacity))
        key("margin"); o += Self.number(Double(margin))
        return o + "}"
    }

    /// WatermarkSpec.fromJson: unknown values the defaults, the numbers clamped to the settings' ranges (NaN / infinite: the
    /// default), the text cut at 4 x maxText UTF-16 units; nil for text that is not a JSON object.
    static func fromJson(_ json: String?) -> WatermarkSpec? {
        guard let json, !json.isEmpty, let data = json.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) as? [String: Any] else { return nil }
        var s = WatermarkSpec()
        s.layout = Layout(rawValue: lower(optString(o["layout"], "text"))) ?? .text
        s.position = Position(rawValue: lower(optString(o["position"], "bl"))) ?? .bottomLeft
        s.showText = optBool(o["showText"], true)
        var text = optString(o["text"], defaultText)
        if text.utf16.count > 4 * maxText { text = String(decoding: Array(text.utf16.prefix(4 * maxText)), as: UTF16.self) }
        s.text = text
        s.showDevice = optBool(o["device"], false)
        s.showLens = optBool(o["lens"], false)
        s.showAperture = optBool(o["aperture"], false)
        s.showShutter = optBool(o["shutter"], false)
        s.showIso = optBool(o["iso"], false)
        s.showTime = optBool(o["time"], false)
        s.dateFormat = DateFormat(rawValue: lower(optString(o["dateFormat"], "datetime"))) ?? .dateTime
        s.logo = optBool(o["logo"], true)
        s.font = Font(rawValue: lower(optString(o["font"], "scam"))) ?? .scam
        s.colour = Colour(rawValue: lower(optString(o["colour"], "auto"))) ?? .auto
        s.size = clamp(Float(optDouble(o["size"], Double(defaultSize))), minSize, maxSize, defaultSize)
        s.opacity = clamp(Float(optDouble(o["opacity"], Double(defaultOpacity / 100))), minOpacity / 100, 1, defaultOpacity / 100)
        s.margin = clamp(Float(optDouble(o["margin"], Double(defaultMargin))), 0, maxMargin, defaultMargin)
        return s
    }

    private static func clamp(_ v: Float, _ lo: Float, _ hi: Float, _ fallback: Float) -> Float {
        if v.isNaN || v.isInfinite { return fallback }
        return Swift.max(lo, Swift.min(hi, v))
    }

    /// JSONObject.optString: a string itself, another value as its text (a number, a Boolean), NULL / missing the fallback.
    private static func optString(_ v: Any?, _ fallback: String) -> String {
        switch v {
        case let s as String: return s
        case let n as NSNumber: return CFGetTypeID(n) == CFBooleanGetTypeID() ? (n.boolValue ? "true" : "false") : number(n.doubleValue)
        default: return fallback
        }
    }

    /// JSONObject.optBoolean: a Boolean, or «true» / «false» in any case; anything else the fallback.
    private static func optBool(_ v: Any?, _ fallback: Bool) -> Bool {
        if let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
        if let s = v as? String {
            if s.caseInsensitiveCompare("true") == .orderedSame { return true }
            if s.caseInsensitiveCompare("false") == .orderedSame { return false }
        }
        return fallback
    }

    /// JSONObject.optDouble: a number, or a string that parses as one; anything else the fallback.
    private static func optDouble(_ v: Any?, _ fallback: Double) -> Double {
        if let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { return n.doubleValue }
        if let s = v as? String, let d = Double(s.trimmingCharacters(in: .whitespaces)) { return d }
        return fallback
    }

    /// JSONObject.numberToString: a whole value as a long, else Double.toString's shortest form.
    static func number(_ d: Double) -> String {
        if d == 0, d.sign == .minus { return "-0" }
        if d.isFinite, d == d.rounded(.towardZero), abs(d) < 9.2e18 { return String(Int64(d)) }
        return "\(d)"
    }

    /// JSONStringer.string: the quoted, escaped text.
    static func quote(_ s: String) -> String {
        var o = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": o += "\\\""
            case "\\": o += "\\\\"
            case "/": o += "\\/"
            case "\t": o += "\\t"
            case "\u{08}": o += "\\b"
            case "\n": o += "\\n"
            case "\r": o += "\\r"
            case "\u{0C}": o += "\\f"
            default:
                if u.value <= 0x1F { o += String(format: "\\u%04x", u.value) } else { o.unicodeScalars.append(u) }
            }
        }
        return o + "\""
    }

    // ───── Android's preference reading (PreferenceValue / PreferenceNumber / SettingsNumericRules) ─────

    private static func str(_ values: [String: Any], _ key: String, _ fallback: String) -> String {
        switch values[key] {
        case let s as String: return s
        case let n as NSNumber: return CFGetTypeID(n) == CFBooleanGetTypeID() ? (n.boolValue ? "true" : "false") : n.stringValue
        default: return fallback
        }
    }

    /// PreferenceNumber.read: a Boolean is 1 / 0, a number itself, a string parsed («,» as «.»); anything else the fallback.
    static func read(_ value: Any?, _ fallback: Double) -> Double {
        switch value {
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? 1 : 0 }
            let d = n.doubleValue
            return d.isFinite ? d : fallback
        case let s as String:
            guard let d = Double(s.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")),
                  d.isFinite else { return fallback }
            return d
        default:
            return fallback
        }
    }

    /// PreferenceNumber.bool: a Boolean itself, «true» / «false» in any case, else a number not 0.
    static func bool(_ values: [String: Any], _ key: String, _ fallback: Bool) -> Bool {
        let value = values[key]
        if let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
        if let s = value as? String {
            if s.caseInsensitiveCompare("true") == .orderedSame { return true }
            if s.caseInsensitiveCompare("false") == .orderedSame { return false }
        }
        return read(value, fallback ? 1 : 0) != 0
    }

    private static func rule(_ v: Double, _ lo: Float, _ hi: Float, whole: Bool) -> Double {
        let c = Swift.max(Double(lo), Swift.min(Double(hi), v))
        return whole ? c.rounded(.toNearestOrEven) : c
    }

    private static func lower(_ v: String) -> String {
        v.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
