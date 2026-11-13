import Foundation

/// ToneCurve (processing/color/ToneCurve.java), the own tone curve's points: x the normalised log exposure (0.5 + EV / 10: -5..+5 EV
/// around middle grey), y the display value; a monotone cubic (Fritsch-Carlson) between them. Stored as "x:y,x:y,..." with four
/// decimals (WhyCam's format), the ends pinned to x 0 and 1, no two points closer than MIN_GAP.
struct ToneCurve: Equatable {
    struct Point: Equatable {
        var x: Float
        var y: Float
    }

    /// WhyCam's default: a gentle S around the middle grey (the editor's «S-кривая»)
    static let defaultPoints = "0.0000:0.0000,0.2500:0.1600,0.5000:0.5000,0.7500:0.8400,1.0000:1.0000"
    /// The setting's default (pref_scam_hybrid_why_curve_points)
    static let settingPoints = "0.0000:0.0000,0.0625:0.0314,0.1250:0.0599,0.1875:0.0881,0.2500:0.1282,0.3125:0.1971,0.3750:0.2867,0.4375:0.3915,0.5000:0.5100,0.5625:0.6347,0.6250:0.7542,0.6875:0.8577,0.7500:0.9350,0.8125:0.9757,0.8750:0.9937,0.9375:1.0000,1.0000:1.0000"
    static let minGap: Float = 0.01
    static let fileHeader = "# SCAMERA tone curve: x = 0.5 + EV / 10 (-5..+5 EV around middle grey), y = display 0..1"

    private(set) var points: [Point]

    private init(points: [Point]) { self.points = points }

    static func defaults() -> ToneCurve { parse(defaultPoints) }
    static func identity() -> ToneCurve { ToneCurve(points: [Point(x: 0, y: 0), Point(x: 1, y: 1)]) }

    static func clamp01(_ v: Float) -> Float { Swift.max(0, Swift.min(1, v)) }

