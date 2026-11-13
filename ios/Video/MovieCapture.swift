import AVFoundation

/// The video recording (Android CaptureController's MediaRecorder: VideoStart / VideoEnd, setUpMediaRecorder): the movie file
/// output and the microphone's input CaptureService adds in the video mode; the output's connection gets the H.264 codec, the
/// video stabilisation and the capture's horizon-level rotation (MediaRecorder.setOrientationHint), unmirrored as the recorder
/// writes the front sensor's picture. The recording's end (asked, or the system's: an interruption, a full disk) is kept until
/// `stop()` takes it.
final class MovieCapture: NSObject, AVCaptureFileOutputRecordingDelegate, @unchecked Sendable {
    let output = AVCaptureMovieFileOutput()
    /// The microphone's input while the video mode holds it
    var audioInput: AVCaptureDeviceInput?

    struct Finished: Sendable {
        let url: URL
        let ok: Bool
        let error: String?
        let seconds: Double
        let bytes: Int64
    }

    struct Progress: Sendable {
        var recording = false
        var seconds = 0.0
        var bytes: Int64 = 0
        /// The recording ended without a stop (the system's): `stop()` returns it at once
        var ended = false
    }

    private let lock = NSLock()
    private var active = false
    private var waiting: CheckedContinuation<Finished, Never>?
    private var ended: Finished?
    private var url: URL?

    /// The microphone (AAC from AudioSource.MIC) when the app may use it
    func makeAudioInput() -> AVCaptureDeviceInput? {
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized, let mic = AVCaptureDevice.default(for: .audio) else { return nil }
        return try? AVCaptureDeviceInput(device: mic)
    }

    /// The video connection for the next recording; returns what was set (the log)
    func configure(rotation: CGFloat, stabilization: AVCaptureVideoStabilizationMode) -> [String] {
        guard let connection = output.connection(with: .video) else { return ["no video connection"] }
        var notes: [String] = []
        if output.availableVideoCodecTypes.contains(.h264) {
            output.setOutputSettings([AVVideoCodecKey: AVVideoCodecType.h264], for: connection)
            notes.append("codec h264")
        } else {
            notes.append("codec \(output.availableVideoCodecTypes.first?.rawValue ?? "?") (no h264)")
        }
        if connection.isVideoStabilizationSupported {
            connection.preferredVideoStabilizationMode = stabilization
            notes.append("stabilisation \(VideoSettings.stabilizationName(stabilization))")
        }
        if connection.isVideoRotationAngleSupported(rotation) {
            connection.videoRotationAngle = rotation
            notes.append("rotation \(Int(rotation))")
        }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
        notes.append("audio \(audioInput != nil ? "mic" : "none")")
        return notes
    }

    func start(to url: URL) {
        lock.lock()
        active = true
        ended = nil
        self.url = url
        lock.unlock()
        output.startRecording(to: url, recordingDelegate: self)
    }

    var progress: Progress {
        lock.lock()
        let hasEnded = ended != nil
        let isActive = active
        lock.unlock()
        var p = Progress()
        p.recording = isActive && output.isRecording
        p.seconds = output.recordedDuration.seconds.isFinite ? output.recordedDuration.seconds : 0
        p.bytes = output.recordedFileSize
        p.ended = hasEnded
        return p
    }

    /// Ends the recording and returns its file (a recording the system ended already: that one)
    func stop() async -> Finished {
        await withCheckedContinuation { (c: CheckedContinuation<Finished, Never>) in
            lock.lock()
            if let done = ended {
                ended = nil
                lock.unlock()
                c.resume(returning: done)
                return
            }
            guard active else {
                let u = url ?? VideoSettings.newFileURL()
                lock.unlock()
                c.resume(returning: Finished(url: u, ok: false, error: "not recording", seconds: 0, bytes: 0))
                return
            }
            waiting = c
            lock.unlock()
            output.stopRecording()
        }
    }

    // MARK: AVCaptureFileOutputRecordingDelegate

    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
        SessionLog.i("Video", "recording started: \(fileURL.lastPathComponent)")
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection],
                    error: Error?) {
        // an error can still leave a whole file (AVErrorRecordingSuccessfullyFinishedKey: a full disk, the duration limit, an
        // interruption)
        var ok = error == nil
        if let error, ((error as NSError).userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool) == true { ok = true }
        let done = Finished(url: outputFileURL, ok: ok, error: error?.localizedDescription,
                            seconds: output.recordedDuration.seconds.isFinite ? output.recordedDuration.seconds : 0,
                            bytes: output.recordedFileSize)
        SessionLog.i("Video", "recording finished: ok \(ok), \(String(format: "%.1f", done.seconds)) s, \(done.bytes) bytes"
                     + (error.map { ", \($0.localizedDescription)" } ?? ""))
        lock.lock()
        active = false
        if let c = waiting {
            waiting = nil
            lock.unlock()
            c.resume(returning: done)
        } else {
            ended = done
            lock.unlock()
        }
    }
}
