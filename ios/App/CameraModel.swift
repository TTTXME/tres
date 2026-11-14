import AVFoundation
import ImageIO
import os
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Observable state of the camera screen (AVCam CameraModel). Every AVFoundation call goes through CaptureService.
@MainActor
@Observable
final class CameraModel {
    private(set) var status = CameraStatus.unknown
    private(set) var activity = CaptureActivity.idle
    private(set) var modules: [LensModule] = []
    private(set) var activeModuleID: String?
    private(set) var supportsFullResolution = false
    private(set) var thumbnail: CGImage?
    private(set) var message: String?
    private(set) var raw: RawChoice
    private(set) var fullResolution: Bool
    /// «Тон Why» V2 on the GPU for shots with RAW (the HEIC is rendered from the DNG).
    private(set) var whyTone: Bool
    /// SHDR: a Bayer series merged by the Android merge (ScamGL), then «Why» V2.
    private(set) var shdr: Bool
    /// The open lens (CaptureService.State): flash unit, torch, side, the ranges of «Про» and the exposure sun
    private(set) var lens = CaptureService.State(modules: [], active: nil, supportsFullResolution: false)
    /// The shown zoom (ZoomController.zoom: the module's ratio times the residual crop)
    private(set) var zoom = 1.0
    /// SIMPLE_UI §1: focus and exposure held by a long press («Закреплено»)
    private(set) var locked = false
    private var lastSwitch = Date.distantPast
    /// «Кадров для склейки» (pref_scam_hybrid_zsl_frames, Android default 20, 4–65)
    var burstFrames: Int {
        let stored = Int(SettingsStore.shared.string("pref_scam_hybrid_zsl_frames", "20")) ?? 20
        return min(max(stored, 4), 65)
    }

    private let captureService = CaptureService()
    private let mediaLibrary = MediaLibrary()
    private let defaults = UserDefaults.standard

    nonisolated var previewSource: PreviewSource { captureService.previewSource }

    init() {
        raw = RawChoice(rawValue: UserDefaults.standard.string(forKey: "raw_choice") ?? "") ?? .off
        fullResolution = UserDefaults.standard.object(forKey: "full_resolution") as? Bool ?? true
        whyTone = UserDefaults.standard.object(forKey: "why_tone") as? Bool ?? true
        shdr = UserDefaults.standard.object(forKey: "shdr") as? Bool ?? true
    }

    func start() async {
        SessionLog.i("App", "start, RAW \(raw.rawValue), full resolution \(fullResolution)")
        guard await captureService.isAuthorized else {
            status = .unauthorized
            SessionLog.w("App", "camera access denied")
            return
        }
        do {
            _ = try await captureService.start(preferredModule: defaults.string(forKey: "module_active"),
                                               fullResolution: fullResolution)
            // the stored flash mode and «Про» values on the opened lens
            apply(await captureService.setFlash(flashMode))
            await captureService.applyManual(ManualSettings.load())
            status = .running
            observeInterruptions()
        } catch {
            status = .failed(error.localizedDescription)
            SessionLog.e("App", "start failed: \(error.localizedDescription)")
        }
    }

    func select(_ module: LensModule) async {
        guard module.id != activeModuleID, activity == .idle else { return }
        do {
            let previous = activeModuleID
            apply(try await captureService.select(moduleID: module.id))
            ModuleProfiles.switched(from: previous, to: module.id)   // «Свои настройки для каждого модуля»
            await captureService.applyManual(ManualSettings.load())
            defaults.set(module.id, forKey: "module_active")
        } catch {
            show(error.localizedDescription)
        }
    }

    /// The settings closed: the modules' names, ratios, buttons and copies as the settings now have them (ModuleSettings)
    func reloadModules() async {
        guard status == .running else { return }
        apply(await captureService.reloadModules())
    }

    func setRaw(_ choice: RawChoice) {
        raw = choice
        defaults.set(choice.rawValue, forKey: "raw_choice")
        SessionLog.i("App", "RAW \(choice.rawValue)")
    }