    /// "x:y,x:y" (clamped, sorted, the ends pinned); fewer than two points: the default.
    static func parse(_ text: String?) -> ToneCurve {
        var out: [Point] = []
        for pair in (text ?? "").split(separator: ",", omittingEmptySubsequences: false) {
            let xy = pair.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false)
            guard xy.count == 2, let x = Float(xy[0].trimmingCharacters(in: .whitespaces)),
                  let y = Float(xy[1].trimmingCharacters(in: .whitespaces)), !x.isNaN, !y.isNaN else { continue }
            out.append(Point(x: clamp01(x), y: clamp01(y)))
        }
        if out.count < 2 { return parse(defaultPoints) }
        var c = ToneCurve(points: out)
        c.normalise()
        return c
    }

    var count: Int { points.count }
    subscript(i: Int) -> Point { points[i] }

    func serialize() -> String {
        let posix = Locale(identifier: "en_US_POSIX")
        return points.map { String(format: "%.4f:%.4f", locale: posix, Double($0.x), Double($0.y)) }.joined(separator: ",")
    }

    /// A saved curve file: the header line, then the points as stored.
    func fileText() -> String { Self.fileHeader + "\n" + serialize() + "\n" }

    /// Adds a point; its index, -1 when another point is too close or (x, y) is outside the pinned ends.
    mutating func add(_ x: Float, _ y: Float) -> Int {
        let x = Self.clamp01(x), y = Self.clamp01(y)
        if points.contains(where: { abs($0.x - x) < Self.minGap }) { return -1 }
        var i = 0
        while i < points.count && points[i].x < x { i += 1 }
        if i == 0 || i == points.count { return -1 }
        points.insert(Point(x: x, y: y), at: i)
        return i
    }

    /// The ends keep their x, an inner point stays between its neighbours.
    mutating func move(_ i: Int, _ x: Float, _ y: Float) {
        guard i >= 0 && i < points.count else { return }
        points[i].y = Self.clamp01(y)
        if i == 0 { points[i].x = 0 } else if i == points.count - 1 { points[i].x = 1 } else {
            points[i].x = Swift.max(points[i - 1].x + Self.minGap, Swift.min(points[i + 1].x - Self.minGap, Self.clamp01(x)))
        }
    }

    /// Removes an inner point (the ends always stay); true when it went.
    mutating func remove(_ i: Int) -> Bool {
        guard i > 0 && i < points.count - 1 else { return false }
        points.remove(at: i)
        return true
    }

    private mutating func normalise() {
        points.sort { $0.x < $1.x }
        points[0].x = 0
        points[points.count - 1].x = 1
        var i = points.count - 2
        while i >= 1 {
            if points[i + 1].x - points[i].x < Self.minGap || points[i].x - points[i - 1].x < Self.minGap { points.remove(at: i) }
            i -= 1
        }
    }

    /// The curve at x (0..1): the monotone cubic through the points.
    func eval(_ x: Float) -> Float { eval(x, tangents()) }

    private func eval(_ x: Float, _ m: [Float]) -> Float {
        let x = Self.clamp01(x)
        var k = 0
        while k < points.count - 2 && x > points[k + 1].x { k += 1 }
        let a = points[k], b = points[k + 1]
        let h = Swift.max(b.x - a.x, 1e-6), t = (x - a.x) / h, t2 = t * t, t3 = t2 * t
        let v = (2 * t3 - 3 * t2 + 1) * a.y + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * b.y + (t3 - t2) * h * m[k + 1]
        return Self.clamp01(v)
    }

    /// n evenly spaced samples over 0..1
    func samples(_ n: Int) -> [Float] {
        let m = tangents()
        return (0..<n).map { eval(n == 1 ? 0 : Float($0) / Float(n - 1), m) }
    }

    /// Fritsch-Carlson tangents (WhyCam's generateLut)
    private func tangents() -> [Float] {
        let n = points.count
        var d = [Float](repeating: 0, count: n - 1), m = [Float](repeating: 0, count: n)
        for i in 0..<(n - 1) {
            d[i] = (points[i + 1].y - points[i].y) / Swift.max(1e-6, points[i + 1].x - points[i].x)
        }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        if n > 2 { for i in 1..<(n - 1) { m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) * 0.5 } }
        for i in 0..<(n - 1) {
            if abs(d[i]) < 1e-7 {
                m[i] = 0
                m[i + 1] = 0
                continue
            }
            let a = m[i] / d[i], b = m[i + 1] / d[i]
            if a < 0 { m[i] = 0 }
            if b < 0 { m[i + 1] = 0 }
            let s = a * a + b * b
            if s > 9 {
                let t = 3 / s.squareRoot()
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    // ───── files (ToneCurve.fromFileText)

    private static let num = "([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)"
    private static func regex(_ pattern: String) -> NSRegularExpression { try! NSRegularExpression(pattern: pattern) }
    private static let pairPattern = regex(num + "\\s*:\\s*" + num)
    private static let jsonXY = regex("\\{\\s*\"x\"\\s*:\\s*" + num + "\\s*,\\s*\"y\"\\s*:\\s*" + num + "[^}]*\\}")
    private static let jsonYX = regex("\\{\\s*\"y\"\\s*:\\s*" + num + "\\s*,\\s*\"x\"\\s*:\\s*" + num + "[^}]*\\}")
    private static let jsonPair = regex("\\[\\s*" + num + "\\s*,\\s*" + num + "\\s*\\]")
    private static let jsonName = regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private static let numberPattern = regex("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?")

    private static func groups(_ re: NSRegularExpression, _ text: String) -> [(start: Int, a: String, b: String)] {
        let ns = text as NSString
        return re.matches(in: text, range: NSRange(location: 0, length: ns.length)).map {
            ($0.range.location, ns.substring(with: $0.range(at: 1)), $0.numberOfRanges > 2 ? ns.substring(with: $0.range(at: 2)) : "")
        }
    }

    /// WhyCam's JSON curve file ({"name": .., "points": [{"x": .., "y": ..}, ...]}, a bare array, points as [x, y]); nil: no JSON
    static func jsonPoints(_ text: String) -> [Point]? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix("{") || t.hasPrefix("[") else { return nil }
        let body = t.range(of: "\"points\"").map { String(t[$0.lowerBound...]) } ?? t
        var raw: [(Int, Float, Float)] = []
        for (re, swapped) in [(jsonXY, false), (jsonYX, true), (jsonPair, false)] {
            for g in groups(re, body) {
                guard let a = Float(g.a), let b = Float(g.b) else { continue }
                raw.append(swapped ? (g.start, b, a) : (g.start, a, b))
            }
        }
        if raw.isEmpty { return nil }
        raw.sort { $0.0 < $1.0 }
        return raw.filter { $0.1.isFinite && $0.2.isFinite && $0.1 >= 0 && $0.2 >= 0 }.map { Point(x: clamp01($0.1), y: clamp01($0.2)) }
    }

    /// The curve's own name in a WhyCam JSON file
    static func jsonName(_ text: String) -> String? {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{"), let g = groups(jsonName, text).first else { return nil }
        let name = g.a.replacingOccurrences(of: "\\\"", with: "\"").replacingOccurrences(of: "\\\\", with: "\\").trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? nil : name
    }

    /// A curve from a file's text, nil with fewer than two points: WhyCam's JSON, a camera's linear curve (x0, y0, x1, y1, ...),
    /// or every "x:y" outside the '#' lines (values up to 255 scaled to 0..1, larger ones refuse the file).
    static func fromFileText(_ text: String?) -> ToneCurve? {
        guard let text else { return nil }
        if let json = jsonPoints(text) {
            if json.count < 2 { return nil }
            var c = ToneCurve(points: json)
            c.normalise()
            return c.count >= 2 ? c : nil
        }
        let body = text.components(separatedBy: .newlines).filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("#") }.joined(separator: "\n")
        if let linear = linearPairs(body) { return fromLinearCurve(linear) }
        var raw: [(Float, Float)] = []
        var maxV: Float = 0
        for g in groups(pairPattern, body) {
            guard let x = Float(g.a), let y = Float(g.b), x.isFinite, y.isFinite else { continue }
            raw.append((x, y))
            maxV = Swift.max(maxV, Swift.max(x, y))
        }
        if raw.count < 2 || maxV > 255.5 { return nil }
        let scale: Float = maxV > 1.0001 ? 1 / 255 : 1
        var c = ToneCurve(points: raw.map { Point(x: clamp01($0.0 * scale), y: clamp01($0.1 * scale)) })
        c.normalise()
        return c.count >= 2 ? c : nil
    }

    /// A bare list x0, y0, x1, y1, ... of a camera's curve (an even count of at least 4, all in 0..1, x rising from 0 to 1)
    static func linearPairs(_ text: String) -> [Float]? {
        if text.contains(":") { return nil }
        let allowed = CharacterSet(charactersIn: "-+0123456789.eE,;").union(.whitespacesAndNewlines)
        if text.unicodeScalars.contains(where: { !allowed.contains($0) }) { return nil }
        let ns = text as NSString
        var v: [Float] = []
        for m in numberPattern.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let f = Float(ns.substring(with: m.range)) else { return nil }
            v.append(f)
        }
        if v.count < 4 || v.count % 2 != 0 { return nil }
        var out = [Float](repeating: 0, count: v.count)
        for i in 0..<v.count {
            let f = v[i]
            if !f.isFinite || f < -1e-4 || f > 1.0001 { return nil }
            out[i] = clamp01(f)
            if i % 2 == 0 && i > 0 && out[i] <= out[i - 2] { return nil }
        }
        if out[0] > 1e-3 || out[out.count - 2] < 0.999 { return nil }
        return out
    }

    private static let middleGrey: Float = 0.18
    private static func xOfLinear(_ linear: Float) -> Float { 0.5 + log2(linear / middleGrey) / 10 }

    static func fromLinearCurve(_ pairs: [Float]) -> ToneCurve {
        var out: [Point] = []
        let white = xOfLinear(1)
        var x: Float = 0
        while x < white - 0.015 {
            out.append(Point(x: x, y: display(linearAt(pairs, middleGrey * powf(2, 10 * (x - 0.5))))))
            x += 0.03
        }
        out.append(Point(x: white, y: display(linearAt(pairs, 1))))
        out.append(Point(x: 1, y: display(linearAt(pairs, 1))))
        var c = ToneCurve(points: out)
        c.normalise()
        return c
    }

    static func linearAt(_ pairs: [Float], _ v: Float) -> Float {
        let v = clamp01(v)
        var i = 2
        while i < pairs.count {
            if v <= pairs[i] {
                let x0 = pairs[i - 2], x1 = pairs[i]
                let t = x1 > x0 ? (v - x0) / (x1 - x0) : 0
                return pairs[i - 1] + t * (pairs[i + 1] - pairs[i - 1])
            }
            i += 2
        }
        return pairs[pairs.count - 1]
    }

    static func display(_ linear: Float) -> Float {
        let l = clamp01(linear)
        return l <= 0.0031308 ? 12.92 * l : 1.055 * powf(l, 1 / 2.4) - 0.055
    }
}
