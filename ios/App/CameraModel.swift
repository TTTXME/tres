import AVFoundation
import SwiftUI

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

    private let captureService = CaptureService()
    private let mediaLibrary = MediaLibrary()
    private let defaults = UserDefaults.standard

    nonisolated var previewSource: PreviewSource { captureService.previewSource }

    init() {
        raw = RawChoice(rawValue: UserDefaults.standard.string(forKey: "raw_choice") ?? "") ?? .off
        fullResolution = UserDefaults.standard.object(forKey: "full_resolution") as? Bool ?? true
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

    func capture() async {
        guard activity == .idle, status == .running else { return }
        activity = .capturing
        defer { activity = .idle }
        let started = Date()
        do {
            let photo = try await captureService.capturePhoto(PhotoRequest(raw: raw, fullResolution: fullResolution))
            photo.report.forEach { SessionLog.i("Shot", $0) }
            activity = .saving
            if let data = photo.processed { thumbnail = MediaLibrary.thumbnail(of: data) }
            try await mediaLibrary.save(photo)
            SessionLog.i("Shot", "saved in \(Int(Date().timeIntervalSince(started) * 1000)) ms, \(memoryFootprint())")
        } catch {
            SessionLog.e("Shot", "failed: \(error.localizedDescription)")
            show(error.localizedDescription)
        }
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