    func setFullResolution(_ enabled: Bool) async {
        fullResolution = enabled
        defaults.set(enabled, forKey: "full_resolution")
        apply(await captureService.setFullResolution(enabled))
    }

    func setWhyTone(_ enabled: Bool) {
        whyTone = enabled
        defaults.set(enabled, forKey: "why_tone")
        SessionLog.i("App", "Why tone \(enabled)")
    }

    func setShdr(_ enabled: Bool) {
        shdr = enabled
        defaults.set(enabled, forKey: "shdr")
        SessionLog.i("App", "SHDR \(enabled)")
    }

    /// pref_ae_mode_key (FlashMode, default 1 = off)
    var flashMode: FlashMode { FlashMode.normalize(Int(SettingsStore.shared.string(FlashMode.key, "1")) ?? 1) }
    /// The mode the top row shows: as the open lens allows it (FlashMode.shown)
    var flashShown: FlashMode { FlashMode.effective(flashMode, flashUnit: lens.hasFlash) }
    /// P507: the front lens's torch is the screen's light
    var screenLight: Bool { lens.isFront && flashShown == .torch }

    /// The top row's flash: the next mode of the cycle (FlashMode.tap), a toast with its name.
    func cycleFlash() async {
        let mode = FlashMode.next(flashMode, flashUnit: lens.hasFlash)
        SettingsStore.shared.set(FlashMode.key, String(mode.rawValue))
        apply(await captureService.setFlash(mode))
        say(mode.label)
    }

    /// A short note on the camera screen's message card (UiMessages.info)
    func say(_ text: String) { MessageCenter.shared.info(text) }

    /// The modules of the open lens's side, by their ratio (ZoomController.lenses)
    var sideModules: [LensModule] {
        let front = modules.first(where: { $0.id == activeModuleID })?.isFront ?? false
        return modules.filter { $0.isFront == front }.sorted { $0.zoom < $1.zoom }
    }

    /// The zoom range of the ruler and the pinch: the widest module to the longest one, times the digital crop the route keeps
    /// (SHDR merges the whole frame: its zoom stops at the modules)
    var zoomRange: ClosedRange<Double> {
        let side = sideModules
        let low = side.first?.zoom ?? 1, high = side.last?.zoom ?? 1
        return low...max(low, high * lens.maxResidual)
    }

    /// ZoomController.zoomTo: the module with the largest ratio not above the zoom (+0.05), the rest a centre crop of it (the
    /// camera's own photo honours it; the SHDR merge takes the whole frame, so with SHDR the zoom stays on the modules). A new
    /// module at most every 0.7 s (a session switch).
    func zoom(to value: Double) async {
        let side = sideModules
        guard !side.isEmpty, activity == .idle else { return }
        let target = min(max(value, zoomRange.lowerBound), zoomRange.upperBound)
        let pick = side.last(where: { $0.zoom <= target + 0.05 }) ?? side[0]
        if pick.id != activeModuleID {
            guard Date().timeIntervalSince(lastSwitch) >= 0.7 else { return }
            lastSwitch = Date()
            await select(pick)
        }
        let residual = max(1, target / pick.zoom) // SHDR too: SabreCrop's window (ShotRun)
        await captureService.setZoom(residual: residual)
        zoom = pick.zoom * residual
    }

    /// TouchFocus.lockAt: focus and exposure held at the point (a manual focus takes no tap focus)
    func lock(at point: CGPoint) async {
        guard !ManualSettings.load().focusOn else { return }
        locked = true
        await captureService.lockFocusAndExposure(atLayerPoint: point)
    }

    /// A tap on the ring: back to the camera's own focus and metering
    func resetFocus() async {
        locked = false
        await captureService.resetFocusAndExposure()
    }

    /// The camera mode changed (OldstyleMode.select): «Спорт»'s shutter ceiling on the lens
    func modeChanged() async {
        await captureService.applyMode()
    }

