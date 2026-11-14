import AVFoundation
import Photos
import SwiftUI
import UIKit

/// The gallery's video player (Android gallery/video/VideoPlayerActivity, P499) on AVPlayer: the videos of the viewer's list
/// (previous / next), play / pause, the seek bar (the elapsed time, the length or what remains), the speed 0.25x..4x, mute, repeat
/// (off / all / one), the picture fit and the pinch zoom, the rotation (by the video / landscape / portrait), the screen lock, the
/// sleep timer, a screenshot into the «SCAMERA» album, subtitles (the file's own text tracks drawn by the player layer, or a
/// SubRip / WebVTT file picked by hand), the sound track, where each video was left (VideoResume), the video's details, share and
/// delete. Gestures as Android's: double tap ∓ the step at the sides (taps add up), pause in the middle; a sideways drag seeks
/// (up to 90 s across); a vertical drag the brightness (left) / the volume (right); a long press 2x while held.
/// The volume goes on over 100 % up to 200 % (VideoBoost: up to +10 dB, LoudnessEnhancer's); «Фоновое воспроизведение» keeps the
/// sound on when the app is left (the lock screen's card with play / pause, VideoPlaybackService's notification); picture in picture
/// by its button or on leaving the app; «Автоповорот» turns the picture with the phone (the app's screen is portrait: the player
/// turns its own picture, as for the other rotations). Not on the iPhone: the decoder choice (AVPlayer decodes in hardware).
@MainActor
@Observable
final class VideoPlayerModel {
    enum Repeat: Int { case off, all, one }
    /// VideoPlayerActivity.Rotate (VIDEO, SENSOR, LANDSCAPE, PORTRAIT); the raw values keep the stored choices of before
    enum Rotate: Int { case video = 0, landscape = 1, portrait = 2, sensor = 3 }
    enum SubsChoice: Equatable { case off, track(Int), file }

    struct Hud: Equatable {
        var icon: String
        var title: String
        var detail: String?
        var level: Double?
    }

    struct Pill: Equatable {
        let id = UUID()
        var text: String
        var action: String?
    }

    let player = AVPlayer()
    private(set) var items: [PHAsset]
    private(set) var index: Int
    private(set) var title = ""
    private(set) var prepared = false
    private(set) var playing = false
    private(set) var position = 0.0
    private(set) var duration = 0.0
    private(set) var videoSize = CGSize.zero
    private(set) var dragging = false
    var showRemaining = false
    private(set) var muted = false
    private(set) var volume: Float = 1
    private(set) var speed: Float = 1
    private(set) var repeatMode: Repeat
    private(set) var fit: VideoFit
    private(set) var rotate: Rotate
    private(set) var zoom: CGFloat = 1
    private(set) var pan = CGSize.zero
    private(set) var locked = false
    private(set) var unlockShown = false
    var controls = true
    /// A panel is open: the controls stay
    var panelOpen = false { didSet { scheduleHide() } }
    private(set) var sleepAt: Date?
    private(set) var sleepAtEnd = false
    private(set) var subtitle: String?
    private(set) var subsChoice = SubsChoice.off
    private(set) var pickedName: String?
    private(set) var legible: [AVMediaSelectionOption] = []
    private(set) var audible: [AVMediaSelectionOption] = []
    private(set) var audioChoice: Int?
    private(set) var hud: Hud?
    private(set) var pill: Pill?

    private var asset: AVAsset?
    private var legibleGroup: AVMediaSelectionGroup?
    private var audibleGroup: AVMediaSelectionGroup?
    private var cues: [Subtitles.Cue] = []
    private var pickedCues: [Subtitles.Cue] = []
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?
    private var hudTask: Task<Void, Never>?
    private var pillTask: Task<Void, Never>?
    private var hideTask: Task<Void, Never>?
    private var unlockTask: Task<Void, Never>?
    private var lastSave = Date.distantPast
    private var doubleTapAt = Date.distantPast
    private var doubleTapSum = 0
    private var heldSpeed: Float?
    private var dragKind: VideoSurface.DragKind?
    private var dragStart = 0.0
    private var dragTarget = 0.0
    private var wasPlaying = false
    private var lastPreview = Date.distantPast
    private var savedBrightness: CGFloat?
    private var started = false
    /// «Фоновое воспроизведение», «Картинка в картинке при выходе», picture in picture now
    private(set) var background: Bool
    private(set) var pipOnHome: Bool
    private(set) var pipActive = false
    /// «Автоповорот»: the phone held sideways and which way (the picture turned +90° or -90°)
    private(set) var deviceLandscape = false
    private(set) var deviceTurn: Double = 90
    /// 1..2: the volume over the full one (VideoBoost)
    private(set) var boostLevel: Float = 0
    @ObservationIgnored private let boost = VideoBoost()
    @ObservationIgnored private let session = VideoBackground()
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private weak var playerLayer: AVPlayerLayer?
    @ObservationIgnored private var lastNowPlaying = Date.distantPast

