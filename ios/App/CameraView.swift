import SwiftUI
import UIKit

/// The camera screen (camera_screen / CameraFragment, P574 / P583): the top row of 40 pt items with its grabber, then the stage - the
/// 3:4 viewfinder from the stage's top with its overlays, and the bottom bar in the room under it (the message band, the lens strip,
/// the gallery card / shutter / switch row, the gear · mode switch · «Про» row, the shade's grabber). Over the bar: the Pro panel,
/// the LUT tray in its place, the «Oldstyle» controls over them and the quick-settings shade.
struct CameraView: View {
    let camera: CameraModel
    @State private var sharing: [URL]?
    @State private var showingInfo = false
    @State private var showingSettings = false
    @State private var showingGallery = false
    @State private var formatTab: Int?
    @State private var proOpen = false
    @State private var topRowSheet = false
    @State private var history = false
    @State private var lutPage: Int?
    @State private var chrome = CameraChrome()
    @State private var focus = FocusMark()
    @State private var sunEv = 0.0
    @State private var sunDragging = false
    @State private var pinchStart = 1.0
    @State private var savedBrightness: CGFloat?
    @State private var flipTurn = 0.0
    @State private var exposurePoint: CGPoint?
    @Environment(\.scenePhase) private var scenePhase

    private var lut: LutTrayModel { LutTrayModel.shared }
    private var shade: ShadeModel { ShadeModel.shared }
    private var store: SettingsStore { SettingsStore.shared }

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
            if topRowSheet { TopRowSheet(camera: camera, shown: $topRowSheet).zIndex(5) }
            FirstRunDialog().zIndex(6)
        }
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) {
            ActivityView(items: sharing ?? [])
        }
        .sheet(isPresented: $showingInfo) { InfoView(camera: camera) }
        .fullScreenCover(isPresented: $showingSettings) { SettingsView(camera: camera) }
        .sheet(isPresented: Binding(get: { formatTab != nil }, set: { if !$0 { formatTab = nil } })) {
            FormatSizeSheet(camera: camera, tab: formatTab ?? 0)
        }
        .fullScreenCover(isPresented: $showingGallery) { GalleryView() }
        .sheet(isPresented: $history) { MessageHistorySheet() }
        .sheet(isPresented: Binding(get: { lutPage != nil }, set: { if !$0 { lutPage = nil; lut.touch() } })) {
            NavigationStack {
                switch lutPage ?? 0 {
                case 1: LensFilterPage()
                case 2: FilmSimPage()
                default: FilmLutPage()
                }
            }
            .preferredColorScheme(.dark)
        }
        // P507: the front lens's torch lights the face with the screen (full brightness while it is on)
        .onChange(of: camera.screenLight) { setScreenLight(camera.screenLight) }
        .onChange(of: scenePhase) {
            if scenePhase != .active {
                chrome.cancelTimer()   // CameraUIController.onPause: the countdown stops, no shot
                setScreenLight(false)
                FrameAnalysis.shared.stopTracking()
            } else {
                setScreenLight(camera.screenLight)
            }
        }
        .onChange(of: ringJobs) { old, new in ringJobsChanged(from: old, to: new) }
        .onChange(of: QuickActions.shared.pending) { openQuickAction() }
        .onChange(of: hudMode) { analysisSettings() }
        .onAppear {
            openQuickAction()
            analysisSettings()
            FrameAnalysis.shared.onTrack = { rect, centre in
                SubjectOverlayModel.shared.setTrack(rect)
                if let centre { Task { await camera.track(at: centre) } }
                if rect == nil && centre == nil { Task { await camera.resetFocus() } }
            }
        }
        .onDisappear { chrome.cancelTimer(); setScreenLight(false) }
    }

    private var hudMode: Int { Int(store.string("pref_show_afdata_key", "0")) ?? 0 }

    private func analysisSettings() {
        FrameAnalysis.shared.setHistogram(hudMode == 2)
        TripodDetector.shared.setActive(hudMode == 1 || hudMode == 2)
    }

    private func openQuickAction() {
        guard let action = QuickActions.shared.pending else { return }
        QuickActions.shared.pending = nil
        if action == QuickActions.settings { showingSettings = true }
    }

    /// The gallery card's ring (P158): a shot's processing starts when its frames are in (queued SHDR shots, or the camera's own
    /// photo being saved), ends when it is saved (or failed); the ring runs again for each shot still queued
    private var ringJobs: Int { camera.shots.count + (camera.activity == .saving ? 1 : 0) }

    private func ringJobsChanged(from old: Int, to new: Int) {
        if new > old { for _ in old..<new { ProcessingRingModel.shared.start() } }
        if new < old { for _ in new..<old { ProcessingRingModel.shared.finish() } }
    }

    // MARK: the layout

    private var content: some View {
        // what the stage's GeometryReader content reads, read in the body too, so a change rebuilds it
        let _ = (shade.level, lut.open, chrome.mode, chrome.horizon, chrome.gridType, chrome.countdown, chrome.roundEdges,
                 OldstyleModel.shared.open, OldstyleModel.shared.tab, camera.screenLight, camera.status, camera.locked, camera.zoom,
                 camera.activity, camera.thumbnail, camera.activeModuleID, ZoomReadoutModel.shared.visible, proOpen, focus.visible,
                 focus.point, focus.dimmed, focus.pulse, exposurePoint, sunEv, flipTurn)
        let _ = hudMode > 0 ? CameraHud.shared.sample : nil
        return VStack(spacing: 0) {
            ZStack(alignment: .top) {
                TopRowBar(camera: camera, chrome: chrome, lutOn: lut.anyOn, lutOpen: lut.open,
                          onFormat: { size in formatTab = size ? 1 : 0 },
                          onLut: { toggleLut() },
                          onEdit: { openTopRow() })
                TopGrabber { openTopRow() }.padding(.top, 3)
                if shade.level == .full { scrim.frame(height: 64) }
            }
            GeometryReader { g in
                let vfHeight = min(g.size.width * 4 / 3, g.size.height)
                // a screen shorter than the bar needs (the iPhone's 19.5:9 under its safe areas): the band and the lens strip float
                // over the preview's bottom, as Android's bar does on a 16:9 screen, with the bar's gaps closed up
                let compact = g.size.height - vfHeight < Self.barNeed
                let barHeight = min(g.size.height, max(g.size.height - vfHeight, compact ? Self.barCompact : Self.barNeed))
                ZStack(alignment: .top) {
                    viewfinder(width: g.size.width, height: vfHeight)
                    if shade.level == .full { scrim.frame(height: g.size.height - barHeight) }
                    bottomBar(compact: compact)
                        .frame(width: g.size.width, height: barHeight)
                        .offset(y: g.size.height - barHeight)
                    panels(width: g.size.width)
                        .frame(width: g.size.width, height: g.size.height - barHeight, alignment: .bottom)
                    if shade.level != .hidden {
                        QuickShade(camera: camera, chrome: chrome, maxHeight: min(vfHeight * 0.86, g.size.height - barHeight),
                                   onFormat: { size in formatTab = size ? 1 : 0 },
                                   onSettings: { showingSettings = true })
                            .frame(width: g.size.width, height: g.size.height - barHeight, alignment: .bottom)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                            .zIndex(3)
                    }
                }
                .frame(width: g.size.width, height: g.size.height, alignment: .top)
                .clipped()
            }
        }
    }

    /// The FULL shade's dimming over the viewfinder and the top row (a tap lowers the shade to PEEK)
    private var scrim: some View {
        Color.black.opacity(0.45)
            .contentShape(Rectangle())
            .onTapGesture { shade.set(.peek) }
            .accessibilityLabel(L("a.shade_scrim"))
    }

    private func openTopRow() {
        guard shade.level == .hidden, !lut.open else { return }
        Haptics.tick()
        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { topRowSheet = true }
    }

    private func toggleLut() {
        withAnimation(CameraMotion.open) {
            lut.open.toggle()
            if lut.open { proOpen = false; OldstyleModel.shared.open = false }
        }
    }

    /// The Pro panel, the LUT tray in its place and the «Oldstyle» controls over them, 8 pt in from the sides, 12 pt over the bar
    private func panels(width: CGFloat) -> some View {
        let oldstyle = chrome.mode == .oldstyle
        let shadeDown = shade.level == .hidden
        let styleOpen = oldstyle && OldstyleModel.shared.open
        return VStack(spacing: 8) {
            if oldstyle {
                HStack { Spacer(); OldstyleControls(camera: camera, shown: shadeDown && !lut.open) }
            }
            ZStack(alignment: .bottom) {
                ManualPanel(camera: camera, expanded: Binding(get: { oldstyle || proOpen }, set: { proOpen = $0 }),
                            shown: shadeDown && !lut.open && !styleOpen)
                if lut.open && shadeDown {
                    LutTray(onAll: { lutPage = $0; lut.open = false })
                        .transition(.opacity.combined(with: .scale(scale: 0.94, anchor: .top)))
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 12)
    }

    // MARK: the viewfinder

    private func viewfinder(width: CGFloat, height: CGFloat) -> some View {
        let size = CGSize(width: width, height: height)
        return CameraPreview(source: camera.previewSource,
                             onTap: { tap(at: $0, in: size) },
                             onLongPress: { longPress(at: $0, in: size) },
                             onPinch: { scale, began in pinch(scale, began: began) },
                             onHardwareShutter: { pressShutter() },
                             onFling: { t, v, start, view in fling(t, v, start, view, size: size) })
            .frame(width: width, height: height)
            .overlay { ViewfinderOverlay(grid: chrome.gridType, horizon: false) }
            .overlay { SubjectOverlay() }
            .overlay {
                // «Информация на экране» (pref_show_afdata_key)
                if hudMode > 0 { CameraHudView(mode: hudMode, sample: CameraHud.shared.sample) }
            }
            .overlay { if chrome.horizon { HorizonLevel() } }
            .overlay { focusLayer(in: size) }
            .overlay(alignment: .topTrailing) { lockPill }
            .overlay(alignment: .top) {
                if chrome.mode == .oldstyle && OldstyleModel.shared.open && OldstyleModel.shared.tab == 0 {
                    OldstyleHud().padding(.top, 12)
                }
            }
            .overlay {
                // the self-timer's seconds, large, in the middle
                if chrome.countdown > 0 {
                    Text("\(chrome.countdown)").font(.app(36)).foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.5), radius: 4).allowsHitTesting(false)
                        .turnsWithPhone()
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
            .onAppear { FrameAnalysis.shared.setViewSize(size) }
            .onChange(of: size) { FrameAnalysis.shared.setViewSize(size) }
    }

    /// «Закреплено» (SIMPLE_UI §1): a 20 pt accent pill at the preview's top right while a long press holds focus and exposure
    @ViewBuilder private var lockPill: some View {
        if camera.locked {
            HStack(spacing: 4) {
                Image("kb_ui_ic_lock_rest").renderingMode(.template).resizable().frame(width: 12, height: 12)
                Text(L("focus.locked")).font(.app(11, 500))
            }
            .foregroundStyle(.black)
            .padding(.leading, 6).padding(.trailing, 8).frame(height: 20)
            .background(CameraStyle.accent, in: RoundedRectangle(cornerRadius: 10))
            .padding(.top, 12).padding(.trailing, 8)
            .allowsHitTesting(false)
            .turnsWithPhone()
            .accessibilityLabel(L("focus.locked_description"))
        }
    }

    // MARK: the viewfinder's gestures (Swipe / TouchFocus / SubjectFocus)

    private var trackingMode: String { store.string("pref_tracking_af_mode", "long_press") }
    private var splitAfAe: Bool { store.bool("pref_split_af_ae", false) }

    /// A tap: in FULL it lowers the shade, with the LUT tray open it closes the tray; in «tap» tracking it starts / stops tracking;
    /// on the ring it goes back to the camera's own focus and metering; elsewhere it focuses and meters there (a manual focus takes
    /// no tap, as Android's FOCUS_AUTO check)
    private func tap(at point: CGPoint, in size: CGSize) {
        if shade.level == .full { shade.set(.peek); return }
        if lut.open { withAnimation(CameraMotion.exit) { lut.open = false }; return }
        guard !ManualSettings.load().focusOn else { return }
        if trackingMode == "tap" {
            if FrameAnalysis.shared.tracking { FrameAnalysis.shared.stopTracking(); return }
            Haptics.longPress()
            hideRing(now: true)
            FrameAnalysis.shared.startTracking(at: point, view: size)
            return
        }
        // a running track gives way to the tap focus
        if FrameAnalysis.shared.tracking { FrameAnalysis.shared.stopTracking() }
        if focus.visible, let p = focus.point, hypot(p.x - point.x, p.y - point.y) <= FocusMark.radius {
            hideRing(now: true)
            exposurePoint = nil
            Task { await camera.resetFocus() }
            return
        }
        showRing(at: point, locked: false)
        exposurePoint = splitAfAe ? point : nil
        Task { await camera.focus(at: point) }
    }

    /// A long press tracks the subject («Следить за объектом» on long press) or holds focus and exposure there («Закреплено»)
    private func longPress(at point: CGPoint, in size: CGSize) {
        guard shade.level != .full, !ManualSettings.load().focusOn else { return }
        if trackingMode == "long_press" {
            hideRing(now: true)
            Task { await camera.resetFocus() }
            FrameAnalysis.shared.startTracking(at: point, view: size)
            return
        }
        FrameAnalysis.shared.stopTracking()
        showRing(at: point, locked: true)
        Task { await camera.lock(at: point) }
    }

    private func pinch(_ scale: CGFloat, began: Bool) {
        guard shade.level != .full else { return }
        if began { pinchStart = camera.zoom }
        let target = pinchStart * Double(scale)
        Task {
            await camera.zoom(to: target)
            ZoomReadoutModel.shared.show(camera.zoom)
        }
    }

    /// Swipe.Router: horizontal (|dx| > 60 pt and 1.5 |dy|) the next / previous LUT with its wipe while the shade is down; up the
    /// shade a level up; down from the top zone (the top row and the viewfinder's upper half) with the shade down and the LUT tray
    /// closed the «Верхняя строка» sheet, else the shade a level down
    private func fling(_ t: CGPoint, _ v: CGPoint, _ start: CGPoint, _ view: CameraPreview.PreviewView, size: CGSize) {
        if topRowSheet { if t.y < 0 && abs(t.y) > abs(t.x) { withAnimation { topRowSheet = false } }; return }
        if abs(t.x) > 1.5 * abs(t.y) {
            guard abs(t.x) > 60, abs(v.x) > 100, shade.level == .hidden else { return }
            let direction = t.x > 0 ? -1 : 1
            view.wipe(direction: direction) {
                let text = lut.step(direction)
                ViewfinderRenderer.shared.refreshNow()
                MessageCenter.shared.info(text)
            }
            return
        }
        guard abs(t.y) > abs(t.x), abs(t.y) > 100, abs(v.y) > 100 else { return }
        if t.y < 0 {
            withAnimation(CameraMotion.exit) { lut.open = false }
            shade.up()
        } else if shade.level == .hidden && !lut.open && start.y <= size.height / 2 {
            openTopRow()
        } else {
            shade.down()
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

    /// The ring and the exposure sun beside it (CameraFragment.attachExposureSun: hidden while ISO and shutter are both manual); with
    /// «Раздельные фокус и экспозиция» the exposure square around the ring, dragged on its own
    @ViewBuilder private func focusLayer(in size: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            if let e = exposurePoint, splitAfAe {
                ExposureSquare()
                    .position(e)
                    .gesture(DragGesture(minimumDistance: 0)
                        .onChanged { d in
                            let p = CGPoint(x: min(max(d.location.x, 0), size.width), y: min(max(d.location.y, 0), size.height))
                            exposurePoint = p
                            focus.hide?.cancel()
                        }
                        .onEnded { _ in
                            if let p = exposurePoint { Haptics.tick(); Task { await camera.expose(at: p) } }
                        })
            }
            if focus.visible, let p = focus.point {
                let r = FocusMark.radius
                let manual = ManualSettings.load()
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
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
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

    // MARK: the bottom bar

    private var shutterEnabled: Bool { camera.shutterAvailable }

    /// The shutter button, and the volume buttons (CameraActivity.dispatchKeyEvent: a click of the shutter while it can be clicked)
    private func pressShutter() {
        guard shutterEnabled || chrome.counting else { return }
        chrome.shoot { await camera.capture() }
    }

    /// camera_bottom_bar: from the bottom edge up - the shade's grabber, the shutter row with the bottom row (lifted 18 pt), the
    /// lens strip 16 pt over it and the message band over the strip
    /// The bar's height with Android's gaps, and with them closed up
    static let barNeed: CGFloat = 282, barCompact: CGFloat = 260

    private func bottomBar(compact: Bool) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            ZStack {
                MessageCardView(onHistory: { history = true })
                    .opacity(ZoomReadoutModel.shared.visible ? 0 : 1)
                if ZoomReadoutModel.shared.visible {
                    ZoomReadout(range: camera.zoomRange, modules: camera.sideModules.map(\.zoom), zoom: camera.zoom)
                        .transition(.opacity)
                }
            }
            .frame(height: 26)
            .padding(.bottom, 8)
            LensStrip(camera: camera, onZoomChange: { ZoomReadoutModel.shared.show($0) })
                .padding(.horizontal, 6)
                .padding(.bottom, compact ? 8 : 16)
            shutterRow
            bottomRow.padding(.top, compact ? 10 : 18)
            moreGrabber.padding(.top, compact ? 2 : 8)
        }
        .foregroundStyle(.white)
    }

    /// The shutter row (P25): the gallery card 52 pt in from the left, the shutter in the exact centre, the switch 52 pt in from the right
    private var shutterRow: some View {
        ZStack {
            HStack {
                galleryCard.padding(.leading, 52)
                Spacer()
                flipButton.padding(.trailing, 52)
            }
            shutterButton
        }
    }

    private var galleryCard: some View {
        ZStack {
            if let thumbnail = camera.thumbnail {
                Image(decorative: thumbnail, scale: 1).resizable().scaledToFill()
                    .frame(width: 58, height: 58)
                    .turnsWithPhone()
            } else {
                Image("kb_ic_shade_jpeg_rest").renderingMode(.template).resizable().frame(width: 26, height: 26).foregroundStyle(CameraStyle.accent)
            }
        }
        .frame(width: 58, height: 58)
        .background(CameraStyle.card)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Color.white.opacity(0.22), lineWidth: 1.5))
        .overlay { ProcessingRingView(radius: 18) }
        .contentShape(Rectangle())
        .onTapGesture { showingGallery = true }
        .accessibilityLabel(L("bottom.gallery"))
        .accessibilityAddTraits(.isButton)
    }

    private var shutterButton: some View {
        Button {
            pressShutter()
        } label: {
            ZStack {
                Circle().stroke(Color(red: 0.957, green: 0.953, blue: 0.969), lineWidth: 3).frame(width: 86, height: 86)
                Circle().fill(camera.activity == .idle && chrome.countdown == 0 ? Color(red: 0.957, green: 0.953, blue: 0.969) : Color(white: 0.85))
                    .frame(width: 70, height: 70)
            }
        }
        .buttonStyle(PressScale(scale: 0.95))
        // a press while the timer counts stops it (CameraUIController: the hovered shutter resets the timer)
        .disabled(!(shutterEnabled || chrome.counting))
        .accessibilityLabel(L("capture.shutter"))
    }

    /// The front / back switch: the gallery card's shape, a half turn on a tap
    private var flipButton: some View {
        Button {
            Haptics.tick()
            withAnimation(.spring(response: 0.4, dampingFraction: 0.75)) { flipTurn += 180 }
            Task { await camera.flip() }
        } label: {
            Image("cv_flip").renderingMode(.template).resizable().frame(width: 26, height: 26).foregroundStyle(CameraStyle.text)
                .rotationEffect(.degrees(flipTurn))
                .frame(width: 58, height: 58)
                .background(CameraStyle.control, in: RoundedRectangle(cornerRadius: 18))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(CameraStyle.hairline, lineWidth: 0.5))
        }
        .accessibilityLabel(L("bottom.flip"))
    }

    /// The bottom row (P574): the gear, the mode switch (icon discs in a capsule, the chosen one a lighter disc in the accent), «Про»
    /// (the accent's dark tone with an accent edge while open; a dot while a value is manual); in «Oldstyle» «Про» steps aside
    private var bottomRow: some View {
        HStack {
            Button { showingSettings = true } label: {
                Image("cv_gear").renderingMode(.template).resizable().frame(width: 22, height: 22).foregroundStyle(CameraStyle.text)
                    .frame(width: 50, height: 50)
                    .background(CameraStyle.control, in: RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(CameraStyle.hairline, lineWidth: 0.5))
                    .turnsWithPhone()
            }
            .buttonStyle(PressScale())
            .contextMenu {
                Button(L("log.share")) { sharing = SessionLog.shared.flushedFiles() }
                Button(L("info.title")) { showingInfo = true }
                Button(L("msg.history")) { history = true }
            }
            .accessibilityLabel(L("settings.open"))
            Spacer()
            HStack(spacing: 10) {
                modeButton("cv_run_m", .sport)
                modeButton("cv_photo_m", .photo)
                modeButton("cv_os_cassette", .oldstyle)
            }
            .padding(4)
            .frame(height: 52)
            .background(CameraStyle.control, in: Capsule())
            .overlay(Capsule().stroke(CameraStyle.hairline, lineWidth: 0.5))
            Spacer()
            let manual = ManualPanel.anyManual(ManualSettings.load())
            Button {
                Haptics.tick()
                withAnimation(CameraMotion.open) {
                    proOpen.toggle()
                    if proOpen { lut.open = false }
                }
            } label: {
                ZStack(alignment: .topTrailing) {
                    Image("cv_sliders").renderingMode(.template).resizable().frame(width: 22, height: 22)
                        .foregroundStyle(proOpen ? CameraStyle.accent : CameraStyle.text)
                        .frame(width: 50, height: 50)
                        .background(proOpen ? CameraStyle.accentDark : CameraStyle.control, in: RoundedRectangle(cornerRadius: 16))
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(proOpen ? CameraStyle.accent : CameraStyle.hairline, lineWidth: proOpen ? 1 : 0.5))
                    if manual && !proOpen {
                        Circle().fill(CameraStyle.accent).frame(width: 8, height: 8).padding(8)
                    }
                }
                .turnsWithPhone()
            }
            .buttonStyle(PressScale())
            .opacity(chrome.mode == .oldstyle ? 0 : 1)
            .disabled(chrome.mode == .oldstyle)
            .accessibilityLabel(L("bottom.pro"))
        }
        .padding(.horizontal, 18)
    }

    private func modeButton(_ icon: String, _ mode: CameraChrome.Mode) -> some View {
        Button {
            guard chrome.mode != mode else { return }   // a tap on the chosen mode does nothing
            Haptics.tick()
            withAnimation(CameraMotion.glide) { chrome.mode = mode }
            if mode != .oldstyle { OldstyleModel.shared.open = false }
            camera.say(mode.title)
            Task { await camera.modeChanged() }
        } label: {
            Image(icon).renderingMode(.template).resizable().frame(width: 23, height: 23)
                .foregroundStyle(chrome.mode == mode ? CameraStyle.accent : CameraStyle.text)
                .frame(width: 44, height: 44)
                .background(chrome.mode == mode ? CameraStyle.chosen : Color.clear, in: Circle())
                .turnsWithPhone()
        }
        .accessibilityLabel(mode.title)
        .accessibilityAddTraits(chrome.mode == mode ? .isSelected : [])
    }

    /// The grabber at the bottom edge (more_button): a tap or a swipe up opens the quick settings
    private var moreGrabber: some View {
        RoundedRectangle(cornerRadius: 2).fill(CameraStyle.grip).frame(width: 36, height: 4)
            .frame(width: 56, height: 18)
            .contentShape(Rectangle().inset(by: -8))
            .onTapGesture { Haptics.tick(); shade.level == .hidden ? shade.set(.peek) : shade.set(.hidden) }
            .gesture(DragGesture(minimumDistance: 6).onEnded { d in
                if d.translation.height < -12 { shade.up() } else if d.translation.height > 12 { shade.down() }
            })
            .accessibilityLabel(L("sheet.open_shade"))
            .accessibilityAddTraits(.isButton)
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
                            Text(module.deviceName).font(.app(12)).foregroundStyle(.secondary)
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