    /// The exposure sun: the Pro panel's EV (one state)
    func setEv(_ ev: Double) async {
        var m = ManualSettings.load()
        m.bias = ev
        m.save()
        await captureService.applyManual(m)
    }

    /// The top row's format badge: «Формат фото» and the size of the open lens (CameraFragment's format / size badges).
    var formatBadge: String {
        let photo = SettingsStore.shared.string("pref_photo_format", "jpeg") == "heic" ? "HEIC" : "JPEG"
        return raw == .off ? photo : photo + "+RAW"
    }
    var sizeBadge: String { fullResolution && supportsFullResolution ? "48M" : "12M" }

    /// flip_camera_button: the other side's first lens
    func flip() async {
        guard let active = modules.first(where: { $0.id == activeModuleID }),
              let other = modules.first(where: { $0.isFront != active.isFront }) else { return }
        await select(other)
    }

    func applyManual(_ m: ManualSettings) async {
        await captureService.applyManual(m)
    }

    // MARK: the shot queue (CaptureController: SCAM_PROCESSING, sScamPending, mScamQueuedShots)

    /// A shot handed to processing (its frames captured): waiting behind the shot before it, merged and processed, or saved.
    struct QueuedShot: Identifiable, Equatable {
        enum Stage: Equatable { case waiting, processing, saving }
        let id: UUID
        let pressed: Date
        var stage: Stage
        /// what it holds in memory until it is processed (its frames, DNG and HEIC)
        let bytes: Int
    }

    /// The shots handed to processing and not saved yet, oldest first (sScamPending): the first is processed, the others wait.
    /// One at a time (SCAM_PROCESSING is one thread; SCAMShot's process lock as well), while the camera takes the next shot.
    private(set) var shots: [QueuedShot] = []
    /// Presses kept while a burst is being captured (mScamQueuedShots), fired one by one when it is handed to processing.
    private(set) var queuedPresses = 0
    /// CaptureController.takePicture: at most this many presses are kept behind a capture.
    static let maxQueuedPresses = 4
    /// scamQueueMemoryAvailable: the memory left free beside the next burst for a press while a shot is processed (1.5 GB)
    static let queueMemoryMargin = 1536 * 1_048_576
    /// The camera captures or a shot is still processed (the gallery button's spinner).
    var busy: Bool { activity != .idle || !shots.isEmpty }
    /// The shutter answers a press (the press itself is then taken, kept behind the capture or refused with a note).
    var shutterAvailable: Bool { status == .running }
    private var processingTail: Task<Void, Never>?
    private var capturingShdr = false
    /// one frame of the last SHDR burst (bytes), for the next burst's size
    private var lastFrameBytes = 12_000_000 * 2

    /// The memory a burst of the shot about to be pressed takes (the last burst's frame size x «Кадров для склейки» + its extras).
    private var nextBurstBytes: Int { lastFrameBytes * (burstFrames + 4) }

    /// CaptureController.scamQueueMemoryAvailable: a press while shots are processed only when the next burst fits beside the
    /// queue with the margin (os_proc_available_memory: what this process may still take)
    private func queueMemoryAvailable() -> Bool {
        let available = Int(os_proc_available_memory())
        let held = shots.reduce(0) { $0 + $1.bytes }
        let ok = available <= 0 || available > nextBurstBytes + Self.queueMemoryMargin
        SessionLog.i("Shot", "queue: \(shots.count) shot(s) processing, \(held / 1_048_576) MB held, available \(available / 1_048_576) MB, "
                     + "next burst ~\(nextBurstBytes / 1_048_576) MB + margin \(Self.queueMemoryMargin / 1_048_576) MB: "
                     + (ok ? "the press is taken" : "refused") + ", \(memoryFootprint())")
        return ok
    }

