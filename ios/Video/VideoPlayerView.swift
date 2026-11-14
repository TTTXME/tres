import AVFoundation
import Photos
import SwiftUI
import UniformTypeIdentifiers

/// The gallery's video player screen (Android VideoPlayerActivity's layout, P579 style): at the top back, the name, subtitles and
/// «Ещё»; under it the quick buttons (repeat, speed, screenshot, rotation, sleep timer, sound); at the bottom the seek bar with
/// the time and the length (a tap: what remains), lock, previous / play / next and the picture fit; the HUD in the middle, the
/// pill at the bottom; the panels (speed, sleep, subtitles, sound track, more, player settings, details) as sheets.
@MainActor
struct VideoPlayerView: View {
    let library: GalleryLibrary
    @State private var model: VideoPlayerModel
    @State private var panel: Panel?
    @State private var share: ShareFiles?
    @State private var confirmDelete = false
    @State private var pickingSubs = false
    @State private var detailRows: [(String, String)] = []
    @Environment(\.dismiss) private var dismiss

    enum Panel: Int, Identifiable {
        case speed, sleep, subtitles, audio, more, settings, details
        var id: Int { rawValue }
    }

    init(library: GalleryLibrary, items: [PHAsset], index: Int) {
        self.library = library
        _model = State(initialValue: VideoPlayerModel(items: items, index: index))
    }

