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
    }

    private var content: some View {
        VStack(spacing: 12) {
            topBar
            GeometryReader { geometry in
                CameraPreview(source: camera.previewSource)
                    .onTapGesture(coordinateSpace: .local) { point in
                        Task { await camera.focus(at: point) }
                    }
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .overlay { ViewfinderOverlay(grid: chrome.grid, horizon: chrome.horizon) }
                    .overlay(alignment: .bottom) { messageCard }
                    .overlay {
                        if camera.status == .interrupted {
                            Text(L("camera.interrupted")).padding().background(.ultraThinMaterial, in: Capsule())
                        }
                    }
            }
            .aspectRatio(3.0 / 4.0, contentMode: .fit)
            moduleRow
            bottomBar
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
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
            topIcon("cv_bolt", active: camera.torch) { Task { await camera.toggleTorch() } }
                .accessibilityLabel(L("top.flash"))
            ZStack(alignment: .topTrailing) {
                topIcon("cv_timer", active: chrome.timerSeconds > 0) { chrome.cycleTimer() }
                if chrome.timerSeconds > 0 {
                    Text("\(chrome.timerSeconds)").font(.system(size: 9, weight: .bold)).padding(.horizontal, 3)
                        .background(Color.yellow, in: Capsule()).foregroundStyle(.black)
                }
            }
            .accessibilityLabel(L("top.timer"))
            topIcon("cv_grid", active: chrome.grid) { chrome.grid.toggle() }.accessibilityLabel(L("top.grid"))
            topIcon("cv_level", active: chrome.horizon) { chrome.horizon.toggle() }.accessibilityLabel(L("top.level"))
            topIcon("cv_sparkle", active: chrome.ultraHdr) { chrome.ultraHdr.toggle() }.accessibilityLabel(L("top.uhdr"))
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
                .foregroundStyle(active ? Color.yellow : Color.white)
                .frame(width: 40, height: 40)
        }
    }

    private var moduleRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(camera.modules) { module in
                    Button {
                        Task { await camera.select(module) }
                    } label: {
                        Text(module.label)
                            .font(.system(size: 14, weight: .semibold))
                            .frame(minWidth: 44, minHeight: 36)
                            .padding(.horizontal, 6)
                            .background(module.id == camera.activeModuleID ? Color.yellow : Color.white.opacity(0.15), in: Capsule())
                            .foregroundStyle(module.id == camera.activeModuleID ? Color.black : Color.white)
                    }
                    .accessibilityLabel(module.deviceName + " " + module.label)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    // layout_bottombuttons: gallery, shutter, flip; then settings, the mode switch (Спорт / Фото / Oldstyle) and «Про»
    private var bottomBar: some View {
        VStack(spacing: 14) {
            HStack {
                Group {
                    if let thumbnail = camera.thumbnail {
                        Image(decorative: thumbnail, scale: 1).resizable().scaledToFill()
                    } else {
                        Image("ic_shade_jpeg").renderingMode(.template).resizable().frame(width: 26, height: 26).foregroundStyle(.white.opacity(0.6))
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
                    chrome.shoot { await camera.capture() }
                } label: {
                    ZStack {
                        Circle().stroke(Color(red: 0.957, green: 0.953, blue: 0.969), lineWidth: 3).frame(width: 86, height: 86)
                        Circle().fill(camera.activity == .idle && chrome.countdown == 0 ? Color(red: 0.957, green: 0.953, blue: 0.969) : Color(white: 0.85))
                            .frame(width: 70, height: 70)
                        if chrome.countdown > 0 { Text("\(chrome.countdown)").font(.title.bold()).foregroundStyle(.black) }
                    }
                }
                .disabled(camera.activity != .idle || camera.status != .running || chrome.countdown > 0)
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
        Button { chrome.mode = mode } label: {
            Image(icon).renderingMode(.template).resizable().frame(width: 24, height: 24)
                .foregroundStyle(chrome.mode == mode ? Color.black : Color.white)
                .frame(width: 44, height: 44)
                .background(chrome.mode == mode ? Color.yellow : Color.clear, in: Circle())
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

    private func pill(_ text: String, active: Bool) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .bold))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(active ? Color.yellow : Color.white.opacity(0.15), in: Capsule())
            .foregroundStyle(active ? Color.black : Color.white)
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
