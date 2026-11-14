import AVFoundation
import AVKit
import MediaPlayer
import MediaToolbox
import UIKit

/// VideoPlayerActivity's volume boost (LoudnessEnhancer, up to +10 dB over the full volume) on AVPlayer: an MTAudioProcessingTap
/// on the item's sound track (AVAudioMix) that multiplies the samples by the gain and clips them at full scale. The gain is read on
/// the audio thread from a box the player sets.
final class VideoBoost: @unchecked Sendable {
    /// linear gain (1: none); written on the main thread, read by the tap
    var gain: Float = 1
    /// whether the tap's samples are 32-bit floats (the tap's usual format); anything else passes unchanged
    var floats = false

    /// The audio mix that boosts `track` by `self.gain` (nil when the tap cannot be made).
    func mix(for track: AVAssetTrack) -> AVAudioMix? {
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: UnsafeMutableRawPointer(Unmanaged.passRetained(self).toOpaque()),
            init: boostInit, finalize: boostFinalize, prepare: boostPrepare, unprepare: nil, process: boostProcess)
        var tap: MTAudioProcessingTap?
        guard MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap) == noErr,
              let tap else {
            Unmanaged<VideoBoost>.fromOpaque(callbacks.clientInfo!).release()
            return nil
        }
        let params = AVMutableAudioMixInputParameters(track: track)
        params.audioTapProcessor = tap
        let mix = AVMutableAudioMix()
        mix.inputParameters = [params]
        return mix
    }
}

private func boostInit(_ tap: MTAudioProcessingTap, _ clientInfo: UnsafeMutableRawPointer?,
                       _ storageOut: UnsafeMutablePointer<UnsafeMutableRawPointer?>) {
    storageOut.pointee = clientInfo
}

private func boostFinalize(_ tap: MTAudioProcessingTap) {
    Unmanaged<VideoBoost>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
}

private func boostPrepare(_ tap: MTAudioProcessingTap, _ maxFrames: CMItemCount, _ format: UnsafePointer<AudioStreamBasicDescription>) {
    let box = Unmanaged<VideoBoost>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    let f = format.pointee
    box.floats = f.mFormatID == kAudioFormatLinearPCM && f.mFormatFlags & kAudioFormatFlagIsFloat != 0 && f.mBitsPerChannel == 32
}

private func boostProcess(_ tap: MTAudioProcessingTap, _ frames: CMItemCount, _ flags: MTAudioProcessingTapFlags,
                          _ buffers: UnsafeMutablePointer<AudioBufferList>, _ framesOut: UnsafeMutablePointer<CMItemCount>,
                          _ flagsOut: UnsafeMutablePointer<MTAudioProcessingTapFlags>) {
    guard MTAudioProcessingTapGetSourceAudio(tap, frames, buffers, flagsOut, nil, framesOut) == noErr else { return }
    let box = Unmanaged<VideoBoost>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    let g = box.gain
    guard box.floats, g > 1.001 else { return }
    for buffer in UnsafeMutableAudioBufferListPointer(buffers) {
        guard let data = buffer.mData else { continue }
        let n = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
        let samples = data.bindMemory(to: Float.self, capacity: n)
        for i in 0..<n { samples[i] = max(-1, min(1, samples[i] * g)) }
    }
}

/// VideoPlaybackService (P499) and the player's picture in picture on the iPhone: the playback audio session while the player is
/// open (the camera's own session back after it), the lock screen's now-playing card with play / pause (Android's notification
/// with «Пауза / Смотреть»), and AVPictureInPictureController over the player's layer («Картинка в картинке», on leaving the app
/// when «Картинка в картинке при выходе» is on).
@MainActor
final class VideoBackground: NSObject, AVPictureInPictureControllerDelegate {
    private var saved: (category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions)?
    private(set) var pip: AVPictureInPictureController?
    private var commands: [Any] = []
    var pipActive = false
    var onPipChange: (Bool) -> Void = { _ in }
    var onRestore: () -> Void = {}

    /// The playback session (needed for the sound in the background and for picture in picture).
    func activate() {
        let s = AVAudioSession.sharedInstance()
        if saved == nil { saved = (s.category, s.mode, s.categoryOptions) }
        do {
            try s.setCategory(.playback, mode: .moviePlayback, options: [])
            try s.setActive(true)
        } catch {
            SessionLog.w("VideoPlayer", "audio session: \(error.localizedDescription)")
        }
    }

    /// The camera's session as it was; the lock screen card and its commands gone.
    func deactivate() {
        clearNowPlaying()
        pip?.stopPictureInPicture()
        pip = nil
        guard let saved else { return }
        self.saved = nil
        let s = AVAudioSession.sharedInstance()
        try? s.setActive(false, options: .notifyOthersOnDeactivation)
        try? s.setCategory(saved.category, mode: saved.mode, options: saved.options)
    }

    // ---------------------------------------------------------------- picture in picture

    static var pipSupported: Bool { AVPictureInPictureController.isPictureInPictureSupported() }

    /// The player's layer: picture in picture over it (`automatic`: when the app is left while playing).
    func attach(_ layer: AVPlayerLayer, automatic: Bool) {
        guard Self.pipSupported else { return }
        if let pip, pip.playerLayer === layer {
            pip.canStartPictureInPictureAutomaticallyFromInline = automatic
            return
        }
        let c = AVPictureInPictureController(playerLayer: layer)
        c?.delegate = self
        c?.canStartPictureInPictureAutomaticallyFromInline = automatic
        pip = c
    }

    func setAutomatic(_ on: Bool) { pip?.canStartPictureInPictureAutomaticallyFromInline = on }

    /// false when picture in picture cannot start now
    func startPip() -> Bool {
        guard let pip, pip.isPictureInPicturePossible else { return false }
        pip.startPictureInPicture()
        return true
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            pipActive = true
            onPipChange(true)
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated {
            pipActive = false
            onPipChange(false)
        }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        MainActor.assumeIsolated { onRestore() }
        completionHandler(true)
    }

    // ---------------------------------------------------------------- the lock screen card

    func nowPlaying(title: String, duration: Double, position: Double, rate: Float) {
        var info: [String: Any] = [MPMediaItemPropertyTitle: title, MPNowPlayingInfoPropertyPlaybackRate: Double(rate),
                                   MPNowPlayingInfoPropertyElapsedPlaybackTime: position]
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// The card's play / pause and toggle (VideoPlaybackService's «Пауза / Смотреть»).
    func remote(play: @escaping @MainActor () -> Void, pause: @escaping @MainActor () -> Void, toggle: @escaping @MainActor () -> Void) {
        let c = MPRemoteCommandCenter.shared()
        removeCommands()
        commands.append(c.playCommand.addTarget { _ in Task { @MainActor in play() }; return .success })
        commands.append(c.pauseCommand.addTarget { _ in Task { @MainActor in pause() }; return .success })
        commands.append(c.togglePlayPauseCommand.addTarget { _ in Task { @MainActor in toggle() }; return .success })
    }

    private func removeCommands() {
        let c = MPRemoteCommandCenter.shared()
        for t in commands {
            c.playCommand.removeTarget(t)
            c.pauseCommand.removeTarget(t)
            c.togglePlayPauseCommand.removeTarget(t)
        }
        commands = []
    }

    private func clearNowPlaying() {
        removeCommands()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