    /// The shutter (CaptureController.takePicture): taken when the camera is free (also while earlier shots are processed, when the
    /// memory allows it), kept behind an SHDR burst being captured (at most four presses), otherwise refused with a note.
    func capture() async {
        guard status == .running else { return }
        if activity != .idle {
            if activity == .capturing && capturingShdr && queuedPresses < Self.maxQueuedPresses {
                queuedPresses += 1
                SessionLog.i("Shot", "shutter queued behind current capture pending=\(queuedPresses)")
                return
            }
            say(L("capture.busy"))
            return
        }
        if !shots.isEmpty && !queueMemoryAvailable() {
            queuedPresses = 0
            say(L("capture.busy"))
            return
        }
        activity = .capturing
        await shoot()
        activity = .idle
        capturingShdr = false
        // «shutter free»: a press kept behind the capture fires now
        if queuedPresses > 0 {
            queuedPresses -= 1
            SessionLog.i("Shot", "queued shutter fires, \(queuedPresses) more kept")
            Task { await capture() }
        }
    }

    private func shoot() async {
        let started = Date()
        // one shutter sound for the shot at the press (CameraSounds; the system's own where it cannot be silenced)
        CameraSounds.shared.shutter(systemPlays: !lens.suppressesShutterSound)
        let facts = await captureService.exifFacts(time: started)
        // FlashMode.flashShot: On, or Auto when the scene asks for it
        let flash = await captureService.flashShot
        if flash { SessionLog.i("Shot", "flash shot (\(flashShown.name))") }
        if shdr {
            await captureShdr(started: started, facts: facts, flash: flash)
            return
        }
        do {
            var photo = try await captureService.capturePhoto(PhotoRequest(raw: raw, fullResolution: fullResolution,
                                                                           flash: flash ? .on : .off, exif: facts))
            photo.report.forEach { SessionLog.i("Shot", $0) }
            activity = .saving
            if whyTone, let dng = photo.rawFile {
                photo = await Self.render(photo, dng: dng, facts: facts)
            }
            if let data = photo.processed { thumbnail = MediaLibrary.thumbnail(of: data) }
            try await mediaLibrary.save(photo)
            SessionLog.i("Shot", "saved in \(Int(Date().timeIntervalSince(started) * 1000)) ms, \(memoryFootprint())")
        } catch {
            SessionLog.e("Shot", "failed: \(error.localizedDescription)")
            show(error.localizedDescription)
        }
    }

    /// SHDR: Bayer series → NCH → the Android merge on the GPU → white balance / colour → «Why» V2 → HEIC (+ the merged DNG).
    /// A flash shot is one fresh frame with the flash (FlashMode.Shot), merged as a one-frame shot. The capture ends when the frames
    /// are handed to the processing queue: the camera takes the next shot while this one is merged, processed and saved.
    private func captureShdr(started: Date, facts: ShotExif, flash: Bool) async {
        capturingShdr = true
        do {
            let burst: BayerBurst
            if flash, let one = try await captureService.captureFlashFrame() {
                burst = one
            } else if flash {
                // no RAW frame with the flash on this lens: the camera's own flash photo
                SessionLog.w("SHDR", "flash shot without a RAW frame: the camera's photo")
                let photo = try await captureService.capturePhoto(PhotoRequest(raw: raw, fullResolution: fullResolution, flash: .on, exif: facts))
                photo.report.forEach { SessionLog.i("Shot", $0) }
                activity = .saving
                if let data = photo.processed { thumbnail = MediaLibrary.thumbnail(of: data) }
                try await mediaLibrary.save(photo)
                return
            } else {
                burst = try await captureService.captureBayerBurst(count: burstFrames)
            }
            enqueue(burst, started: started, facts: facts)
        } catch {
            SessionLog.e("SHDR", "failed: \(error.localizedDescription)")
            show(error.localizedDescription)
        }
    }