    var body: some View {
        GeometryReader { geo in
            let turned = model.landscape
            content
                .frame(width: turned ? geo.size.height : geo.size.width, height: turned ? geo.size.width : geo.size.height)
                .rotationEffect(.degrees(turned ? 90 : 0))
                .frame(width: geo.size.width, height: geo.size.height)
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .onChange(of: panel) { _, p in model.panelOpen = p != nil }
        .sheet(item: $panel) { p in
            panelView(p)
                .presentationDetents([.medium, .large])
                .presentationBackground(GalleryStyle.page)
        }
        .sheet(item: $share) { s in ActivitySheet(items: s.urls) }
        .fileImporter(isPresented: $pickingSubs, allowedContentTypes: Self.subtitleTypes) { result in
            if case .success(let url) = result { model.loadSubtitleFile(url) }
        }
        .confirmationDialog(L("video.delete_title"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("gallery.delete"), role: .destructive) { deleteCurrent() }
            Button(L("gallery.cancel"), role: .cancel) {}
        }
    }

    static var subtitleTypes: [UTType] {
        [UTType(filenameExtension: "srt"), UTType(filenameExtension: "vtt"), UTType.plainText].compactMap { $0 }
    }

    // ---------------------------------------------------------------- the picture and what lies over it

    private var content: some View {
        ZStack {
            Color.black
            VideoSurface(player: model.player, videoSize: model.videoSize, fit: model.fit, zoom: model.zoom, pan: model.pan,
                         onTap: { withAnimation(.easeOut(duration: 0.2)) { model.tap() } },
                         onDoubleTap: { model.doubleTap(at: $0) },
                         onDrag: { kind, dx, dy, ended in model.drag(kind, dx: dx, dy: dy, ended: ended) },
                         onPinch: { f, d, ended in model.pinch(f, delta: d, ended: ended) },
                         onHold: { model.hold($0) })
            if let text = model.subtitle {
                VStack {
                    Spacer()
                    Text(text).font(.app(model.subtitlePoints, 500)).foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .shadow(color: .black, radius: 2, x: 0, y: 1)
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(Color.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 6))
                        .padding(.bottom, model.controls ? 150 : 36)
                }
                .padding(.horizontal, 16)
                .allowsHitTesting(false)
            }
            if model.controls && !model.locked {
                controlsLayer.transition(.opacity)
            }
            if model.locked && model.unlockShown {
                VStack {
                    Spacer()
                    RoundIconButton(icon: "kb_ui_ic_unlock_rest", label: L("video.unlock"), background: GalleryStyle.bar) {
                        withAnimation { model.setLocked(false) }
                    }
                    .padding(.bottom, 40)
                }
            }
            if let hud = model.hud { hudView(hud).allowsHitTesting(false) }
            if let pill = model.pill {
                VStack {
                    Spacer()
                    HStack(spacing: 10) {
                        Text(pill.text).font(.app(13, 600)).foregroundStyle(.white)
                        if let action = pill.action {
                            Button(action) { model.pillAction() }
                                .font(.app(13, 700)).foregroundStyle(GalleryStyle.accent)
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(GalleryStyle.bar, in: Capsule())
                    .padding(.bottom, model.controls ? 160 : 40)
                }
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.18), value: model.hud)
        .animation(.easeOut(duration: 0.2), value: model.pill)
        .clipped()
    }

    private func hudView(_ hud: VideoPlayerModel.Hud) -> some View {
        VStack(spacing: 6) {
            GalleryIcon(name: hud.icon, size: 30)
            Text(hud.title).font(.app(22, 600).monospacedDigit()).foregroundStyle(.white)
            if let detail = hud.detail {
                Text(detail).font(.app(13)).foregroundStyle(GalleryStyle.muted).multilineTextAlignment(.center)
            }
            if let level = hud.level {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.2))
                        Capsule().fill(GalleryStyle.accent).frame(width: g.size.width * CGFloat(min(1, max(0, level))))
                    }
                }
                .frame(width: 120, height: 4)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 14)
        .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 20))
    }

    // ---------------------------------------------------------------- the controls

    private var controlsLayer: some View {
        VStack(spacing: 0) {
            topBar
            quickRow
            Spacer()
            bottomBar
        }
        .background {
            VStack(spacing: 0) {
                LinearGradient(colors: [Color.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).frame(height: 150)
                Spacer()
                LinearGradient(colors: [.clear, Color.black.opacity(0.6)], startPoint: .top, endPoint: .bottom).frame(height: 190)
            }
            .allowsHitTesting(false)
        }
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            RoundIconButton(icon: "ui_ic_back", label: L("video.back"), background: GalleryStyle.bar) { dismiss() }
            VStack(alignment: .leading, spacing: 1) {
                Text(model.title).font(.app(15, 600)).foregroundStyle(.white).lineLimit(1)
                if model.items.count > 1 {
                    Text("\(model.index + 1) / \(model.items.count)").font(.app(12)).foregroundStyle(GalleryStyle.muted)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 4).background(GalleryStyle.bar, in: Capsule())
            Spacer(minLength: 4)
            RoundIconButton(icon: "kb_ui_ic_subtitles_rest", label: L("video.subtitles"),
                            background: model.subsChoice != .off ? GalleryStyle.accent.opacity(0.35) : GalleryStyle.bar) { panel = .subtitles }
            RoundIconButton(icon: "ui_ic_more", label: L("gallery.more"), background: GalleryStyle.bar) { panel = .more }
        }
        .padding(.horizontal, 12).padding(.top, 8)
    }

    private var quickRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                quick(model.repeatMode == .one ? "ui_ic_repeat_one" : "ui_ic_repeat", model.repeatMode == .off ? L("video.repeat") : model.repeatName,
                      on: model.repeatMode != .off) { model.cycleRepeat() }
                quickText(VideoPlayerModel.speedLabel(model.speed), label: L("video.speed"), on: model.speed != 1) { panel = .speed }
                quick("kb_ui_ic_screenshot_rest", L("video.screenshot"), on: false) { model.screenshot() }
                quick("kb_ui_ic_rotate_rest", model.rotateName, on: model.rotate != .video) { model.cycleRotate() }
                quick("ui_ic_moon", model.sleepLabel, on: model.sleepAt != nil || model.sleepAtEnd) { panel = .sleep }
                quick(model.muted ? "kb_ui_ic_mute_rest" : "kb_ui_ic_sound_rest", model.muted ? L("video.muted") : L("video.sound"),
                      on: model.muted) { model.toggleMute() }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
        }
    }

    private func quick(_ icon: String, _ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                GalleryIcon(name: icon, size: 18, color: on ? GalleryStyle.ink : .white)
                Text(label).font(.app(12, 600)).foregroundStyle(on ? GalleryStyle.ink : .white).lineLimit(1)
            }
            .padding(.horizontal, 12).frame(height: 34)
            .background(on ? GalleryStyle.accent : GalleryStyle.bar, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func quickText(_ text: String, label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.app(13, 700).monospacedDigit()).foregroundStyle(on ? GalleryStyle.ink : .white)
                .padding(.horizontal, 12).frame(height: 34)
                .background(on ? GalleryStyle.accent : GalleryStyle.bar, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label + " " + text)
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Text(VideoResume.clock(Int64(model.position * 1000))).font(.app(12, 600).monospacedDigit())
                    .foregroundStyle(.white).frame(minWidth: 44, alignment: .leading)
                Slider(value: Binding(get: { min(model.position, max(model.duration, 0.01)) }, set: { model.scrub(to: $0) }),
                       in: 0...max(model.duration, 0.01),
                       onEditingChanged: { model.scrubbing($0) })
                    .tint(GalleryStyle.accent)
                    .disabled(!model.prepared)
                    .accessibilityLabel(L("video.seek"))
                Button {
                    model.showRemaining.toggle()
                } label: {
                    Text(model.showRemaining ? "-" + VideoResume.clock(Int64(max(0, model.duration - model.position) * 1000))
                         : VideoResume.clock(Int64(model.duration * 1000)))
                        .font(.app(12, 600).monospacedDigit()).foregroundStyle(.white)
                        .frame(minWidth: 44, alignment: .trailing)
                }
                .buttonStyle(.plain)
            }
            HStack {
                RoundIconButton(icon: "kb_ui_ic_lock_rest", label: L("video.lock"), background: GalleryStyle.bar) {
                    withAnimation { model.setLocked(true) }
                }
                Spacer()
                RoundIconButton(icon: "ui_ic_prev", label: L("video.previous"), background: GalleryStyle.bar) { model.previous() }
                Button { model.togglePlay() } label: {
                    GalleryIcon(name: model.playing ? "ui_ic_pause_bars" : "ui_ic_play", size: 30, color: GalleryStyle.ink)
                        .frame(width: 64, height: 64).background(Color.white, in: Circle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 18)
                .accessibilityLabel(model.playing ? L("video.pause") : L("video.play"))
                RoundIconButton(icon: "ui_ic_next", label: L("video.next"), enabled: model.hasNext, background: GalleryStyle.bar) { model.next() }
                Spacer()
                RoundIconButton(icon: "kb_ui_ic_aspect_rest", label: L("video.fit"), background: GalleryStyle.bar) { model.cycleFit() }
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 14)
    }

    // ---------------------------------------------------------------- the panels

    @ViewBuilder private func panelView(_ p: Panel) -> some View {
        NavigationStack {
            List {
                switch p {
                case .speed: speedPanel
                case .sleep: sleepPanel
                case .subtitles: subtitlesPanel
                case .audio: audioPanel
                case .more: morePanel
                case .settings: settingsPanel
                case .details: detailsPanel
                }
            }
            .scrollContentBackground(.hidden)
            .background(GalleryStyle.page)
            .navigationTitle(title(p))
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
    }

    private func title(_ p: Panel) -> String {
        switch p {
        case .speed: return L("video.speed")
        case .sleep: return L("video.sleep")
        case .subtitles: return L("video.subtitles")
        case .audio: return L("video.audio")
        case .more: return L("gallery.more")
        case .settings: return L("video.settings")
        case .details: return L("video.details")
        }
    }

    private func option(_ text: String, sub: String? = nil, chosen: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(text).foregroundStyle(.white)
                    if let sub { Text(sub).font(.app(13)).foregroundStyle(GalleryStyle.muted) }
                }
                Spacer()
                if chosen { GalleryIcon(name: "ui_ic_check", size: 18, color: GalleryStyle.accent) }
            }
        }
        .listRowBackground(GalleryStyle.card)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }

    private func actionRow(_ icon: String, _ text: String, color: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                GalleryIcon(name: icon, size: 20, color: color)
                Text(text).foregroundStyle(color)
            }
        }
        .listRowBackground(GalleryStyle.card)
    }

    /// 0.25x..4x in 0.05 steps, the presets, back to 1x
    @ViewBuilder private var speedPanel: some View {
        Section {
            HStack {
                RoundIconButton(icon: "ui_ic_minus", label: L("video.slower")) { model.setSpeed(model.speed - 0.05) }
                Spacer()
                Text(VideoPlayerModel.speedLabel(model.speed)).font(.app(26, 600).monospacedDigit())
                Spacer()
                RoundIconButton(icon: "ui_ic_plus", label: L("video.faster")) { model.setSpeed(model.speed + 0.05) }
            }
            .buttonStyle(.plain)
            Slider(value: Binding(get: { Double(model.speed) }, set: { model.setSpeed(Float($0)) }), in: 0.25...4, step: 0.05)
                .tint(GalleryStyle.accent)
                .accessibilityLabel(L("video.speed"))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(VideoPlayerModel.speeds, id: \.self) { v in
                        GalleryChip(text: VideoPlayerModel.speedLabel(v), on: abs(model.speed - v) < 0.001) { model.setSpeed(v) }
                    }
                }
            }
        }
        .listRowBackground(GalleryStyle.card)
        Section { actionRow("kb_ui_ic_undo_rest", L("video.speed_reset")) { model.setSpeed(1) } }
    }

    @ViewBuilder private var sleepPanel: some View {
        option(L("video.sleep_off"), chosen: model.sleepAt == nil && !model.sleepAtEnd) { model.setSleep(0); panel = nil }
        ForEach(VideoPlayerModel.sleepMinutes, id: \.self) { m in
            option(String(format: L("video.minutes"), m), chosen: false) { model.setSleep(m); panel = nil }
        }
        option(L("video.sleep_end"), chosen: model.sleepAtEnd) { model.setSleep(-1); panel = nil }
    }

    @ViewBuilder private var subtitlesPanel: some View {
        Section {
            option(L("video.subs_off"), chosen: model.subsChoice == .off) { model.selectSubs(.off) }
            ForEach(Array(model.legible.enumerated()), id: \.offset) { i, o in
                option(VideoPlayerModel.optionName(o, i, prefix: L("video.subs_builtin")), sub: o.displayName,
                       chosen: model.subsChoice == .track(i)) { model.selectSubs(.track(i)) }
            }
            if let name = model.pickedName {
                option(L("video.subs_picked"), sub: name, chosen: model.subsChoice == .file) { model.selectSubs(.file) }
            }
            actionRow("kb_ui_ic_folder_rest", L("video.subs_open")) {
                panel = nil
                pickingSubs = true
            }
        }
        Section(L("video.subs_size")) {
            HStack(spacing: 8) {
                ForEach(0..<3, id: \.self) { k in
                    GalleryChip(text: [L("video.subs_small"), L("video.subs_medium"), L("video.subs_large")][k], on: model.subtitleSize == k) {
                        model.subtitleSize = k
                        panel = nil
                    }
                }
            }
            .listRowBackground(GalleryStyle.card)
        }
    }

    @ViewBuilder private var audioPanel: some View {
        if model.audible.isEmpty {
            Text(L("video.no_sound")).foregroundStyle(GalleryStyle.muted).listRowBackground(GalleryStyle.card)
        } else {
            ForEach(Array(model.audible.enumerated()), id: \.offset) { i, o in
                option(VideoPlayerModel.optionName(o, i, prefix: L("video.track")), sub: o.displayName,
                       chosen: model.audioChoice == i && !model.muted) { model.selectAudio(i) }
            }
            option(L("video.sound_off"), chosen: model.audioChoice == nil || model.muted) { model.selectAudio(nil) }
        }
    }

    @ViewBuilder private var morePanel: some View {
        actionRow("kb_ui_ic_sound_rest", L("video.audio")) { panel = .audio }
        actionRow("kb_ui_ic_subtitles_rest", L("video.subtitles")) { panel = .subtitles }
        actionRow("kb_ui_ic_info_rest", L("video.details")) {
            Task {
                detailRows = await model.details()
                panel = .details
            }
        }
        actionRow("kb_ui_ic_share_rest", L("gallery.share")) {
            panel = nil
            shareCurrent()
        }
        actionRow("kb_ui_ic_trash_rest", L("gallery.delete"), color: GalleryStyle.warn) {
            panel = nil
            confirmDelete = true
        }
        actionRow("cs_ic_sliders", L("video.settings")) { panel = .settings }
    }

    @ViewBuilder private var settingsPanel: some View {
        Section(L("video.step")) {
            Picker(L("video.step"), selection: Binding(get: { model.seekStep }, set: { model.seekStep = $0 })) {
                ForEach(VideoPlayerModel.steps, id: \.self) { s in Text("\(s)" + L("video.seconds_suffix")).tag(s) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(GalleryStyle.card)
        }
    }

    @ViewBuilder private var detailsPanel: some View {
        ForEach(Array(detailRows.enumerated()), id: \.offset) { _, row in
            VStack(alignment: .leading, spacing: 2) {
                Text(row.0).font(.app(13)).foregroundStyle(GalleryStyle.muted)
                Text(row.1).foregroundStyle(.white).textSelection(.enabled)
            }
            .listRowBackground(GalleryStyle.card)
        }
    }

    // ---------------------------------------------------------------- share and delete

    private func shareCurrent() {
        guard let a = model.current else { return }
        Task {
            if let url = await GalleryLibrary.exportVideo(a) {
                share = ShareFiles(urls: [url])
            } else {
                model.showPill(L("video.share_failed"))
            }
        }
    }

    private func deleteCurrent() {
        guard let a = model.current else { return }
        Task {
            model.pause()
            let ok = await library.delete([a])
            if !ok {
                model.showPill(L("gallery.delete_failed"))
                return
            }
            if !model.removeCurrent() { dismiss() }
        }
    }
}
