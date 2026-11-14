import AVFoundation
import ImageIO
import SwiftUI
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
            apply(try await captureService.select(moduleID: module.id))
            await captureService.applyManual(ManualSettings.load())
            defaults.set(module.id, forKey: "module_active")
        } catch {
            show(error.localizedDescription)
        }
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

    func capture() async {
        guard activity == .idle, status == .running else { return }
        activity = .capturing
        defer { activity = .idle }
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

    /// SHDR: Bayer series → NCH → the Android merge on the GPU → white balance / colour → «Why» V2 → HEIC (+ the first DNG).
    /// A flash shot is one fresh frame with the flash (FlashMode.Shot), merged as a one-frame shot.
    private func captureShdr(started: Date, facts: ShotExif, flash: Bool) async {
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
            activity = .saving
            // ShotExif / ParseExif.syncWithParameters + describeFilm: the frames merged, the camera, the film's recipe, the route
            var described = facts
            described.frames = burst.frames.count
            described.merged = true
            let base = described
            let result: (heic: Data?, uti: String, log: [String], error: String?, develop: Data?) = await Task.detached(priority: .userInitiated) {
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
                    let r = try BurstProcessor.process(burst, metadata: metadata, zoom: burst.zoom)
                    return (r.heic, r.uti, r.log, nil, r.developRecord)
                } catch {
                    return (nil, UTType.heic.identifier, burst.report, error.localizedDescription, nil)
                }
            }.value
            result.log.forEach { SessionLog.i("SHDR", $0) }
            var rawFile: URL?
            if !burst.dng.isEmpty {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("SCAM_\(UUID().uuidString).dng")
                // P665: the gallery developer's record at the DNG's very end (DevelopRecord.append), after the DNG's own bytes
                var dng = burst.dng
                if let record = result.develop {
                    dng.append(record)
                    SessionLog.i("SHDR", "develop record appended to the DNG: \(record.count) bytes")
                }
                if (try? dng.write(to: url)) != nil { rawFile = url }
            }
            if let error = result.error {
                SessionLog.e("SHDR", "processing failed: \(error)")
                show(error)
            }
            let heic = result.heic ?? burst.processed
            if let heic { thumbnail = MediaLibrary.thumbnail(of: heic) }
            let saveRaw = raw != .off ? rawFile : nil
            try await mediaLibrary.save(CapturedPhoto(processed: heic, processedUTI: result.heic != nil ? result.uti : UTType.heic.identifier,
                                                      rawFile: saveRaw, report: []))
            SessionLog.i("SHDR", "saved in \(Int(Date().timeIntervalSince(started) * 1000)) ms, \(memoryFootprint())")
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