    /// The burst into the processing queue (CaptureController: «queued for processing pending=N»); processed after the shots before it.
    private func enqueue(_ burst: BayerBurst, started: Date, facts: ShotExif) {
        let frameBytes = burst.frames.first.map { $0.raw.count } ?? 0
        if frameBytes > 0 { lastFrameBytes = frameBytes }
        let bytes = burst.frames.reduce(0) { $0 + $1.raw.count } + burst.dng.count + (burst.processed?.count ?? 0)
        let shot = QueuedShot(id: UUID(), pressed: started, stage: .waiting, bytes: bytes)
        shots.append(shot)
        SessionLog.i("SHDR", "queued for processing pending=\(shots.count), burst \(bytes / 1_048_576) MB "
                     + "(\(burst.frames.count) frames of \(frameBytes / 1_048_576) MB), \(memoryFootprint())")
        // the RAW saving of the press (ScamProcessor.configure at the shot)
        let wantDng = raw != .off
        let previous = processingTail
        processingTail = Task { [weak self] in
            await previous?.value
            await self?.process(shot.id, burst: burst, started: started, facts: facts, wantDng: wantDng)
        }
    }

    private func setStage(_ id: UUID, _ stage: QueuedShot.Stage) {
        if let i = shots.firstIndex(where: { $0.id == id }) { shots[i].stage = stage }
    }

    /// One queued shot: merged and processed off the main actor, its DNG and photo saved; a failure is said (processingFailed) and
    /// the queue goes on. The app may go to the background meanwhile (ProcessingKeepAlive: as long as iOS gives it time).
    private func process(_ id: UUID, burst: BayerBurst, started: Date, facts: ShotExif, wantDng: Bool) async {
        let keepAlive = BackgroundTime()
        keepAlive.id = UIApplication.shared.beginBackgroundTask(withName: "SCAMERA shot") { MainActor.assumeIsolated { keepAlive.end() } }
        defer {
            shots.removeAll { $0.id == id }
            keepAlive.end()
        }
        setStage(id, .processing)
        do {
            // ShotExif / ParseExif.syncWithParameters + describeFilm: the frames merged, the camera, the film's recipe, the route
            var described = facts
            described.frames = burst.frames.count
            described.merged = true
            let base = described
            let result: (heic: Data?, uti: String, log: [String], error: String?, mergedDng: Data?) = await Task.detached(priority: .userInitiated) {
                var shot = base
                shot.filmRecipe = ViewfinderNative.filmRecipe()
                let camera = burst.processed.flatMap { CGImageSourceCreateWithData($0 as CFData, nil) }
                    .flatMap { CGImageSourceCopyPropertiesAtIndex($0, 0, nil) as? [String: Any] } ?? [:]
                var metadata = shot.apply(to: camera)
                // the merged photo is sRGB (the pipeline's output)
                if var exif = metadata[kCGImagePropertyExifDictionary as String] as? [String: Any] {
                    exif[kCGImagePropertyExifColorSpace as String] = 1
                    metadata[kCGImagePropertyExifDictionary as String] = exif
                }
                do {
                    // P165: a merged DNG when RAW is saved (ScamProcessor: rawSaving >= 1)
                    let r = try BurstProcessor.process(burst, metadata: metadata, zoom: burst.zoom,
                                                       dngFacts: wantDng ? BurstProcessor.dngFacts(metadata: metadata, shot: shot) : nil)
                    return (r.heic, r.uti, r.log, nil, r.dng)
                } catch {
                    return (nil, UTType.heic.identifier, burst.report, error.localizedDescription, nil)
                }
            }.value
            setStage(id, .saving)
            result.log.forEach { SessionLog.i("SHDR", $0) }
            var rawFile: URL?
            if wantDng && !burst.dng.isEmpty {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("SCAM_\(UUID().uuidString).dng")
                // P165: the merged DNG (its preview and develop record in it); the camera's DNG of the reference frame only when
                // the merge gave none
                let dng = result.mergedDng ?? burst.dng
                SessionLog.i("SHDR", result.mergedDng != nil ? "DNG: the merged burst RAW, \(dng.count) bytes"
                                                           : "DNG: the reference frame's (the camera's), \(dng.count) bytes")
                if (try? dng.write(to: url)) != nil { rawFile = url }
            }
            if let error = result.error {
                SessionLog.e("SHDR", "processing failed: \(error)")
                show(error)
            }
            let heic = result.heic ?? burst.processed
            if let heic { thumbnail = MediaLibrary.thumbnail(of: heic) }
            try await mediaLibrary.save(CapturedPhoto(processed: heic, processedUTI: result.heic != nil ? result.uti : UTType.heic.identifier,
                                                      rawFile: rawFile, report: []))
            SessionLog.i("SHDR", "saved in \(Int(Date().timeIntervalSince(started) * 1000)) ms, \(shots.count - 1) more queued, \(memoryFootprint())")
        } catch {
            SessionLog.e("SHDR", "failed: \(error.localizedDescription)")
            show(error.localizedDescription)
        }
    }

