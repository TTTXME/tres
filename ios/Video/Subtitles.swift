import Foundation

/// The player's subtitles (Android gallery/video/Subtitles): SubRip (.srt) and WebVTT (.vtt) files picked by hand. Cues are kept
/// sorted; `at(_:_:)` gives the text at a time. The text tracks inside the file are AVPlayer's own legible options (drawn by the
/// player layer), and a file «beside the video» has no counterpart in the photo library.
enum Subtitles {
    struct Cue: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let text: String
    }

    private static let timing = try! NSRegularExpression(
        pattern: "((?:\\d+:)?\\d{1,2}:\\d{1,2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{1,2}[,.]\\d{1,3})")

    /// «01:02:03,456», «02:03.456», «2:03.4» in ms; -1 when unreadable
    static func time(_ t: String) -> Int64 {
        let main = t.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".").split(separator: ".", omittingEmptySubsequences: false)
        guard let head = main.first else { return -1 }
        var ms: Int64 = 0
        for p in head.split(separator: ":", omittingEmptySubsequences: false) {
            guard let v = Int64(p.trimmingCharacters(in: .whitespaces)) else { return -1 }
            ms = ms * 60 + v
        }
        ms *= 1000
        if main.count > 1 {
            let frac = String((String(main[1]) + "00").prefix(3))
            guard let f = Int64(frac) else { return -1 }
            ms += f
        }
        return ms
    }

    /// Drops the markup (<i>, <font ...>, {\an8}) the player does not draw
    static func clean(_ s: String) -> String {
        var t = s.replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "\\{\\\\[^}]*\\}", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "&nbsp;", with: " ").replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func timingMatch(_ line: String) -> NSTextCheckingResult? {
        timing.firstMatch(in: line, range: NSRange(line.startIndex..., in: line))
    }

    /// SubRip or WebVTT text: every «start --> end» line and the text lines under it up to an empty line
    static func parse(_ source: String) -> [Cue] {
        var cues: [Cue] = []
        let normalised = source.replacingOccurrences(of: "\u{FEFF}", with: "").replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let lines = normalised.components(separatedBy: "\n")
        var i = 0
        while i < lines.count {
            guard let m = timingMatch(lines[i]), m.numberOfRanges >= 3,
                  let r1 = Range(m.range(at: 1), in: lines[i]), let r2 = Range(m.range(at: 2), in: lines[i]) else {
                i += 1
                continue
            }
            let a = time(String(lines[i][r1])), b = time(String(lines[i][r2]))
            var text = ""
            var k = i + 1
            while k < lines.count, !lines[k].trimmingCharacters(in: .whitespaces).isEmpty {
                if timingMatch(lines[k]) != nil { break }
                if !text.isEmpty { text += "\n" }
                text += lines[k]
                k += 1
            }
            let t = clean(text)
            if a >= 0, b > a, !t.isEmpty { cues.append(Cue(startMs: a, endMs: b, text: t)) }
            i = max(k, i + 1)
        }
        return cues.enumerated().sorted { $0.element.startMs != $1.element.startMs ? $0.element.startMs < $1.element.startMs : $0.offset < $1.offset }
            .map(\.element)
    }

    /// The text shown at `ms` (overlapping cues joined by a line), or nil
    static func at(_ cues: [Cue], _ ms: Int64) -> String? {
        var lo = 0, hi = cues.count - 1, last = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if cues[mid].startMs <= ms {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var out: String?
        var i = last
        while i >= 0 && i > last - 8 {
            let c = cues[i]
            if ms >= c.startMs && ms < c.endMs {
                out = out == nil ? c.text : c.text + "\n" + out!
            }
            i -= 1
        }
        return out
    }

    /// A subtitle file's text: UTF-8, or Windows-1251 when it is not valid UTF-8 (old Russian .srt files)
    static func decode(_ data: Data) -> String {
        if let s = String(data: data, encoding: .utf8) { return s }
        if let s = String(data: data, encoding: .windowsCP1251) { return s }
        return String(decoding: data, as: UTF8.self)
    }

    /// A picked file (at most 8 MB read)
    static func read(_ url: URL) throws -> [Cue] {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: 8 << 20) ?? Data()
        return parse(decode(data))
    }
}