    static let prefsKey = "video_player"
    static let steps = [5, 10, 15, 20, 30, 60]
    static let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 2, 3]
    static let sleepMinutes = [15, 30, 45, 60, 90]

    init(items: [PHAsset], index: Int) {
        self.items = items
        self.index = min(max(index, 0), max(items.count - 1, 0))
        let p = Self.prefs
        repeatMode = Repeat(rawValue: p["repeat"] as? Int ?? 0) ?? .off
        fit = VideoFit(rawValue: p["fit"] as? Int ?? 0) ?? .fit
        background = p["background"] as? Bool ?? false
        pipOnHome = p["pip_on_home"] as? Bool ?? false
        rotate = Rotate(rawValue: p["rotate"] as? Int ?? 0) ?? .video
    }

    // ---------------------------------------------------------------- the player's own settings («video_player»)

    private static var prefs: [String: Any] { UserDefaults.standard.dictionary(forKey: prefsKey) ?? [:] }

    private static func setPref(_ key: String, _ value: Any) {
        var d = prefs
        d[key] = value
        UserDefaults.standard.set(d, forKey: prefsKey)
    }

    /// The double tap's step in seconds (5 / 10 / 15 / 20 / 30 / 60, default 10)
    var seekStep: Int {
        get { Self.prefs["seek_step"] as? Int ?? 10 }
        set { Self.setPref("seek_step", newValue) }
    }

    /// The subtitles' size: 0 small (16), 1 medium (20), 2 large (26)
    var subtitleSize: Int {
        get { Self.prefs["sub_size"] as? Int ?? 1 }
        set { Self.setPref("sub_size", newValue) }
    }

    var subtitlePoints: CGFloat { subtitleSize == 0 ? 16 : subtitleSize == 2 ? 26 : 20 }

    /// The picture turned: by the video (a landscape video lies across), or as chosen
    var landscape: Bool {
        switch rotate {
        case .landscape: return true
        case .portrait: return false
        case .video: return videoSize.width > videoSize.height
        case .sensor: return deviceLandscape
        }
    }

    /// How far the picture is turned: sideways with the phone's way under «Автоповорот», else +90°.
    var turnDegrees: Double { landscape ? (rotate == .sensor ? deviceTurn : 90) : 0 }

    var current: PHAsset? { items.indices.contains(index) ? items[index] : nil }
    var hasPrevious: Bool { index > 0 || (repeatMode == .all && items.count > 1) }
    var hasNext: Bool { index < items.count - 1 || (repeatMode == .all && items.count > 1) }

    // ---------------------------------------------------------------- the lifetime

    func start() {
        guard !started else { return }
        started = true
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 120, timescale: 1000), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t.seconds) }
        }
        session.activate()
        session.onPipChange = { [weak self] on in
            self?.pipActive = on
            if on { self?.panelOpen = false }
        }
        session.remote(play: { [weak self] in self?.play() }, pause: { [weak self] in self?.pause() },
                       toggle: { [weak self] in self?.togglePlay() })
        // «Автоповорот»: the phone's way (portrait, sideways either way; flat or upside down keeps the last)
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.deviceTurned() }
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.wentBackground() }
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.cameForeground() }
        })
        deviceTurned()
        load(index)
    }

    func stop() {
        saveResume()
        loadTask?.cancel()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        UIApplication.shared.isIdleTimerDisabled = false
        restoreBrightness()
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        session.deactivate()
        started = false
    }

    // ---------------------------------------------------------------- P499 background, picture in picture, the phone's way

    private func deviceTurned() {
        switch UIDevice.current.orientation {
        case .landscapeLeft:
            deviceLandscape = true
            deviceTurn = 90
        case .landscapeRight:
            deviceLandscape = true
            deviceTurn = -90
        case .portrait:
            deviceLandscape = false
        default:
            break
        }
    }

    /// The app left: with «Фоновое воспроизведение» the sound goes on (the picture's layer lets go of the player, as iOS pauses a
    /// player shown on a layer in the background), else the video pauses; picture in picture keeps both.
    private func wentBackground() {
        guard prepared, !pipActive else { return }
        if background && playing {
            playerLayer?.player = nil
            updateNowPlaying(force: true)
        } else if playing {
            pause()
        }
    }

    private func cameForeground() {
        if let layer = playerLayer, layer.player !== player { layer.player = player }
    }

    /// VideoSurface's layer: picture in picture over it.
    func attach(_ layer: AVPlayerLayer) {
        playerLayer = layer
        session.attach(layer, automatic: pipOnHome)
    }

    var pipSupported: Bool { VideoBackground.pipSupported }

    func startPip() {
        if !session.startPip() { showPill(L("video.pip_unavailable")) }
    }

    func toggleBackground() {
        background.toggle()
        Self.setPref("background", background)
        showHud("kb_ui_ic_headphones_rest", background ? L("video.background_on") : L("video.background_off"),
                background ? L("video.background_hint") : nil)
    }

    func setPipOnHome(_ on: Bool) {
        pipOnHome = on
        Self.setPref("pip_on_home", on)
        session.setAutomatic(on)
    }

    /// The lock screen's card: the title, the length, where it is, the speed (at most once a second unless asked).
    private func updateNowPlaying(force: Bool = false) {
        guard force || Date().timeIntervalSince(lastNowPlaying) > 1 else { return }
        lastNowPlaying = Date()
        session.nowPlaying(title: title, duration: duration, position: position, rate: playing ? speed : 0)
    }

    func load(_ i: Int) {
        saveResume()
        guard items.indices.contains(i) else { return }
        index = i
        prepared = false
        playing = false
        position = 0
        duration = 0
        videoSize = .zero
        cues = []
        pickedCues = []
        pickedName = nil
        subtitle = nil
        subsChoice = .off
        legible = []
        audible = []
        legibleGroup = nil
        audibleGroup = nil
        audioChoice = nil
        zoom = 1
        pan = .zero
        asset = nil
        let a = items[i]
        title = Self.stem(GalleryLibrary.videoResource(a)?.originalFilename ?? "")
        if title.isEmpty, let d = a.creationDate { title = d.formatted(date: .abbreviated, time: .shortened) }
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let avAsset = await Self.requestAsset(a) else {
                self?.showPill(L("video.open_failed"))
                return
            }
            guard let self, !Task.isCancelled else { return }
            await self.prepare(avAsset, a)
        }
    }

    private func prepare(_ avAsset: AVAsset, _ a: PHAsset) async {
        asset = avAsset
        let item = AVPlayerItem(asset: avAsset)
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item,
                                                             queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.completed() }
        }
        // the volume boost's tap on the sound track (a file's; a stream or a video without sound plays as it is)
        if let audio = try? await avAsset.loadTracks(withMediaType: .audio).first { item.audioMix = boost.mix(for: audio) }
        player.replaceCurrentItem(with: item)
        let d = (try? await avAsset.load(.duration))?.seconds ?? 0
        duration = d.isFinite && d > 0 ? d : a.duration
        if let track = try? await avAsset.loadTracks(withMediaType: .video).first,
           let loaded = try? await track.load(.naturalSize, .preferredTransform) {
            let r = CGRect(origin: .zero, size: loaded.0).applying(loaded.1)
            videoSize = CGSize(width: abs(r.width), height: abs(r.height))
        } else {
            videoSize = CGSize(width: a.pixelWidth, height: a.pixelHeight)
        }
        if let g = try? await avAsset.loadMediaSelectionGroup(for: .legible) {
            legibleGroup = g
            legible = g.options
            item.select(nil, in: g)
        }
        if let g = try? await avAsset.loadMediaSelectionGroup(for: .audible) {
            audibleGroup = g
            audible = g.options
            if let selected = item.currentMediaSelection.selectedMediaOption(in: g) { audioChoice = g.options.firstIndex(of: selected) }
        }
        guard !Task.isCancelled, current?.localIdentifier == a.localIdentifier else { return }
        prepared = true
        player.isMuted = muted
        player.volume = volume
        let resume = VideoResume.load(a.localIdentifier)
        SessionLog.i("VideoPlayer", "open \(title): \(Int(videoSize.width))x\(Int(videoSize.height)), \(String(format: "%.1f", duration)) s, resume \(resume) ms")
        if resume > 0 {
            _ = await player.seek(to: CMTime(value: resume, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        play()
        if resume > 0 { showPill(String(format: L("video.resumed"), VideoResume.clock(resume)), action: L("video.from_start")) }
        // autoSubs: the file's first text track comes on by itself
        if !legible.isEmpty { selectSubs(.track(0)) }
        scheduleHide()
    }

    nonisolated static func requestAsset(_ a: PHAsset) async -> AVAsset? {
        await withCheckedContinuation { (c: CheckedContinuation<AVAsset?, Never>) in
            let o = PHVideoRequestOptions()
            o.isNetworkAccessAllowed = true
            o.deliveryMode = .highQualityFormat
            o.version = .current
            PHImageManager.default().requestAVAsset(forVideo: a, options: o) { asset, _, _ in c.resume(returning: asset) }
        }
    }

    static func stem(_ name: String) -> String {
        guard let dot = name.lastIndex(of: "."), dot > name.startIndex else { return name }
        return String(name[..<dot])
    }

    // ---------------------------------------------------------------- the clock

    private func tick(_ seconds: Double) {
        guard prepared else { return }
        if !dragging, seconds.isFinite { position = seconds }
        playing = player.timeControlStatus != .paused
        let ms = Int64(max(0, position) * 1000)
        let text = cues.isEmpty ? nil : Subtitles.at(cues, ms)
        if text != subtitle { subtitle = text }
        if playing, Date().timeIntervalSince(lastSave) > 3 { saveResume() }
        updateNowPlaying()
        if let at = sleepAt, Date() >= at {
            sleepAt = nil
            pause()
            showHud("ui_ic_moon", L("video.sleep"), L("video.paused"))
        }
        if player.currentItem?.status == .failed {
            prepared = false
            showPill(L("video.decode_failed"))
            controls = true
        }
    }

    private func completed() {
        if let a = current { VideoResume.save(a.localIdentifier, positionMs: 0, durationMs: 0) }   // watched: next time from the start
        if sleepAtEnd {
            sleepAtEnd = false
            playing = false
            UIApplication.shared.isIdleTimerDisabled = false
            showHud("ui_ic_moon", L("video.sleep"), L("video.ended"))
            return
        }
        switch repeatMode {
        case .one:
            restart()
        case .all where items.count > 1:
            next()
        case .all:
            restart()
        case .off:
            playing = false
            UIApplication.shared.isIdleTimerDisabled = false
            controls = true
        }
    }

    private func restart() {
        player.seek(to: .zero)
        play()
    }

    func saveResume() {
        guard prepared, let a = current, duration > 0 else { return }
        let pos = player.currentTime().seconds
        guard pos.isFinite else { return }
        if pos >= duration - 0.05 { return }   // completed() keeps the start
        VideoResume.save(a.localIdentifier, positionMs: Int64(pos * 1000), durationMs: Int64(duration * 1000))
        lastSave = Date()
    }

    // ---------------------------------------------------------------- playback

    func play() {
        guard prepared else { return }
        player.defaultRate = speed
        player.playImmediately(atRate: speed)
        playing = true
        UIApplication.shared.isIdleTimerDisabled = true
        updateNowPlaying(force: true)
    }

    func pause() {
        player.pause()
        playing = false
        saveResume()
        UIApplication.shared.isIdleTimerDisabled = false
        updateNowPlaying(force: true)
    }

    func togglePlay() {
        if playing {
            pause()
            controls = true
            hideTask?.cancel()
        } else {
            play()
            scheduleHide()
        }
    }

    /// MX: the first press goes back to the start (after 3 s), the next to the previous video
    func previous() {
        if position > 3 {
            seek(to: 0, exact: true)
            return
        }
        guard hasPrevious else { return }
        load(index > 0 ? index - 1 : items.count - 1)
    }

    func next() {
        guard hasNext else { return }
        load(index < items.count - 1 ? index + 1 : 0)
    }

    func seek(to seconds: Double, exact: Bool) {
        let t = CMTime(seconds: min(max(0, seconds), max(0, duration)), preferredTimescale: 600)
        if exact {
            player.seek(to: t, toleranceBefore: .zero, toleranceAfter: .zero)
        } else {
            player.seek(to: t)
        }
        position = t.seconds
    }

    /// The seek bar: while dragged the position follows the finger (previewed), at the end the exact frame
    func scrub(to seconds: Double) {
        dragTarget = seconds
        position = seconds
        if Date().timeIntervalSince(lastPreview) > 0.09 {
            lastPreview = Date()
            player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        }
    }

    func scrubbing(_ on: Bool) {
        if on {
            dragging = true
            wasPlaying = playing
            dragTarget = position
            player.pause()
            hideTask?.cancel()
        } else {
            dragging = false
            seek(to: dragTarget, exact: true)
            if wasPlaying { play() }
            scheduleHide()
        }
    }

    func setSpeed(_ value: Float) {
        speed = min(4, max(0.25, (value * 100).rounded() / 100))
        player.defaultRate = speed
        if playing { player.rate = speed }
    }

    static func speedLabel(_ s: Float) -> String {
        var v = String(format: "%.2f", s)
        while v.hasSuffix("0") { v.removeLast() }
        if v.hasSuffix(".") { v.removeLast() }
        return v + "x"
    }

    func toggleMute() {
        muted.toggle()
        player.isMuted = muted
        showHud(muted ? "kb_ui_ic_mute_rest" : "kb_ui_ic_sound_rest", muted ? L("video.muted") : L("video.sound_on"))
    }

    func cycleRepeat() {
        repeatMode = Repeat(rawValue: (repeatMode.rawValue + 1) % 3) ?? .off
        Self.setPref("repeat", repeatMode.rawValue)
        showHud(repeatMode == .one ? "ui_ic_repeat_one" : "ui_ic_repeat", repeatName)
    }

    var repeatName: String {
        switch repeatMode {
        case .off: return L("video.repeat_off")
        case .all: return L("video.repeat_all")
        case .one: return L("video.repeat_one")
        }
    }

    func cycleFit() {
        fit = VideoFit(rawValue: (fit.rawValue + 1) % VideoFit.allCases.count) ?? .fit
        Self.setPref("fit", fit.rawValue)
        zoom = 1
        pan = .zero
        showHud("kb_ui_ic_aspect_rest", fit.title)
    }

    func cycleRotate() {
        // Android's order: by the video → auto-rotate → landscape → portrait
        let order: [Rotate] = [.video, .sensor, .landscape, .portrait]
        rotate = order[((order.firstIndex(of: rotate) ?? 0) + 1) % order.count]
        Self.setPref("rotate", rotate.rawValue)
        deviceTurned()
        showHud("kb_ui_ic_rotate_rest", rotateName)
    }

    var rotateName: String {
        switch rotate {
        case .video: return L("video.rotate_video")
        case .sensor: return L("video.rotate_sensor")
        case .landscape: return L("video.rotate_landscape")
        case .portrait: return L("video.rotate_portrait")
        }
    }

    func setLocked(_ on: Bool) {
        locked = on
        controls = !on
        if on {
            showHud("kb_ui_ic_lock_rest", L("video.locked"), L("video.locked_hint"))
        } else {
            unlockShown = false
            scheduleHide()
        }
    }

    /// 0 off, -1 at the end of this video, else minutes
    func setSleep(_ minutes: Int) {
        sleepAtEnd = minutes < 0
        sleepAt = minutes > 0 ? Date().addingTimeInterval(Double(minutes) * 60) : nil
        if minutes != 0 {
            showHud("ui_ic_moon", minutes < 0 ? L("video.sleep_end_short") : String(format: L("video.minutes"), minutes), L("video.then_pause"))
        }
    }

    /// The quick button's text: what remains of the sleep timer
    var sleepLabel: String {
        if sleepAtEnd { return L("video.sleep_end_short") }
        if let at = sleepAt { return VideoResume.clock(Int64(max(0, at.timeIntervalSinceNow) * 1000)) }
        return L("video.sleep_short")
    }

    // ---------------------------------------------------------------- subtitles and sound

    func selectSubs(_ choice: SubsChoice) {
        guard let item = player.currentItem else { return }
        subsChoice = choice
        switch choice {
        case .off:
            if let g = legibleGroup { item.select(nil, in: g) }
            cues = []
        case .track(let i):
            if let g = legibleGroup, legible.indices.contains(i) { item.select(legible[i], in: g) }
            cues = []
        case .file:
            if let g = legibleGroup { item.select(nil, in: g) }
            cues = pickedCues
        }
        subtitle = nil
    }

    func loadSubtitleFile(_ url: URL) {
        let name = url.lastPathComponent
        let id = current?.localIdentifier
        Task { [weak self] in
            let result: [Subtitles.Cue]? = await Task.detached(priority: .userInitiated) { try? Subtitles.read(url) }.value
            guard let self, self.current?.localIdentifier == id else { return }
            guard let cues = result else {
                self.showPill(L("video.subs_unreadable"))
                return
            }
            self.pickedCues = cues
            self.pickedName = name
            self.selectSubs(.file)
            if cues.isEmpty { self.showPill(L("video.subs_empty")) }
        }
    }

    /// nil: no sound (the track off where the file allows it, else muted)
    func selectAudio(_ i: Int?) {
        guard let item = player.currentItem, let g = audibleGroup else { return }
        if let i, audible.indices.contains(i) {
            item.select(audible[i], in: g)
            audioChoice = i
            if muted { toggleMute() }
        } else if g.allowsEmptySelection {
            item.select(nil, in: g)
            audioChoice = nil
        } else if !muted {
            toggleMute()
        }
    }

    static func optionName(_ o: AVMediaSelectionOption, _ i: Int, prefix: String) -> String {
        var s = prefix + " \(i + 1)"
        if let lang = o.extendedLanguageTag ?? o.locale?.identifier { s += " · " + lang }
        return s
    }

    // ---------------------------------------------------------------- gestures

    func tap() {
        if locked {
            unlockShown = true
            unlockTask?.cancel()
            unlockTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if !Task.isCancelled { self?.unlockShown = false }
            }
            return
        }
        controls.toggle()
        scheduleHide()
    }

    func doubleTap(at x: CGFloat) {
        guard !locked, prepared else { return }
        if x > 0.35 && x < 0.65 {
            togglePlay()
            showHud(playing ? "ui_ic_play" : "ui_ic_pause_bars", playing ? L("video.play") : L("video.pause"))
            return
        }
        let dir = x < 0.5 ? -1 : 1
        if Date().timeIntervalSince(doubleTapAt) > 1.2 || doubleTapSum.signum() != dir { doubleTapSum = 0 }
        doubleTapSum += dir * seekStep
        doubleTapAt = Date()
        seek(to: player.currentTime().seconds + Double(dir * seekStep), exact: true)
        showHud(dir < 0 ? "kb_ui_ic_undo_rest" : "kb_ui_ic_redo_rest", (dir < 0 ? "−" : "+") + "\(abs(doubleTapSum))" + L("video.seconds_suffix"))
    }

    func drag(_ kind: VideoSurface.DragKind, dx: CGFloat, dy: CGFloat, ended: Bool) {
        guard !locked, prepared else { return }
        if dragKind == nil {
            dragKind = kind
            switch kind {
            case .seek:
                dragStart = position
                dragTarget = position
                wasPlaying = playing
                dragging = true
                player.pause()
            case .brightness:
                let b = screen?.brightness ?? 0.5
                if savedBrightness == nil { savedBrightness = b }
                dragStart = Double(b)
            case .volume:
                dragStart = Double(volume >= 1 ? 1 + boostLevel : volume)
            }
        }
        switch kind {
        case .seek:
            let span = min(duration, 90)
            let target = min(max(0, dragStart + Double(dx) * span), duration)
            let delta = target - dragStart
            dragTarget = target
            position = target
            showHud(delta < 0 ? "kb_ui_ic_undo_rest" : "kb_ui_ic_redo_rest", VideoResume.clock(Int64(target * 1000)),
                    "[" + (delta < 0 ? "−" : "+") + VideoResume.clock(Int64(abs(delta) * 1000)) + "]",
                    level: duration > 0 ? target / duration : nil)
            if Date().timeIntervalSince(lastPreview) > 0.09 {
                lastPreview = Date()
                player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
            }
            if ended {
                dragging = false
                seek(to: dragTarget, exact: true)
                if wasPlaying { play() }
            }
        case .brightness:
            let b = min(1, max(0.01, dragStart - Double(dy) * 1.3))
            screen?.brightness = CGFloat(b)
            showHud("kb_ui_ic_sun_rest", "\(Int((b * 100).rounded())) %", L("video.brightness"), level: b)
        case .volume:
            // setVolumeLevel: 0..1 the player's volume, 1..2 the boost over it (up to +10 dB)
            let level = Float(min(2, max(0, dragStart - Double(dy) * 2.6)))
            let v = min(1, level)
            volume = v
            player.volume = v
            boostLevel = max(0, level - 1)
            boost.gain = powf(10, boostLevel * 10 / 20)
            if muted && level > 0 { muted = false; player.isMuted = false }
            showHud(level == 0 ? "kb_ui_ic_mute_rest" : "kb_ui_ic_sound_rest", "\(Int((level * 100).rounded())) %",
                    level > 1 ? L("video.boost") : L("video.volume"), level: Double(level / 2))
        }
        if ended { dragKind = nil }
    }

    func pinch(_ factor: CGFloat, delta: CGSize, ended: Bool) {
        guard !locked, prepared else { return }
        if ended {
            if abs(zoom - 1) < 0.06 {
                zoom = 1
                pan = .zero
            }
            return
        }
        zoom = min(4, max(0.5, zoom * factor))
        pan = CGSize(width: pan.width + delta.width, height: pan.height + delta.height)
        showHud("kb_ui_ic_search_rest", "\(Int((zoom * 100).rounded())) %", L("video.zoom"))
    }

    /// Held: 2x while playing (twice the speed from 2x up, at most 4x)
    func hold(_ on: Bool) {
        guard !locked, prepared else { return }
        if on {
            guard playing else { return }
            heldSpeed = speed
            let rate: Float = speed >= 2 ? min(4, speed * 2) : 2
            player.rate = rate
            showHud("kb_ui_ic_redo_rest", Self.speedLabel(rate), L("video.while_held"))
        } else if heldSpeed != nil {
            heldSpeed = nil
            if playing { player.rate = speed }
            hudTask?.cancel()
            hudTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 200_000_000)
                if !Task.isCancelled { self?.hud = nil }
            }
        }
    }

    private var screen: UIScreen? { (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.screen }

    private func restoreBrightness() {
        if let b = savedBrightness { screen?.brightness = b }
        savedBrightness = nil
    }

    // ---------------------------------------------------------------- the HUD, the pill, the controls' timer

    func showHud(_ icon: String, _ title: String, _ detail: String? = nil, level: Double? = nil) {
        hud = Hud(icon: icon, title: title, detail: detail, level: level)
        hudTask?.cancel()
        hudTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 900_000_000)
            if !Task.isCancelled { self?.hud = nil }
        }
    }

    func showPill(_ text: String, action: String? = nil) {
        pill = Pill(text: text, action: action)
        pillTask?.cancel()
        pillTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: action != nil ? 6_000_000_000 : 3_500_000_000)
            if !Task.isCancelled { self?.pill = nil }
        }
    }

    /// The pill's button («С начала»)
    func pillAction() {
        seek(to: 0, exact: true)
        pill = nil
    }

    func scheduleHide() {
        hideTask?.cancel()
        guard controls, playing, !panelOpen, !dragging else { return }
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            guard let self, !Task.isCancelled, self.playing, !self.panelOpen, !self.dragging else { return }
            withAnimation(.easeOut(duration: 0.2)) { self.controls = false }
        }
    }

    // ---------------------------------------------------------------- screenshot, details, delete

    /// The frame shown as a JPEG in the «SCAMERA» album («<video>_<ms>.jpg»)
    func screenshot() {
        guard let asset else { return }
        let time = player.currentTime()
        let name = (title.isEmpty ? "SCAMERA" : title) + "_\(Int64(max(0, time.seconds) * 1000)).jpg"
        Task { [weak self] in
            let gen = AVAssetImageGenerator(asset: asset)
            gen.appliesPreferredTrackTransform = true
            gen.requestedTimeToleranceBefore = .zero
            gen.requestedTimeToleranceAfter = .zero
            do {
                let shot = try await gen.image(at: time)
                guard let data = UIImage(cgImage: shot.image).jpegData(compressionQuality: 0.95) else { throw CameraError.setupFailed }
                try await Self.saveScreenshot(data, name: name)
                self?.showPill(L("video.shot_saved"))
            } catch {
                SessionLog.w("VideoPlayer", "screenshot: \(error.localizedDescription)")
                self?.showPill(L("video.shot_failed"))
            }
        }
    }

    nonisolated static func saveScreenshot(_ jpeg: Data, name: String) async throws {
        let album = GalleryLibrary.album()
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = name
            options.uniformTypeIdentifier = "public.jpeg"
            request.addResource(with: .photo, data: jpeg, options: options)
            if let placeholder = request.placeholderForCreatedAsset {
                if let album {
                    PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
                } else {
                    PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: GalleryLibrary.albumTitle)
                        .addAssets([placeholder] as NSArray)
                }
            }
        }
    }

    /// «Сведения о видео»: the file, its size, the date, the length, the picture (size, frame rate, codec), the bit rate, the sound
    func details() async -> [(String, String)] {
        guard let a = current else { return [] }
        var rows: [(String, String)] = []
        let res = GalleryLibrary.videoResource(a)
        if let name = res?.originalFilename, !name.isEmpty { rows.append((L("video.d.file"), name)) }
        var bytes: Int64 = 0
        if let r = res, let size = r.value(forKey: "fileSize") as? NSNumber { bytes = size.int64Value }
        if bytes > 0 { rows.append((L("video.d.size"), ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))) }
        if let d = a.creationDate { rows.append((L("video.d.date"), d.formatted(date: .long, time: .shortened))) }
        if duration > 0 { rows.append((L("video.d.length"), VideoResume.clock(Int64(duration * 1000)))) }
        guard let asset else { return rows }
        if let track = try? await asset.loadTracks(withMediaType: .video).first {
            var s = "\(Int(videoSize.width))×\(Int(videoSize.height))"
            if let fps = try? await track.load(.nominalFrameRate), fps > 0 { s += " · " + String(format: "%.0f", fps) + " " + L("video.d.fps") }
            if let f = (try? await track.load(.formatDescriptions))?.first { s += " · " + Self.codecName(CMFormatDescriptionGetMediaSubType(f)) }
            rows.append((L("video.d.video"), s))
            if bytes > 0, duration > 0 {
                rows.append((L("video.d.bitrate"), String(format: "%.1f", Double(bytes) * 8 / duration / 1_000_000) + " " + L("video.d.mbps")))
            }
        }
        if let tracks = try? await asset.loadTracks(withMediaType: .audio) {
            for (i, t) in tracks.enumerated() {
                var s = ""
                if let f = (try? await t.load(.formatDescriptions))?.first {
                    s = Self.codecName(CMFormatDescriptionGetMediaSubType(f))
                    if let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(f)?.pointee {
                        if asbd.mSampleRate > 0 { s += " · " + String(format: "%.1f", asbd.mSampleRate / 1000) + " " + L("video.d.khz") }
                        if asbd.mChannelsPerFrame == 1 { s += " · " + L("video.d.mono") }
                        else if asbd.mChannelsPerFrame == 2 { s += " · " + L("video.d.stereo") }
                        else if asbd.mChannelsPerFrame > 2 { s += " · \(asbd.mChannelsPerFrame) " + L("video.d.channels") }
                    }
                }
                rows.append((L("video.d.sound") + " \(i + 1)", s))
            }
        }
        return rows
    }

    static func codecName(_ code: FourCharCode) -> String {
        switch code.fourCC {
        case "avc1", "avc3": return "H.264"
        case "hvc1", "hev1": return "HEVC"
        case "mp4a", "aac ": return "AAC"
        case "apcn", "apch", "apcs", "apco", "ap4h", "ap4x": return "ProRes"
        case "lpcm": return "PCM"
        default: return code.fourCC
        }
    }

    /// The video gone from the library: the next one, or none left
    func removeCurrent() -> Bool {
        guard items.indices.contains(index) else { return false }
        player.pause()
        player.replaceCurrentItem(with: nil)
        prepared = false
        items.remove(at: index)
        if items.isEmpty { return false }
        load(min(index, items.count - 1))
        return true
    }
}
