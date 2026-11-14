import SwiftUI
import UIKit

struct CameraView: View {
    let camera: CameraModel
    @State private var sharing: [URL]?
    @State private var showingInfo = false
    @State private var showingSettings = false
    @State private var showingFormat = false
    @State private var showingPro = false
    @State private var showingGallery = false
    @State private var chrome = CameraChrome()
    @State private var focus = FocusMark()
    @State private var sunEv = 0.0
    @State private var sunDragging = false
    @State private var rulerShown = false
    @State private var rulerTask: Task<Void, Never>?
    @State private var pinchStart = 1.0
    @State private var savedBrightness: CGFloat?
    @Environment(\.scenePhase) private var scenePhase

    /// TouchFocus's ring: where it is, whether it shows, dimmed while a lock holds (AUTO_HIDE_DELAY_MS 3 s)
    struct FocusMark {
        var point: CGPoint?
        var visible = false
        var dimmed = false
        var pulse = false
        var hide: Task<Void, Never>?
        static let radius: CGFloat = 35
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch camera.status {
            case .unauthorized:
                notice(L("camera.denied"), action: L("camera.open_settings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            case .failed(let reason):
                notice(L("camera.failed") + "\n" + reason, action: nil, perform: {})
            default:
                content
            }
        }
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) {
            ActivityView(items: sharing ?? [])
        }
        .sheet(isPresented: $showingInfo) {
            InfoView(camera: camera)
        }
        .fullScreenCover(isPresented: $showingSettings) {
            SettingsView(camera: camera)
        }
        .sheet(isPresented: $showingFormat) {
            FormatSheet(camera: camera).presentationDetents([.medium])
        }
        .fullScreenCover(isPresented: $showingGallery) {
            GalleryView()
        }
        .sheet(isPresented: $showingPro) {
            ProSheet(camera: camera).presentationDetents([.medium])
        }
        // P507: the front lens's torch lights the face with the screen (full brightness while it is on)
        .onChange(of: camera.screenLight) { setScreenLight(camera.screenLight) }
        .onChange(of: scenePhase) {
            if scenePhase != .active {
                chrome.cancelTimer()   // CameraUIController.onPause: the countdown stops, no shot
                setScreenLight(false)
            } else {
                setScreenLight(camera.screenLight)
            }
        }
        .onDisappear { chrome.cancelTimer(); setScreenLight(false) }
    }

