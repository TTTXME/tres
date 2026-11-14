import SwiftUI
import UIKit

/// The camera screen's one message channel (P214f: UiMessages + MessageLog). An information message shows for its text's time
/// (1.3 s + 35 ms a character, at most 7 s); an error stays, in the warning colour, until it is tapped; a repeat of the same text
/// and kind within 10 minutes is one entry with a counter («×3») that moves to the newest place and starts its time again. An
/// information message over an unread error shows for its time, then the error comes back. The last 20 entries are kept in a
/// small file (Application Support/scam_messages.txt, MessageLog's format) so the messages before a restart can still be read.
@MainActor
@Observable
final class MessageCenter {
    static let shared = MessageCenter()
    static let keep = 20
    static let mergeWindow: TimeInterval = 600

    struct Entry: Equatable, Identifiable {
        let text: String
        let error: Bool
        let count: Int
        let first: Date
        let last: Date
        var id: String { "\(error ? "E" : "I")\(last.timeIntervalSince1970)\(text)" }
    }

    /// What the card shows now (nil: hidden)
    private(set) var showing: Entry?
    /// Oldest first
    private(set) var entries: [Entry] = []
    /// A hint of its own length, not in the history (MessageCard.showFor)
    private(set) var plainHint = false
    @ObservationIgnored private var unread: Entry?
    @ObservationIgnored private var timer: Task<Void, Never>?
    @ObservationIgnored private var loaded = false

    /// MessageLog.durationMs: 1.3 s + 35 ms a character, at most 7 s
    static func duration(_ text: String) -> TimeInterval { min(7, 1.3 + 0.035 * Double(text.count)) }

    func info(_ text: String) { post(text, error: false) }
    func error(_ text: String) { post(text, error: true) }

    func post(_ raw: String, error: Bool) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        load()
        let now = Date()
        var entry = Entry(text: text, error: error, count: 1, first: now, last: now)
        if let i = entries.lastIndex(where: { $0.text == text && $0.error == error && now.timeIntervalSince($0.last) <= Self.mergeWindow
            && now >= $0.last }) {
            let old = entries.remove(at: i)
            entry = Entry(text: text, error: error, count: old.count + 1, first: old.first, last: now)
        }
        entries.append(entry)
        if entries.count > Self.keep { entries.removeFirst(entries.count - Self.keep) }
        if error { unread = entry }
        persist()
        SessionLog.i("Message", (error ? "E " : "I ") + text + (entry.count > 1 ? " x\(entry.count)" : ""))
        display(entry, hint: false)
        timer?.cancel()
        if !error { schedule(Self.duration(text)) }
    }

    /// A hint of its own length, not kept in the history (SIMPLE_UI: the first-run hint)
    func showFor(_ text: String, seconds: TimeInterval) {
        let now = Date()
        display(Entry(text: text, error: false, count: 1, first: now, last: now), hint: true)
        timer?.cancel()
        schedule(seconds)
    }

    /// A tap: the card closes and the unread error is read
    func dismiss() {
        unread = nil
        timer?.cancel()
        withAnimation(.easeOut(duration: 0.18)) { showing = nil }
    }

    func clearHistory() {
        entries = []
        unread = nil
        persist()
    }

    private func schedule(_ seconds: TimeInterval) {
        timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
            guard !Task.isCancelled else { return }
            self?.timeout()
        }
    }

    private func timeout() {
        if let pending = unread { display(pending, hint: false) } else { withAnimation(.easeOut(duration: 0.18)) { showing = nil } }
    }

    private func display(_ e: Entry, hint: Bool) {
        plainHint = hint
        // P495: the band comes in as Motion.fade does (from .96, 240 ms on the sheet curve)
        withAnimation(CameraMotion.sheet) { showing = e }
        if e.error { UIAccessibility.post(notification: .announcement, argument: e.text) }
    }

    // the file: a header line, then one entry per line, oldest first: firstMs \t lastMs \t count \t E|I \t text

    private static let header = "scam-messages 1"

    private var file: URL? {
        guard let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("scam_messages.txt")
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let file, let text = try? String(contentsOf: file, encoding: .utf8) else { return }
        let older = Self.parse(text)
        entries = Array((older + entries).suffix(Self.keep))
    }

    private func persist() {
        guard let file else { return }
        var out = Self.header + "\n"
        for e in entries {
            out += "\(Int64(e.first.timeIntervalSince1970 * 1000))\t\(Int64(e.last.timeIntervalSince1970 * 1000))\t\(e.count)\t"
                + (e.error ? "E" : "I") + "\t" + Self.escape(e.text) + "\n"
        }
        try? out.write(to: file, atomically: true, encoding: .utf8)
    }

    /// Newest first (the history sheet)
    var newestFirst: [Entry] {
        load()
        return entries.reversed()
    }

    static func parse(_ text: String) -> [Entry] {
        let lines = text.components(separatedBy: "\n")
        guard lines.first?.trimmingCharacters(in: .whitespaces) == header else { return [] }
        var out: [Entry] = []
        for line in lines.dropFirst() {
            let f = line.split(separator: "\t", maxSplits: 4, omittingEmptySubsequences: false).map(String.init)
            guard f.count == 5, f[3] == "E" || f[3] == "I", let first = Int64(f[0]), let last = Int64(f[1]), let count = Int(f[2]) else { continue }
            let msg = unescape(f[4]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !msg.isEmpty else { continue }
            out.append(Entry(text: msg, error: f[3] == "E", count: max(1, count), first: Date(timeIntervalSince1970: Double(first) / 1000),
                             last: Date(timeIntervalSince1970: Double(last) / 1000)))
        }
        return Array(out.suffix(keep))
    }

    static func escape(_ s: String) -> String {
        var out = ""
        for c in s {
            switch c {
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default: if let a = c.asciiValue, a < 0x20 { continue } else { out.append(c) }
            }
        }
        return out
    }

    static func unescape(_ s: String) -> String {
        var out = ""
        var escaped = false
        for c in s {
            if escaped {
                out.append(c == "n" ? "\n" : c == "r" ? "\r" : c == "t" ? "\t" : c)
                escaped = false
            } else if c == "\\" {
                escaped = true
            } else {
                out.append(c)
            }
        }
        if escaped { out.append("\\") }
        return out
    }
}

