import AVFoundation

/// The one owner of AVCaptureSession and every AVFoundation call (AVCam CaptureService). Runs on its own serial queue.
actor CaptureService {
    nonisolated let previewSource: PreviewSource

    private let session = AVCaptureSession()
    private let photoCapture = PhotoCapture()
    private var activeInput: AVCaptureDeviceInput?
    private(set) var modules: [LensModule] = []
    private(set) var activeModule: LensModule?
    private var fullResolution = true
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    private var subjectAreaTask: Task<Void, Never>?
    private var isSetUp = false

    private let sessionQueue = DispatchSerialQueue(label: "scamera.capture-session")
    nonisolated var unownedExecutor: UnownedSerialExecutor { sessionQueue.asUnownedSerialExecutor() }

    init() {
        previewSource = DefaultPreviewSource(session: session)
    }

    var isAuthorized: Bool {
        get async {
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            if status == .notDetermined { return await AVCaptureDevice.requestAccess(for: .video) }
            return status == .authorized
        }
    }

    struct State: Sendable {
        var modules: [LensModule]
        var active: LensModule?
        var supportsFullResolution: Bool
    }

    func start(preferredModule: String?, fullResolution: Bool) throws -> State {
        self.fullResolution = fullResolution
        if !isSetUp {
            modules = DeviceLookup.modules()
            SessionLog.i("Modules", modules.map { "\($0.id)=\($0.label) \($0.kind.rawValue) zoom=\(String(format: "%.2f", $0.zoom)) \($0.deviceName)" }
                .joined(separator: "; "))
            guard let module = modules.first(where: { $0.id == preferredModule }) ?? modules.first(where: { $0.zoom == 1 && !$0.isFront })
                    ?? modules.first else { throw CameraError.videoDeviceUnavailable }
            session.beginConfiguration()
            session.sessionPreset = .photo
            do {
                try addOutput(photoCapture.output)
                try apply(module)
            } catch {
                session.commitConfiguration()
                throw error
            }
            // ProRAW is enabled before the session starts: toggling it later reconfigures the whole pipeline.
            if photoCapture.output.isAppleProRAWSupported { photoCapture.output.isAppleProRAWEnabled = true }
            session.commitConfiguration()
            observeNotifications()
            isSetUp = true
        }
        if !session.isRunning { session.startRunning() }
        if let device = activeInput?.device {
            DeviceProbe.describe(output: photoCapture.output).forEach { SessionLog.i("Probe", $0) }
            createRotationCoordinator(for: device)
        }
        return state
    }

    func stop() {
        if session.isRunning { session.stopRunning() }
    }

    var state: State {
        State(modules: modules, active: activeModule, supportsFullResolution: photoCapture.supportsFullResolution)
    }

    func select(moduleID: String) throws -> State {
        guard let module = modules.first(where: { $0.id == moduleID }) else { throw CameraError.moduleUnavailable }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        try apply(module)
        if let device = activeInput?.device { createRotationCoordinator(for: device) }
        DeviceProbe.describe(output: photoCapture.output).forEach { SessionLog.i("Probe", $0) }
        return state
    }

    func setFullResolution(_ enabled: Bool) -> State {
        fullResolution = enabled
        if let device = activeInput?.device {
            session.beginConfiguration()
            photoCapture.updateConfiguration(for: device, fullResolution: enabled)
            session.commitConfiguration()
            SessionLog.i("Capture", "full resolution \(enabled) -> maxPhoto=\(photoCapture.output.maxPhotoDimensions.text)")
        }
        return state
    }

    /// Switches the input (one camera per session, as AVCam) and applies the module's format and zoom.
    private func apply(_ module: LensModule) throws {
        guard let device = AVCaptureDevice(uniqueID: module.deviceID) else { throw CameraError.moduleUnavailable }
        if activeInput?.device != device {
            let previous = activeInput
            if let previous { session.removeInput(previous) }
            do {
                let input = try AVCaptureDeviceInput(device: device)
                guard session.canAddInput(input) else { throw CameraError.addInputFailed }
                session.addInput(input)
                activeInput = input
            } catch {
                if let previous, session.canAddInput(previous) { session.addInput(previous) }
                SessionLog.e("Capture", "module \(module.id) input failed: \(error.localizedDescription)")
                throw CameraError.addInputFailed
            }
            // A format chosen for the previous lens leaves the session at .inputPriority; the new lens starts from .photo.
            if session.sessionPreset != .photo, session.canSetSessionPreset(.photo) { session.sessionPreset = .photo }
            observeSubjectAreaChanges(of: device)
        }
        let presetFormat = device.activeFormat
        let best = DeviceLookup.bestPhotoFormat(for: device)
        try device.lockForConfiguration()
        if best != presetFormat, DeviceLookup.maxPhotoArea(best) > DeviceLookup.maxPhotoArea(presetFormat) {
            device.activeFormat = best
            SessionLog.i("Capture", "module \(module.id): format with larger stills chosen over the .photo preset format")
        }
        let zoom = min(max(CGFloat(module.deviceZoom), device.minAvailableVideoZoomFactor), device.maxAvailableVideoZoomFactor)
        device.videoZoomFactor = zoom
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        device.unlockForConfiguration()
        photoCapture.updateConfiguration(for: device, fullResolution: fullResolution)
        activeModule = module
        SessionLog.i("Capture", "module \(module.id) \(module.label) \(module.kind.rawValue) deviceZoom=\(zoom)")
        DeviceProbe.describe(device: device).forEach { SessionLog.i("Probe", $0) }
    }

    private func addOutput(_ output: AVCaptureOutput) throws {
        guard session.canAddOutput(output) else { throw CameraError.addOutputFailed }
        session.addOutput(output)
    }

    func capturePhoto(_ request: PhotoRequest) async throws -> CapturedPhoto {
        guard let device = activeInput?.device else { throw CameraError.videoDeviceUnavailable }
        return try await photoCapture.capture(request, device: device)
    }

    /// A Bayer RAW series of one exposure for the SHDR merge.
    func captureBayerBurst(count: Int) async throws -> BayerBurst {
        guard let device = activeInput?.device else { throw CameraError.videoDeviceUnavailable }
        return try await photoCapture.captureBayerBurst(count: count, device: device)
    }

    // MARK: the top row's flash and «Про» (CameraUIController torch_button, the manual panel)

    func setTorch(_ on: Bool) -> Bool {
        guard let device = activeInput?.device, device.hasTorch, device.isTorchModeSupported(on ? .on : .off) else { return false }
        do {
            try device.lockForConfiguration()
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
            return on
        } catch {
            return false
        }
    }

    func applyManual(_ m: ManualSettings) {
        guard let device = activeInput?.device else { return }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            if m.exposureOn, device.isExposureModeSupported(.custom) {
                let f = device.activeFormat
                let iso = Float(min(max(m.iso, Double(f.minISO)), Double(f.maxISO)))
                let seconds = min(max(pow(2, m.shutter), f.minExposureDuration.seconds), f.maxExposureDuration.seconds)
                device.setExposureModeCustom(duration: CMTime(seconds: seconds, preferredTimescale: 1_000_000), iso: iso)
            } else {
                if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
                device.setExposureTargetBias(Float(min(max(m.bias, Double(device.minExposureTargetBias)), Double(device.maxExposureTargetBias))))
            }
            if m.focusOn, device.isFocusModeSupported(.locked) {
                device.setFocusModeLocked(lensPosition: Float(m.focus))
            } else if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }
            if m.wbOn, device.isWhiteBalanceModeSupported(.locked) {
                var gains = device.deviceWhiteBalanceGains(for: AVCaptureDevice.WhiteBalanceTemperatureAndTintValues(temperature: Float(m.kelvin), tint: 0))
                let maxGain = device.maxWhiteBalanceGain
                gains.redGain = min(max(gains.redGain, 1), maxGain)
                gains.greenGain = min(max(gains.greenGain, 1), maxGain)
                gains.blueGain = min(max(gains.blueGain, 1), maxGain)
                device.setWhiteBalanceModeLocked(with: gains)
            } else if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) {
                device.whiteBalanceMode = .continuousAutoWhiteBalance
            }
        } catch {
            SessionLog.e("Capture", "manual settings: \(error.localizedDescription)")
        }
    }

    // MARK: focus and rotation (AVCam)

    private var previewLayer: AVCaptureVideoPreviewLayer? {
        session.connections.compactMap { $0.videoPreviewLayer }.first
    }

    func focusAndExpose(atLayerPoint point: CGPoint) {
        guard let layer = previewLayer else { return }
        focusAndExpose(at: layer.captureDevicePointConverted(fromLayerPoint: point), userInitiated: true)
    }

    private func focusAndExpose(at devicePoint: CGPoint, userInitiated: Bool) {
        guard let device = activeInput?.device else { return }
        do {
            try device.lockForConfiguration()
            let focus: AVCaptureDevice.FocusMode = userInitiated ? .autoFocus : .continuousAutoFocus
            if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(focus) {
                device.focusPointOfInterest = devicePoint
                device.focusMode = focus
            }
            let exposure: AVCaptureDevice.ExposureMode = userInitiated ? .autoExpose : .continuousAutoExposure
            if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(exposure) {
                device.exposurePointOfInterest = devicePoint
                device.exposureMode = exposure
            }
            device.isSubjectAreaChangeMonitoringEnabled = userInitiated
            device.unlockForConfiguration()
        } catch {
            SessionLog.w("Capture", "focus failed: \(error.localizedDescription)")
        }
    }

    private func observeSubjectAreaChanges(of device: AVCaptureDevice) {
        subjectAreaTask?.cancel()
        subjectAreaTask = Task {
            for await _ in NotificationCenter.default.notifications(named: AVCaptureDevice.subjectAreaDidChangeNotification, object: device) {
                focusAndExpose(at: CGPoint(x: 0.5, y: 0.5), userInitiated: false)
            }
        }
    }

    private func createRotationCoordinator(for device: AVCaptureDevice) {
        let layer = previewLayer
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: layer)
        rotationCoordinator = coordinator
        photoCapture.setVideoRotationAngle(coordinator.videoRotationAngleForHorizonLevelCapture)
        let previewAngle = coordinator.videoRotationAngleForHorizonLevelPreview
        Task { @MainActor in
            if let connection = layer?.connection, connection.isVideoRotationAngleSupported(previewAngle) {
                connection.videoRotationAngle = previewAngle
            }
        }
        rotationObservation = coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: .new) { [weak self] _, change in
            guard let self, let angle = change.newValue else { return }
            Task { await self.updateCaptureRotation(angle) }
        }
    }

    private func updateCaptureRotation(_ angle: CGFloat) {
        photoCapture.setVideoRotationAngle(angle)
    }

    private func observeNotifications() {
        Task {
            for await note in NotificationCenter.default.notifications(named: AVCaptureSession.runtimeErrorNotification) {
                let error = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
                SessionLog.e("Capture", "session runtime error \(error?.code.rawValue ?? -1): \(error?.localizedDescription ?? "")")
                if error?.code == .mediaServicesWereReset, !session.isRunning { session.startRunning() }
            }
        }
    }
}