    private var content: some View {
        VStack(spacing: 12) {
            topBar
            GeometryReader { geometry in
                CameraPreview(source: camera.previewSource,
                              onTap: { tap(at: $0, in: geometry.size) },
                              onLongPress: { longPress(at: $0) },
                              onPinch: { scale, began in pinch(scale, began: began) },
                              onHardwareShutter: { pressShutter() })
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .overlay { ViewfinderOverlay(grid: chrome.gridType, horizon: chrome.horizon) }
                    .overlay {
                        // «Информация на экране» (pref_show_afdata_key)
                        let hudMode = Int(SettingsStore.shared.string("pref_show_afdata_key", "0")) ?? 0
                        if hudMode > 0 { CameraHudView(mode: hudMode, sample: CameraHud.shared.sample) }
                    }
                    .overlay { focusLayer(in: geometry.size) }
                    .overlay(alignment: .top) { topPills }
                    .overlay(alignment: .bottom) { messageCard }
                    .overlay(alignment: .bottom) {
                        if rulerShown {
                            ZoomRuler(zoom: camera.zoom, range: camera.zoomRange, marks: camera.sideModules.map { ($0.zoom, $0.label) },
                                      onZoom: { z in pokeRuler(); Task { await camera.zoom(to: z) } })
                                .frame(width: min(280, geometry.size.width - 32))
                                .padding(.bottom, 12)
                                .transition(.opacity)
                        }
                    }
                    .overlay {
                        if camera.screenLight {
                            RoundedRectangle(cornerRadius: chrome.roundEdges ? 24 : 0).strokeBorder(Color.white, lineWidth: 36)
                                .allowsHitTesting(false)
                        }
                    }
                    .overlay {
                        if camera.status == .interrupted {
                            Text(L("camera.interrupted")).padding().background(.ultraThinMaterial, in: Capsule())
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: chrome.roundEdges ? 24 : 0))
            }
            .aspectRatio(3.0 / 4.0, contentMode: .fit)
            moduleRow
            bottomBar
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: the viewfinder's gestures (Swipe / TouchFocus)

    /// A tap: on the ring it goes back to the camera's own focus and metering; elsewhere it focuses and meters there (a manual focus
    /// takes no tap, as Android's FOCUS_AUTO check)
    private func tap(at point: CGPoint, in size: CGSize) {
        guard !ManualSettings.load().focusOn else { return }
        if focus.visible, let p = focus.point, hypot(p.x - point.x, p.y - point.y) <= FocusMark.radius {
            hideRing(now: true)
            Task { await camera.resetFocus() }
            return
        }
        showRing(at: point, locked: false)
        Task { await camera.focus(at: point) }
    }

    /// A long press holds focus and exposure there («Закреплено»; Android's tracking autofocus has no counterpart here)
    private func longPress(at point: CGPoint) {
        guard !ManualSettings.load().focusOn else { return }
        showRing(at: point, locked: true)
        Task { await camera.lock(at: point) }
    }

    private func pinch(_ scale: CGFloat, began: Bool) {
        if began { pinchStart = camera.zoom }
        pokeRuler()
        let target = pinchStart * Double(scale)
        Task { await camera.zoom(to: target) }
    }

    private func pokeRuler() {
        withAnimation(.easeOut(duration: 0.15)) { rulerShown = true }
        rulerTask?.cancel()
        rulerTask = Task {
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            if !Task.isCancelled { withAnimation(.easeIn(duration: 0.22)) { rulerShown = false } }
        }
    }

    private func showRing(at point: CGPoint, locked: Bool) {
        focus.hide?.cancel()
        focus.point = point
        focus.dimmed = false
        sunEv = ManualSettings.load().bias
        withAnimation(.easeOut(duration: 0.25)) { focus.visible = true; focus.pulse = true }
        Task {
            try? await Task.sleep(nanoseconds: 250_000_000)
            withAnimation(.easeIn(duration: 0.25)) { focus.pulse = false }
        }
        focus.hide = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            if camera.locked && locked {
                withAnimation(.easeOut(duration: 0.25)) { focus.dimmed = true }
            } else if !sunDragging {
                hideRing(now: false)
            }
        }
    }

    private func hideRing(now: Bool) {
        focus.hide?.cancel()
        withAnimation(.easeIn(duration: now ? 0.1 : 0.15)) { focus.visible = false }
    }

    /// The ring and the exposure sun beside it (CameraFragment.attachExposureSun: hidden while ISO and shutter are both manual)
    @ViewBuilder private func focusLayer(in size: CGSize) -> some View {
        if focus.visible, let p = focus.point {
            let r = FocusMark.radius
            let manual = ManualSettings.load()
            ZStack(alignment: .topLeading) {
                Circle().stroke(Color.white, lineWidth: 1.5)
                    .frame(width: 2 * r, height: 2 * r)
                    .scaleEffect(focus.pulse ? 1.2 : 1)
                    .position(p)
                    .opacity(focus.dimmed && !camera.locked ? 0 : focus.dimmed ? 0.55 : 1)
                    .allowsHitTesting(false)
                if !(manual.isoOn && manual.shutterOn), camera.lens.evRange.upperBound > camera.lens.evRange.lowerBound, !focus.dimmed {
                    let total = ExposureSun.width + ExposureSun.label
                    let right = p.x + r + ExposureSun.gap
                    let x = right + total > size.width ? p.x - r - ExposureSun.gap - ExposureSun.width : right
                    let y = min(max(0, p.y - ExposureSun.height / 2), size.height - ExposureSun.height)
                    ExposureSun(range: max(-4, camera.lens.evRange.lowerBound)...min(4, camera.lens.evRange.upperBound), ev: sunEv,
                                onEv: { ev in
                                    sunEv = ev
                                    Task { await camera.setEv(ev) }
                                },
                                onDrag: { dragging in
                                    sunDragging = dragging
                                    if dragging { focus.hide?.cancel() } else { showRing(at: p, locked: camera.locked) }
                                })
                        .offset(x: max(0, x), y: y)
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
    }

    /// The «Закреплено» pill (accent, a 13 pt lock) and the short notes (Android's snackbar)
    private var topPills: some View {
        VStack(spacing: 8) {
            if camera.locked {
                HStack(spacing: 4) {
                    Image("kb_ui_ic_lock_rest").renderingMode(.template).resizable().frame(width: 13, height: 13)
                    Text(L("focus.locked")).font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(.black)
                .padding(.leading, 6).padding(.trailing, 8).frame(height: 24)
                .background(CameraStyle.accent, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel(L("focus.locked_description"))
            }
            if let toast = camera.toast {
                Text(toast).font(.footnote.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(.black.opacity(0.65), in: Capsule())
                    .transition(.opacity)
            }
        }
        .padding(.top, 12)
        .allowsHitTesting(false)
        .animation(.easeOut(duration: 0.2), value: camera.toast)
    }

    private func setScreenLight(_ on: Bool) {
        guard let screen = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.screen else { return }
        if on {
            if savedBrightness == nil { savedBrightness = screen.brightness }
            screen.brightness = 1
        } else if let saved = savedBrightness {
            screen.brightness = saved
            savedBrightness = nil
        }
    }

    // layout_main_topbar: format badge, flash, timer, grid, level, Ultra HDR (the Android top row)
    private var topBar: some View {
        HStack(spacing: 6) {
            Button { showingFormat = true } label: {
                HStack(spacing: 4) {
                    Text(camera.formatBadge).font(.system(size: 13, weight: .bold))
                    Text(camera.sizeBadge).font(.system(size: 11, weight: .semibold)).opacity(0.7)
                }
                .padding(.horizontal, 10).frame(height: 32)
                .background(Color.white.opacity(0.12), in: Capsule())
            }
            .accessibilityLabel(L("top.format"))
            Spacer(minLength: 4)
            // P590: off a bolt with a slash, auto a bolt with «A», on the filled bolt, the torch a flashlight
            topIcon(camera.flashShown.icon, active: camera.flashShown != .off) { Task { await camera.cycleFlash() } }
                .accessibilityLabel(camera.flashShown.label)
            ZStack(alignment: .topTrailing) {
                topIcon("cv_timer", active: chrome.timerSeconds > 0) {
                    let seconds = chrome.cycleTimer()
                    camera.say(seconds > 0 ? String(format: L("timer.seconds"), seconds) : L("timer.off"))
                }
                if chrome.timerSeconds > 0 {
                    Text("\(chrome.timerSeconds)").font(.system(size: 9, weight: .bold)).padding(.horizontal, 3)
                        .background(CameraStyle.accent, in: Capsule()).foregroundStyle(.black)
                }
            }
            .accessibilityLabel(L("top.timer"))
            topIcon("cv_grid", active: chrome.grid) { camera.say(L(chrome.toggleGrid() ? "grid.on" : "grid.off")) }
                .accessibilityLabel(L("top.grid"))
            topIcon("cv_level", active: chrome.horizon) {
                chrome.horizon.toggle()
                camera.say(L(chrome.horizon ? "level.on" : "level.off"))
            }
            .accessibilityLabel(L("top.level"))
            topIcon("cv_sparkle", active: chrome.ultraHdr) {
                chrome.ultraHdr.toggle()
                camera.say(L(chrome.ultraHdr ? "uhdr.on" : "uhdr.off"))
            }
            .accessibilityLabel(L("top.uhdr"))
            Menu {
                Button(L("log.share")) { sharing = SessionLog.shared.flushedFiles() }
                Button(L("info.title")) { showingInfo = true }
            } label: { Image(systemName: "ellipsis").frame(width: 32, height: 40) }
        }
        .foregroundStyle(.white)
        .frame(height: 64)
    }

    private func topIcon(_ name: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(name).renderingMode(.template).resizable().frame(width: 22, height: 22)
                .foregroundStyle(active ? CameraStyle.accent : Color.white)
                .frame(width: 40, height: 40)
                .background(active ? CameraStyle.accent.opacity(0.16) : Color.clear, in: Circle())
        }
    }

    private var moduleRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(camera.modules) { module in
                    let active = module.id == camera.activeModuleID
                    Button {
                        if !active { pokeRuler() }
                        Task { await camera.select(module) }
                    } label: {
                        // the active chip shows the zoom when a crop is over its module's ratio
                        Text(active && abs(camera.zoom - module.zoom) > 0.05 ? CameraStyle.zoomText(camera.zoom) + "×" : module.label)
                            .font(.system(size: 14, weight: .semibold))
                            .frame(minWidth: 44, minHeight: 36)
                            .padding(.horizontal, 6)
                            .background(active ? CameraStyle.accent : Color.white.opacity(0.15), in: Capsule())
                            .foregroundStyle(active ? Color.black : Color.white)
                    }
                    .accessibilityLabel(module.deviceName + " " + module.label)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var shutterEnabled: Bool { camera.activity == .idle && camera.status == .running }

    /// The shutter button, and the volume buttons (CameraActivity.dispatchKeyEvent: a click of the shutter while it can be clicked)
    private func pressShutter() {
        guard shutterEnabled || chrome.counting else { return }
        chrome.shoot { await camera.capture() }
    }

    // layout_bottombuttons: gallery, shutter, flip; then settings, the mode switch (Спорт / Фото / Oldstyle) and «Про»
    private var bottomBar: some View {
        VStack(spacing: 14) {
            HStack {
                Group {
                    if let thumbnail = camera.thumbnail {
                        Image(decorative: thumbnail, scale: 1).resizable().scaledToFill()
                    } else {
                        Image("kb_ic_shade_jpeg_rest").renderingMode(.template).resizable().frame(width: 26, height: 26).foregroundStyle(.white.opacity(0.6))
                    }
                }
                .frame(width: 58, height: 58)
                .background(Color.white.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay { if camera.activity != .idle { ProgressView().tint(.white) } }
                .contentShape(Rectangle())
                .onTapGesture { showingGallery = true }
                .accessibilityLabel(L("bottom.gallery"))
                .accessibilityAddTraits(.isButton)
                Spacer()
                Button {
                    pressShutter()
                } label: {
                    ZStack {
                        Circle().stroke(Color(red: 0.957, green: 0.953, blue: 0.969), lineWidth: 3).frame(width: 86, height: 86)
                        Circle().fill(camera.activity == .idle && chrome.countdown == 0 ? Color(red: 0.957, green: 0.953, blue: 0.969) : Color(white: 0.85))
                            .frame(width: 70, height: 70)
                        if chrome.countdown > 0 { Text("\(chrome.countdown)").font(.title.bold()).foregroundStyle(.black) }
                    }
                }
                // a press while the timer counts stops it (CameraUIController: the hovered shutter resets the timer)
                .disabled(!(shutterEnabled || chrome.counting))
                .accessibilityLabel(L("capture.shutter"))
                Spacer()
                Button { Task { await camera.flip() } } label: {
                    Image("cv_flip").renderingMode(.template).resizable().frame(width: 26, height: 26).foregroundStyle(.white)
                        .frame(width: 58, height: 58).background(Color.white.opacity(0.1), in: Circle())
                }
                .accessibilityLabel(L("bottom.flip"))
            }
            HStack {
                Button { showingSettings = true } label: {
                    Image("cv_gear").renderingMode(.template).resizable().frame(width: 24, height: 24).frame(width: 50, height: 50)
                }
                .accessibilityLabel(L("settings.open"))
                Spacer()
                HStack(spacing: 4) {
                    modeButton("cv_run_m", .sport)
                    modeButton("cv_photo_m", .photo)
                    modeButton("cv_os_cassette", .oldstyle)
                }
                .padding(4).background(Color.white.opacity(0.1), in: Capsule())
                Spacer()
                Button { showingPro = true } label: {
                    Image("cv_sliders").renderingMode(.template).resizable().frame(width: 22, height: 22).frame(width: 50, height: 50)
                        .background(Color.white.opacity(0.1), in: Circle())
                }
                .accessibilityLabel(L("bottom.pro"))
            }
            .foregroundStyle(.white)
        }
    }

    private func modeButton(_ icon: String, _ mode: CameraChrome.Mode) -> some View {
        Button {
            guard chrome.mode != mode else { return }   // a tap on the chosen mode does nothing
            chrome.mode = mode
            camera.say(mode.title)
            Task { await camera.modeChanged() }
        } label: {
            Image(icon).renderingMode(.template).resizable().frame(width: 24, height: 24)
                .foregroundStyle(chrome.mode == mode ? Color.black : Color.white)
                .frame(width: 44, height: 44)
                .background(chrome.mode == mode ? CameraStyle.accent : Color.clear, in: Circle())
        }
        .accessibilityLabel(mode.title)
    }

    @ViewBuilder private var messageCard: some View {
        if let message = camera.message {
            Text(message)
                .font(.footnote)
                .foregroundStyle(.white)
                .padding(10)
                .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 10))
                .padding(8)
                .onTapGesture { camera.clearMessage() }
        }
    }

    private func notice(_ text: String, action: String?, perform: @escaping () -> Void) -> some View {
        VStack(spacing: 16) {
            Text(text).multilineTextAlignment(.center).foregroundStyle(.white)
            if let action { Button(action, action: perform).buttonStyle(.borderedProminent) }
        }
        .padding(24)
    }
}

struct InfoView: View {
    let camera: CameraModel

    var body: some View {
        NavigationStack {
            List {
                Section(L("info.build")) { Text(BuildInfo.line).font(.footnote.monospaced()).textSelection(.enabled) }
                Section(L("info.modules")) {
                    ForEach(camera.modules) { module in
                        VStack(alignment: .leading) {
                            Text("\(module.id)  \(module.label)  \(module.kind == .isz ? "ISZ" : L("info.lens"))")
                            Text(module.deviceName).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section(L("info.log")) {
                    Text(SessionLog.shared.folder.path).font(.caption.monospaced()).textSelection(.enabled)
                }
            }
            .navigationTitle(L("info.title"))
        }
    }
}

/// UIActivityViewController for sharing the session log files.
struct ActivityView: UIViewControllerRepresentable {
    let items: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
