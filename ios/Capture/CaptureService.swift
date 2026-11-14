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
    /// pref_ae_mode_key as the top row stores it (applied to every lens it opens)
    private var flashMode = FlashMode.off
    /// SIMPLE_UI §1: a long press holds the focus and the exposure («Закреплено») until a tap elsewhere or a reset
    private var aeAfLocked = false
    /// ZoomController's residual: the digital crop over the module's own zoom
    private var residual = 1.0
    /// «Про»: the values applied last, and the exposure (ISO x seconds) the AE had when they took over (the software priority)
    private var manual = ManualSettings.load()
    private var autoExposureProduct: Double?

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
        /// The open lens: a flash unit, a torch, the user's side
        var hasFlash = false
        var hasTorch = false
        var isFront = false
        /// The lens's ranges for «Про» and the exposure sun
        var evRange: ClosedRange<Float> = -2...2
        var isoRange: ClosedRange<Float> = 25...3200
        var durationRange: ClosedRange<Double> = 1.0 / 8000...1
        /// The digital zoom the lens allows over its module's own (ZoomController's residual range)
        var maxResidual = 1.0
        /// The system's capture sound is suppressed (iOS 18, where allowed): the app's own shutter sound plays
        var suppressesShutterSound = false
    }

    func start(preferredModule: String?, fullResolution: Bool) throws -> State {
        self.fullResolution = fullResolution
        if !isSetUp {
            modules = ModuleSettings.apply(DeviceLookup.modules())
            SessionLog.i("Modules", modules.map { "\($0.id)=\($0.label) \($0.kind.rawValue) zoom=\(String(format: "%.2f", $0.zoom)) \($0.deviceName)" }
                .joined(separator: "; "))
            guard let module = modules.first(where: { $0.id == preferredModule }) ?? modules.first(where: { $0.zoom == 1 && !$0.isFront })
                    ?? modules.first else { throw CameraError.videoDeviceUnavailable }
            session.beginConfiguration()
            session.sessionPreset = .photo
            do {
                try addOutput(photoCapture.output)
                // the live look's frames (Viewfinder/ViewfinderRenderer); without them the plain preview layer only
                let frames = ViewfinderRenderer.shared.output
                if session.canAddOutput(frames) {
                    session.addOutput(frames)
                } else {
                    SessionLog.w("Capture", "viewfinder frames output refused: the plain preview only")
                }
                // the viewfinder's face boxes (App/Camera/SubjectTracking FaceBoxes)
                let faces = FaceBoxes.shared.output
                if session.canAddOutput(faces) {
                    session.addOutput(faces)
                } else {
                    SessionLog.w("Capture", "face metadata output refused: no face boxes")
                }
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
        var s = State(modules: modules, active: activeModule, supportsFullResolution: photoCapture.supportsFullResolution)
        s.suppressesShutterSound = photoCapture.suppressesShutterSound
        if let device = activeInput?.device {
            s.hasFlash = device.hasFlash
            s.hasTorch = device.hasTorch
            s.isFront = device.position == .front
            if device.maxExposureTargetBias > device.minExposureTargetBias {
                s.evRange = device.minExposureTargetBias...device.maxExposureTargetBias
            }
            let f = device.activeFormat
            if f.maxISO > f.minISO { s.isoRange = f.minISO...f.maxISO }
            let d0 = f.minExposureDuration.seconds, d1 = f.maxExposureDuration.seconds
            if d0 > 0, d1 > d0 { s.durationRange = d0...d1 }
            let base = max(activeModule?.deviceZoom ?? 1, 1)
            s.maxResidual = max(1, min(Double(device.maxAvailableVideoZoomFactor) / base, 10))
        }
        return s
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

    /// The modules again with the settings' names, ratios, buttons and copies (ModuleSettings: the settings closed); the open lens stays
    func reloadModules() -> State {
        guard isSetUp else { return state }
        modules = ModuleSettings.apply(DeviceLookup.modules())
        if let id = activeModule?.id, let now = modules.first(where: { $0.id == id }) { activeModule = now }
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
        residual = 1
        aeAfLocked = false
        let focus = Self.focusMode
        if device.isFocusModeSupported(focus) { device.focusMode = focus }
        else if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        // «Распознавание лиц» (pref_face_detect_mode): off keeps the focus and the exposure off the faces
        let faces = StoredPrefs.string("pref_face_detect_mode", "auto") != "off"
        device.automaticallyAdjustsFaceDrivenAutoFocusEnabled = faces
        device.automaticallyAdjustsFaceDrivenAutoExposureEnabled = faces
        if !faces {
            device.isFaceDrivenAutoFocusEnabled = false
            device.isFaceDrivenAutoExposureEnabled = false
        }
        // «Ночной буст» (pref_low_light_boost, Android CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY): the lens's own boost
        if device.isLowLightBoostSupported {
            device.automaticallyEnablesLowLightBoostWhenAvailable = StoredPrefs.bool("pref_low_light_boost", false)
        }
        Self.applyMotionCeiling(device)
        device.unlockForConfiguration()
        applyFlash(device)
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

    /// The SHDR series: `count` N frames, then the plan's ultrashort / bracketed frames (HybridPlan). It carries the press's digital
    /// zoom (ZoomController's residual: SabreCrop's window and the photo's crop, BurstProcessor).
    /// Whether the photo output gives Bayer RAW (an iPad / a lens without RAW: none)
    var hasBayerRaw: Bool { photoCapture.output.availableRawPhotoPixelFormatTypes.contains { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) } }

    func captureBayerBurst(count: Int) async throws -> BayerBurst {
        guard let device = activeInput?.device else { throw CameraError.videoDeviceUnavailable }
        let zoom = residual * (activeModule?.crop ?? 1) // a copy's crop (ModuleSettings) on top of the residual
        var burst = try await photoCapture.captureBayerBurst(count: count, device: device)
        burst.zoom = zoom
        return burst
    }

    // MARK: the top row's flash, the zoom and «Про» (CameraUIController torch_button, ZoomController, the manual panel)

    /// «Режим фокуса» (pref_af_mode_key: 4 continuous picture, 3 continuous video, 1 auto - one scan, then held)
    private static var focusMode: AVCaptureDevice.FocusMode {
        StoredPrefs.string("pref_af_mode_key", "4") == "1" ? .autoFocus : .continuousAutoFocus
    }

    /// «Спорт» (MotionMode, P110): the camera's auto exposure keeps the shutter at or under pref_scam_hybrid_mm_shutter (1/250 s;
    /// "0" no ceiling), the ISO raised instead; outside the mode the lens's own ceiling. Called with the device locked.
    private static func applyMotionCeiling(_ device: AVCaptureDevice) {
        guard device.exposureMode != .custom else { return }   // a manual shutter («Про») is its own
        let denominator = StoredPrefs.bool("pref_motion_mode", false) ? Double(StoredPrefs.string("pref_scam_hybrid_mm_shutter", "250")) ?? 250 : 0
        let f = device.activeFormat
        if denominator > 0 {
            let seconds = min(max(1 / denominator, f.minExposureDuration.seconds), f.maxExposureDuration.seconds)
            device.activeMaxExposureDuration = CMTime(seconds: seconds, preferredTimescale: 1_000_000)
        } else {
            device.activeMaxExposureDuration = .invalid
        }
    }

    /// The camera mode changed (Спорт / Фото / Oldstyle): its shutter ceiling on the open lens
    func applyMode() {
        guard let device = activeInput?.device else { return }
        do {
            try device.lockForConfiguration()
            Self.applyMotionCeiling(device)
            device.unlockForConfiguration()
            SessionLog.i("Capture", "camera mode: sport \(StoredPrefs.bool("pref_motion_mode", false)), shutter ceiling \(device.activeMaxExposureDuration.seconds)")
        } catch {
            SessionLog.w("Capture", "camera mode: \(error.localizedDescription)")
        }
    }

    /// FlashMode: the torch while «Фонарик» is chosen (a lens with one), the scene metered for the flash in «Авто».
    func setFlash(_ mode: FlashMode) -> State {
        flashMode = mode
        if let device = activeInput?.device { applyFlash(device) }
        SessionLog.i("Capture", "flash mode \(mode.name)")
        return state
    }

    private func applyFlash(_ device: AVCaptureDevice) {
        let mode = FlashMode.effective(flashMode, flashUnit: device.hasFlash)
        let torch: AVCaptureDevice.TorchMode = mode == .torch ? .on : .off
        if device.hasTorch, device.isTorchModeSupported(torch), device.torchMode != torch {
            do {
                try device.lockForConfiguration()
                device.torchMode = torch
                device.unlockForConfiguration()
            } catch {
                SessionLog.w("Capture", "torch: \(error.localizedDescription)")
            }
        }
        photoCapture.monitorFlashScene(mode == .auto)
    }

    /// FlashMode.flashShot at the press: On, or Auto when the scene asks for the flash
    var flashShot: Bool {
        guard let device = activeInput?.device else { return false }
        return FlashMode.flashShot(FlashMode.effective(flashMode, flashUnit: device.hasFlash), flashScene: photoCapture.isFlashScene,
                                   flashUnit: device.hasFlash)
    }

    /// ZoomController: the residual crop over the open module's own zoom (1: the module as it is)
    func setZoom(residual r: Double) {
        guard let device = activeInput?.device, let module = activeModule else { return }
        let factor = min(max(CGFloat(module.deviceZoom * max(1, r)), device.minAvailableVideoZoomFactor), device.maxAvailableVideoZoomFactor)
        guard abs(device.videoZoomFactor - factor) > 0.001 else { return }
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = factor
            device.unlockForConfiguration()
            residual = Double(factor) / max(module.deviceZoom, 1)
        } catch {
            SessionLog.w("Capture", "zoom: \(error.localizedDescription)")
        }
    }

    /// The lens facts the photo's EXIF needs where the camera's metadata lacks them (ShotExif): the f-number, the 35 mm equivalent of
    /// the field the photo shows (the format's field of view over the zoom), the side, the module.
    func exifFacts(time: Date) -> ShotExif {
        var facts = ShotExif(time: time)
        facts.cameraID = activeModule?.id
        guard let device = activeInput?.device else { return facts }
        facts.facing = device.position == .front ? "front" : "back"
        facts.fNumber = Double(device.lensAperture)
        let dims = photoCapture.output.maxPhotoDimensions
        let aspect = dims.width > 0 && dims.height > 0 ? Double(max(dims.width, dims.height)) / Double(min(dims.width, dims.height)) : 4.0 / 3.0
        facts.focal35 = ShotExif.focal35(horizontalFOV: Double(device.activeFormat.videoFieldOfView), aspect: aspect,
                                         zoom: Double(device.videoZoomFactor))
        return facts
    }

    /// The flash shot's one Bayer frame (nil: refused, the camera's own flash photo then)
    func captureFlashFrame() async throws -> BayerBurst? {
        guard let device = activeInput?.device else { throw CameraError.videoDeviceUnavailable }
        let zoom = residual * (activeModule?.crop ?? 1) // a copy's crop (ModuleSettings) on top of the residual
        var burst = try await photoCapture.captureFlashFrame(device: device, flash: .on)
        burst?.zoom = zoom
        return burst
    }

    func applyManual(_ m: ManualSettings) {
        guard let device = activeInput?.device else { return }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            let f = device.activeFormat
            if (m.isoOn || m.shutterOn), device.isExposureModeSupported(.custom) {
                // ISO or shutter priority: the other one keeps the exposure the AE had (times the EV), as the panel's software
                // priority (SceneLuma) holds the scene's brightness
                if device.exposureMode != .custom || autoExposureProduct == nil {
                    autoExposureProduct = Double(device.iso) * device.exposureDuration.seconds
                }
                let product = (autoExposureProduct ?? 0.01) * pow(2, m.isoOn && m.shutterOn ? 0 : m.bias)
                var seconds = m.shutterOn ? pow(2, m.shutter) : product / max(m.iso, 1)
                seconds = min(max(seconds, f.minExposureDuration.seconds), f.maxExposureDuration.seconds)
                var iso = m.isoOn ? m.iso : product / max(seconds, 1e-6)
                iso = min(max(iso, Double(f.minISO)), Double(f.maxISO))
                device.setExposureModeCustom(duration: CMTime(seconds: seconds, preferredTimescale: 1_000_000), iso: Float(iso))
            } else {
                autoExposureProduct = nil
                if !aeAfLocked, device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
                device.setExposureTargetBias(Float(min(max(m.bias, Double(device.minExposureTargetBias)), Double(device.maxExposureTargetBias))))
            }
            if m.focusOn, device.isLockingFocusWithCustomLensPositionSupported {
                device.setFocusModeLocked(lensPosition: Float(m.focus))
            } else if manual.focusOn, device.isFocusModeSupported(Self.focusMode) {
                device.focusMode = Self.focusMode
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
            manual = m
        } catch {
            SessionLog.e("Capture", "manual settings: \(error.localizedDescription)")
        }
    }

    // MARK: focus and rotation (AVCam)

    private var previewLayer: AVCaptureVideoPreviewLayer? {
        session.connections.compactMap { $0.videoPreviewLayer }.first
    }

    /// TouchFocus.processTouchToFocus: a tap focuses and meters there (a held lock goes); a manual exposure («Про») stays as it is.
    func focusAndExpose(atLayerPoint point: CGPoint) {
        guard let layer = previewLayer else { return }
        aeAfLocked = false
        focusAndExpose(at: layer.captureDevicePointConverted(fromLayerPoint: point), userInitiated: true)
    }

    /// TouchFocus.lockAt (SIMPLE_UI §1): the long press focuses and meters there, then the exposure is locked once the metering
    /// settled (LOCK_AE_SETTLE_MS 600) until a tap elsewhere, a reset or another lens.
    func lockFocusAndExposure(atLayerPoint point: CGPoint) async {
        guard let layer = previewLayer else { return }
        focusAndExpose(at: layer.captureDevicePointConverted(fromLayerPoint: point), userInitiated: true)
        aeAfLocked = true
        if let device = activeInput?.device, (try? device.lockForConfiguration()) != nil {
            device.isSubjectAreaChangeMonitoringEnabled = false
            device.unlockForConfiguration()
        }
        SessionLog.i("Capture", "focus and exposure locked")
        try? await Task.sleep(nanoseconds: 600_000_000)
        guard aeAfLocked, let device = activeInput?.device, !(manual.isoOn || manual.shutterOn) else { return }
        do {
            try device.lockForConfiguration()
            if device.isExposureModeSupported(.locked) { device.exposureMode = .locked }
            device.unlockForConfiguration()
        } catch {
            SessionLog.w("Capture", "exposure lock: \(error.localizedDescription)")
        }
    }

    /// SubjectFocus: the tracked subject's centre drives the continuous focus and metering (a manual focus or exposure keeps its own)
    func track(atLayerPoint point: CGPoint) {
        guard let layer = previewLayer, let device = activeInput?.device else { return }
        let p = layer.captureDevicePointConverted(fromLayerPoint: point)
        aeAfLocked = false
        do {
            try device.lockForConfiguration()
            if !manual.focusOn, device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusPointOfInterest = p
                device.focusMode = .continuousAutoFocus
            }
            if !(manual.isoOn || manual.shutterOn), device.isExposurePointOfInterestSupported,
               device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposurePointOfInterest = p
                device.exposureMode = .continuousAutoExposure
            }
            device.isSubjectAreaChangeMonitoringEnabled = false
            device.unlockForConfiguration()
        } catch {
            SessionLog.w("Capture", "tracking focus: \(error.localizedDescription)")
        }
    }

    /// «Раздельные фокус и экспозиция» (pref_split_af_ae): the exposure square meters at its own point; the focus stays where it is
    func expose(atLayerPoint point: CGPoint) {
        guard let layer = previewLayer, let device = activeInput?.device, !(manual.isoOn || manual.shutterOn) else { return }
        let p = layer.captureDevicePointConverted(fromLayerPoint: point)
        do {
            try device.lockForConfiguration()
            if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(.autoExpose) {
                device.exposurePointOfInterest = p
                device.exposureMode = .autoExpose
            }
            device.unlockForConfiguration()
        } catch {
            SessionLog.w("Capture", "exposure point: \(error.localizedDescription)")
        }
    }

    /// A tap on the focus ring (TouchFocus.resetFocusCircle + setInitialAFAE): the lock goes, focus and exposure follow the scene.
    func resetFocusAndExposure() {
        aeAfLocked = false
        focusAndExpose(at: CGPoint(x: 0.5, y: 0.5), userInitiated: false)
    }

    private func focusAndExpose(at devicePoint: CGPoint, userInitiated: Bool) {
        guard let device = activeInput?.device else { return }
        do {
            try device.lockForConfiguration()
            if !manual.focusOn {
                let focus: AVCaptureDevice.FocusMode = userInitiated ? .autoFocus : Self.focusMode
                if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(focus) {
                    device.focusPointOfInterest = devicePoint
                    device.focusMode = focus
                }
            }
            // P521: a manual exposure («Про»: ISO / shutter set) takes no metering; the tap focuses only
            if !(manual.isoOn || manual.shutterOn) {
                let exposure: AVCaptureDevice.ExposureMode = userInitiated ? .autoExpose : .continuousAutoExposure
                if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(exposure) {
                    device.exposurePointOfInterest = devicePoint
                    device.exposureMode = exposure
                }
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
                if !aeAfLocked { focusAndExpose(at: CGPoint(x: 0.5, y: 0.5), userInitiated: false) }
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
        // the live look's frames upright as the preview, mirrored for the front lens; the front photo mirrored as Android's
        // Parameters.mirror unless «Disable front mirror» (pref_tunable_parameters_disablemirror)
        let front = device.position == .front
        let disableMirror = ["1", "true", "1.0"].contains(StoredPrefs.string("pref_tunable_parameters_disablemirror", "0"))
        ViewfinderRenderer.shared.configure(rotation: previewAngle, mirrored: front)
        photoCapture.setMirrored(front && !disableMirror)
        // «Информация на экране» (pref_show_afdata_key): the HUD follows this lens (Capture/CameraHud)
        hudMonitor.observe(device, layer: layer)
        FaceBoxes.shared.attach(layer)
        // the face type is offered only once the output has a lens's connection
        FaceBoxes.shared.configure()
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

    // MARK: the viewfinder's HUD («Информация на экране», Capture/CameraHud)

    /// The open lens's key-value observer for the viewfinder's HUD
    private let hudMonitor = DeviceHudMonitor()
}
