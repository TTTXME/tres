import Foundation
import os

enum BuildInfo {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?" }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?" }
    static var sha: String { Bundle.main.object(forInfoDictionaryKey: "SCAMGitSHA") as? String ?? "local" }

    /// Hardware identifier such as "iPhone16,1"; the marketing name is not available through public API.
    static let machine: String = {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
    }()

    static var line: String {
        "SCAMERA iOS \(version) (\(build)) \(sha) \(machine) \(ProcessInfo.processInfo.operatingSystemVersionString)"
    }
}

/// Daily session log in Documents/Log (log-YYYY-MM-DD.txt), the iOS counterpart of the Android ScameraFileLog.
final class SessionLog: @unchecked Sendable {
    static let shared = SessionLog()
    static let keepDays = 7

    let folder: URL
    private let queue = DispatchQueue(label: "scamera.session-log")
    private let logger = Logger(subsystem: "com.scamera.ios", category: "SCAMERA")
    private let clock: DateFormatter
    private let dayFormat: DateFormatter
    private var handle: FileHandle?
    private var day = ""

    init(folder: URL? = nil) {
        self.folder = folder ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Log", isDirectory: true)
        clock = DateFormatter()
        clock.locale = Locale(identifier: "en_US_POSIX")
        clock.dateFormat = "HH:mm:ss.SSS"
        dayFormat = DateFormatter()
        dayFormat.locale = Locale(identifier: "en_US_POSIX")
        dayFormat.dateFormat = "yyyy-MM-dd"
        try? FileManager.default.createDirectory(at: self.folder, withIntermediateDirectories: true)
        removeExpired(now: Date())
    }

    static func i(_ tag: String, _ message: String) { shared.write("I", tag, message) }
    static func w(_ tag: String, _ message: String) { shared.write("W", tag, message) }
    static func e(_ tag: String, _ message: String) { shared.write("E", tag, message) }

    func write(_ level: String, _ tag: String, _ message: String) {
        let now = Date()
        logger.log("\(level, privacy: .public)/\(tag, privacy: .public): \(message, privacy: .public)")
        queue.async { [self] in
            let today = dayFormat.string(from: now)
            if handle == nil || today != day { open(today) }
            handle?.write(Data("\(clock.string(from: now)) \(level)/\(tag): \(message)\n".utf8))
        }
    }

    /// Waits for queued lines and returns the log files, newest first.
    func flushedFiles() -> [URL] {
        queue.sync { try? handle?.synchronize() }
        return files()
    }

    func files() -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        return all.filter { $0.lastPathComponent.hasPrefix("log-") && $0.pathExtension == "txt" }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
    }

    private func open(_ today: String) {
        try? handle?.close()
        let url = folder.appendingPathComponent("log-\(today).txt")
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        handle = try? FileHandle(forWritingTo: url)
        _ = try? handle?.seekToEnd()
        day = today
        handle?.write(Data("--- \(clock.string(from: Date())) I/ScameraBuild: \(BuildInfo.line)\n".utf8))
    }

    private func removeExpired(now: Date) {
        let limit = now.addingTimeInterval(-Double(Self.keepDays) * 86_400)
        for url in files() {
            let name = url.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "log-", with: "")
            if let date = dayFormat.date(from: name), date < limit {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }
}
