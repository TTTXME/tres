import AVFoundation

/// Shutter and self-timer sounds (Android CameraSounds, P16 / P319): the shutter's sound_shutter.mp3 once per shot at the press, the
/// timer's sound_timer.mp3 from the countdown's start, cut at its end or when it is cancelled. «Звук затвора» (pref_camera_sounds_key)
/// and «Звук таймера» (pref_timer_sound_key), default on, each on its own («Звуки», pref_camera_sounds_all, writes both). They play
/// in the ambient category, so the ring / silent switch silences them as Android's ringer mode does. The system's own capture
/// sound is suppressed where the iPhone allows it (iOS 18, AVCapturePhotoOutput.isShutterSoundSuppressionSupported); where it
/// does not (the region requires it), the system's sound is the shutter sound and this one stays silent - Android's regionForces.
@MainActor
final class CameraSounds {
    static let shared = CameraSounds()
    private var shutter: AVAudioPlayer?
    private var timer: AVAudioPlayer?
    private var loaded = false
    private var logged = false

    private func load() {
        guard !loaded else { return }
        loaded = true
        try? AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        shutter = Bundle.main.url(forResource: "sound_shutter", withExtension: "mp3").flatMap { try? AVAudioPlayer(contentsOf: $0) }
        timer = Bundle.main.url(forResource: "sound_timer", withExtension: "mp3").flatMap { try? AVAudioPlayer(contentsOf: $0) }
        shutter?.prepareToPlay()
        timer?.prepareToPlay()
    }

    static var shutterOn: Bool { SettingsStore.shared.bool("pref_camera_sounds_key", true) }
    static var timerOn: Bool { SettingsStore.shared.bool("pref_timer_sound_key", true) }

    /// One shutter sound for the shot that starts now; `systemPlays`: the system's capture sound cannot be suppressed here.
    func shutter(systemPlays: Bool) {
        let on = Self.shutterOn
        if !logged {
            logged = true
            SessionLog.i("CameraSounds", "shutter sound: switch \(on ? "on" : "off"), the system's sound "
                         + (systemPlays ? "required (not suppressible)" : "suppressed") + " -> "
                         + (systemPlays ? "the system's" : on ? "played" : "silent"))
        }
        guard on, !systemPlays else { return }
        load()
        shutter?.currentTime = 0
        shutter?.play()
    }

    /// The countdown sound, from its start.
    func timerStart() {
        guard Self.timerOn else { return }
        load()
        timer?.currentTime = 0
        timer?.play()
    }

    /// Cut the countdown sound (the timer finished or was cancelled).
    func timerStop() {
        guard let timer, timer.isPlaying else { return }
        timer.stop()
        timer.currentTime = 0
    }
}