    /// GPU processing off the main actor; on failure the camera's own HEIC is kept.
    nonisolated private static func render(_ photo: CapturedPhoto, dng: URL, facts: ShotExif) async -> CapturedPhoto {
        await Task.detached(priority: .userInitiated) {
            var result = photo
            let source = photo.processed.flatMap { CGImageSourceCreateWithData($0 as CFData, nil) }
                ?? CGImageSourceCreateWithURL(dng as CFURL, nil)
            let metadata = facts.apply(to: source.flatMap { CGImageSourceCopyPropertiesAtIndex($0, 0, nil) as? [String: Any] } ?? [:])
            do {
                let processed = try ShotProcessor.process(dng: dng, metadata: metadata)
                processed.log.forEach { SessionLog.i("Engine", $0) }
                result.processed = processed.heic
                result.processedUTI = UTType.heic.identifier
            } catch {
                SessionLog.e("Engine", "GPU processing failed, the camera's HEIC is kept: \(error.localizedDescription)")
            }
            return result
        }.value
    }

    /// A tap focuses and meters there (TouchFocus.processTouchToFocus; a manual focus takes no tap focus); a held lock goes.
    func focus(at point: CGPoint) async {
        locked = false
        guard !ManualSettings.load().focusOn else { return }
        await captureService.focusAndExpose(atLayerPoint: point)
    }

    func clearMessage() { message = nil }

    /// SubjectFocus / «Раздельные фокус и экспозиция»: the camera-screen hooks of App/Camera/SubjectTracking
    func track(at point: CGPoint) async { locked = false; await captureService.track(atLayerPoint: point) }
    func expose(at point: CGPoint) async { await captureService.expose(atLayerPoint: point) }

    private func apply(_ state: CaptureService.State) {
        if state.active?.id != activeModuleID {
            zoom = state.active?.zoom ?? 1
            locked = false
        }
        modules = state.modules
        activeModuleID = state.active?.id
        supportsFullResolution = state.supportsFullResolution
        lens = state
    }

    private func show(_ text: String) {
        message = text
        MessageCenter.shared.error(text)
    }

    private func observeInterruptions() {
        Task {
            for await _ in NotificationCenter.default.notifications(named: AVCaptureSession.wasInterruptedNotification) {
                status = .interrupted
                SessionLog.w("App", "session interrupted")
            }
        }
        Task {
            for await _ in NotificationCenter.default.notifications(named: AVCaptureSession.interruptionEndedNotification) {
                status = .running
                SessionLog.i("App", "interruption ended")
            }
        }
    }
}

/// Resident memory of the app (phys_footprint), as the shot log on Android reports it.
/// A background task's time for a shot being processed (ended once, by the shot or when iOS takes the time back).
final class BackgroundTime: @unchecked Sendable {
    var id = UIBackgroundTaskIdentifier.invalid
    @MainActor func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}

func memoryFootprint() -> String {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    guard result == KERN_SUCCESS else { return "memory ?" }
    return "memory \(info.phys_footprint / 1_048_576) MB"
}
