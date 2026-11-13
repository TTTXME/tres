import SwiftUI
import UIKit

struct CameraView: View {
    let camera: CameraModel
    @State private var sharing: [URL]?
    @State private var showingInfo = false
    @State private var showingSettings = false

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
            SettingsView()
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

    private var topBar: some View {
        HStack(spacing: 18) {
            Menu {
                Picker(L("raw.title"), selection: Binding(get: { camera.raw }, set: { camera.setRaw($0) })) {
                    ForEach(RawChoice.allCases) { Text($0.title).tag($0) }
                }
            } label: {
                pill(camera.raw == .off ? L("raw.short_off") : camera.raw.title, active: camera.raw != .off)
            }
            Button {
                camera.setShdr(!camera.shdr)
            } label: {
                pill("SHDR", active: camera.shdr)
            }
            .accessibilityLabel(L("shdr.hint"))
            if camera.raw != .off && !camera.shdr {
                Button {
                    camera.setWhyTone(!camera.whyTone)
                } label: {
                    pill(L("why.title"), active: camera.whyTone)
                }
                .accessibilityLabel(L("why.hint"))
            }
            if camera.supportsFullResolution || camera.fullResolution {
                Button {
                    Task { await camera.setFullResolution(!camera.fullResolution) }
                } label: {
                    pill(L("fullres.title"), active: camera.fullResolution && camera.supportsFullResolution)
                }
            }
            Spacer()
            Button {
                sharing = SessionLog.shared.flushedFiles()
            } label: {
                Image(systemName: "doc.text").font(.title3)
            }
            .accessibilityLabel(L("log.share"))
            Button { showingInfo = true } label: { Image(systemName: "info.circle").font(.title3) }
                .accessibilityLabel(L("info.title"))
            Button { showingSettings = true } label: { Image(systemName: "gearshape").font(.title3) }
                .accessibilityLabel(L("settings.open"))
        }
        .foregroundStyle(.white)
        .padding(.top, 8)
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

    private var bottomBar: some View {
        HStack {
            Group {
                if let thumbnail = camera.thumbnail {
                    Image(decorative: thumbnail, scale: 1).resizable().scaledToFill()
                } else {
                    Color.white.opacity(0.1)
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            Spacer()
            Button {
                Task { await camera.capture() }
            } label: {
                ZStack {
                    Circle().stroke(.white, lineWidth: 4).frame(width: 76, height: 76)
                    Circle().fill(camera.activity == .idle ? Color.white : Color.gray).frame(width: 62, height: 62)
                    if camera.activity != .idle { ProgressView().tint(.black) }
                }
            }
            .disabled(camera.activity != .idle || camera.status != .running)
            .accessibilityLabel(L("capture.shutter"))
            Spacer()
            Color.clear.frame(width: 56, height: 56)
        }
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
