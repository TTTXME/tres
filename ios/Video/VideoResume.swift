import Foundation

/// Where each video was left (Android gallery/video/VideoResume), by the asset's local identifier in the app's defaults
/// («gallery_video_resume»: "position/duration/savedAt" in ms). Nothing is kept for the first 3 s or the last 3 s (a video
/// watched to the end starts over); the 300 most recent are kept. The grid draws the watched part under a thumbnail.
enum VideoResume {
    static let edgeMs: Int64 = 3000
    static let keep = 300
    private static let key = "gallery_video_resume"

    /// Where to start from a saved position: 0 near either end
    static func resumeAt(savedMs: Int64, durationMs: Int64) -> Int64 {
        if savedMs < edgeMs { return 0 }
        if durationMs > 0 && savedMs > durationMs - edgeMs { return 0 }
        return savedMs
    }

    /// "position/duration/savedAt" → (position, duration, savedAt), nil when unreadable
    static func decode(_ v: String?) -> (Int64, Int64, Int64)? {
        guard let v else { return nil }
        let p = v.split(separator: "/", omittingEmptySubsequences: false)
        guard p.count == 3, let a = Int64(p[0]), let b = Int64(p[1]), let c = Int64(p[2]) else { return nil }
        return (a, b, c)
    }

    private static var all: [String: String] {
        (UserDefaults.standard.dictionary(forKey: key) as? [String: String]) ?? [:]
    }

    /// The saved position in ms (0: start over)
    static func load(_ id: String) -> Int64 {
        guard let v = decode(all[id]) else { return 0 }
        return resumeAt(savedMs: v.0, durationMs: v.1)
    }

    /// The watched share 0...1 for the grid, or -1 when nothing is kept
    static func progress(_ id: String) -> Double {
        guard let v = decode(all[id]), v.1 > 0, resumeAt(savedMs: v.0, durationMs: v.1) != 0 else { return -1 }
        return min(1, Double(v.0) / Double(v.1))
    }

    static func save(_ id: String, positionMs: Int64, durationMs: Int64) {
        var d = all
        if resumeAt(savedMs: positionMs, durationMs: durationMs) == 0 {
            d.removeValue(forKey: id)
        } else {
            d[id] = "\(positionMs)/\(durationMs)/\(Int64(Date().timeIntervalSince1970 * 1000))"
            if d.count > keep {   // drop the oldest
                var oldest: String?
                var at = Int64.max
                for (k, v) in d where k != id {
                    let t = decode(v)?.2 ?? 0
                    if t < at { at = t; oldest = k }
                }
                if let oldest { d.removeValue(forKey: oldest) }
            }
        }
        UserDefaults.standard.set(d, forKey: key)
    }

    /// GalleryVideo.clock: «1:05», «1:02:03»
    static func clock(_ ms: Int64) -> String {
        let s = max(0, ms / 1000)
        let h = s / 3600, m = s / 60 % 60, sec = s % 60
        return h > 0 ? String(format: "%ld:%02ld:%02ld", h, m, sec) : String(format: "%ld:%02ld", m, sec)
    }
}
