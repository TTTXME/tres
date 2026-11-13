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
            let state = try await captureService.start(preferredModule: defaults.string(forKey: "module_active"),
                                                       fullResolution: fullResolution)
            apply(state)
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

    func capture() async {
        guard activity == .idle, status == .running else { return }
        activity = .capturing
        defer { activity = .idle }
        let started = Date()
        if shdr {
            await captureShdr(started: started)
            return
        }
        do {
            var photo = try await captureService.capturePhoto(PhotoRequest(raw: raw, fullResolution: fullResolution))
            photo.report.forEach { SessionLog.i("Shot", $0) }
            activity = .saving
            if whyTone, let dng = photo.rawFile {
                photo = await Self.render(photo, dng: dng)
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
    private func captureShdr(started: Date) async {
        do {
            let burst = try await captureService.captureBayerBurst(count: burstFrames)
            activity = .saving
            let result: (heic: Data?, log: [String], error: String?) = await Task.detached(priority: .userInitiated) {
                let metadata = burst.processed.flatMap { CGImageSourceCreateWithData($0 as CFData, nil) }
                    .flatMap { CGImageSourceCopyPropertiesAtIndex($0, 0, nil) as? [String: Any] } ?? [:]
                do {
                    let r = try BurstProcessor.process(burst, metadata: metadata)
                    return (r.heic, r.log, nil)
                } catch {
                    return (nil, burst.report, error.localizedDescription)
                }
            }.value
            result.log.forEach { SessionLog.i("SHDR", $0) }
            var rawFile: URL?
            if !burst.dng.isEmpty {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("SCAM_\(UUID().uuidString).dng")
                if (try? burst.dng.write(to: url)) != nil { rawFile = url }
            }
            if let error = result.error {
                SessionLog.e("SHDR", "processing failed: \(error)")
                show(error)
            }
            let heic = result.heic ?? burst.processed
            if let heic { thumbnail = MediaLibrary.thumbnail(of: heic) }
            try await mediaLibrary.save(CapturedPhoto(processed: heic, processedUTI: UTType.heic.identifier, rawFile: rawFile, report: []))
            SessionLog.i("SHDR", "saved in \(Int(Date().timeIntervalSince(started) * 1000)) ms, \(memoryFootprint())")
        } catch {
            SessionLog.e("SHDR", "failed: \(error.localizedDescription)")
            show(error.localizedDescription)
        }
    }

    /// GPU processing off the main actor; on failure the camera's own HEIC is kept.
    nonisolated private static func render(_ photo: CapturedPhoto, dng: URL) async -> CapturedPhoto {
        await Task.detached(priority: .userInitiated) {
            var result = photo
            let source = photo.processed.flatMap { CGImageSourceCreateWithData($0 as CFData, nil) }
                ?? CGImageSourceCreateWithURL(dng as CFURL, nil)
            let metadata = source.flatMap { CGImageSourceCopyPropertiesAtIndex($0, 0, nil) as? [String: Any] } ?? [:]
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

    func focus(at point: CGPoint) async {
        await captureService.focusAndExpose(atLayerPoint: point)
    }

    func clearMessage() { message = nil }

    private func apply(_ state: CaptureService.State) {
        modules = state.modules
        activeModuleID = state.active?.id
        supportsFullResolution = state.supportsFullResolution
    }

    private func show(_ text: String) {
        message = text
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