/// MessageCard (SIMPLE_UI §1): one 22 pt single-line pill (12 pt) in the band between the viewfinder and the bottom bar; «×N» for a
/// repeat; an error in the warning colour until tapped; a tap closes it, a long press opens «Последние сообщения».
struct MessageCardView: View {
    var onHistory: () -> Void

    var body: some View {
        let center = MessageCenter.shared
        if let e = center.showing {
            Text(e.text + (e.count > 1 ? "  ×\(e.count)" : ""))
                .font(.system(size: 12))
                .foregroundStyle(e.error ? CameraStyle.warn : CameraStyle.text)
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.horizontal, 10)
                .frame(height: 22)
                .background(RoundedRectangle(cornerRadius: 11).fill(Color(red: 0x1A / 255, green: 0x1F / 255, blue: 0x22 / 255).opacity(0.92)))
                .overlay(RoundedRectangle(cornerRadius: 11).stroke(CameraStyle.line, lineWidth: 1))
                .padding(.horizontal, 12)
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
                .id(e.id)
                .onTapGesture { center.dismiss() }
                .onLongPressGesture(minimumDuration: 0.5) { Haptics.longPress(); onHistory() }
                .accessibilityLabel(e.error ? e.text + ". " + L("msg.tap_close") : e.text)
                .accessibilityAddTraits(.isButton)
        }
    }
}

/// MessageHistorySheet: «Последние сообщения» - the last 20 messages, newest first, with the time, the repeat count and the error
/// mark, in one #2C2C2E card; «Скопировать» and «Очистить».
struct MessageHistorySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var entries = MessageCenter.shared.newestFirst

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetGrab().frame(maxWidth: .infinity)
            Text(L("msg.history")).font(.system(size: 16.5, weight: .semibold)).foregroundStyle(CameraStyle.text)
                .padding(.leading, 4).padding(.top, 12).padding(.bottom, 12)
            if entries.isEmpty {
                Text(L("msg.none")).font(.system(size: 15)).foregroundStyle(CameraStyle.muted)
                    .frame(maxWidth: .infinity).padding(.top, 8).padding(.bottom, 12)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(entries.enumerated()), id: \.offset) { i, e in
                            if i > 0 { Rectangle().fill(CameraStyle.hairline).frame(height: 0.5) }
                            VStack(alignment: .leading, spacing: 4) {
                                Text(Self.meta(e)).font(.system(size: 12)).foregroundStyle(e.error ? CameraStyle.warn : CameraStyle.summary)
                                Text(e.text).font(.system(size: 14)).foregroundStyle(CameraStyle.text).lineSpacing(2).textSelection(.enabled)
                            }
                            .padding(.horizontal, 16).padding(.vertical, 11)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .background(CameraStyle.row, in: RoundedRectangle(cornerRadius: 16))
                }
                HStack(spacing: 10) {
                    Button { UIPasteboard.general.string = Self.plain(entries) } label: {
                        Text(L("msg.copy")).font(.system(size: 15, weight: .semibold)).foregroundStyle(CameraStyle.text)
                            .frame(maxWidth: .infinity, minHeight: 40).background(CameraStyle.row, in: Capsule())
                    }
                    Button {
                        MessageCenter.shared.clearHistory()
                        dismiss()
                    } label: {
                        Text(L("msg.clear")).font(.system(size: 15, weight: .semibold)).foregroundStyle(CameraStyle.red)
                            .frame(maxWidth: .infinity, minHeight: 40).background(CameraStyle.row, in: Capsule())
                    }
                }
                .padding(.top, 14)
            }
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 18)
        .presentationBackground(CameraStyle.control)
        .presentationCornerRadius(36)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .preferredColorScheme(.dark)
    }

    /// «12:04:05 · ×3 (с 12:01:40) · ошибка»
    static func meta(_ e: MessageCenter.Entry) -> String {
        var out = time(e.last)
        if e.count > 1 { out += " · ×\(e.count)" + String(format: L("msg.since"), time(e.first)) }
        if e.error { out += " · " + L("msg.error") }
        return out
    }

    /// The copied text: one line per message, newest first
    static func plain(_ entries: [MessageCenter.Entry]) -> String {
        entries.map { e in
            time(e.last) + (e.count > 1 ? " x\(e.count)" : "") + (e.error ? " E" : "") + "  " + e.text.replacingOccurrences(of: "\n", with: " ")
        }.joined(separator: "\n") + "\n"
    }

    /// HH:mm:ss today, dd.MM HH:mm:ss on another day
    static func time(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = Calendar.current.isDateInToday(d) ? "HH:mm:ss" : "dd.MM HH:mm:ss"
        return f.string(from: d)
    }
}
