import SwiftUI

/// CameraSheets.formatAndSize (P574 / P583 / P592): one floating sheet (#1C1C1E, 36 pt corners, 8 pt from the screen's sides and
/// bottom, the grab) for the top row's «JPEG / 12 Мп» badge - the tabs «Формат | Размер» over the format's two controls or the size
/// rows; a choice never closes it. «Фото»: the codecs the iPhone writes (JPEG, HEIC; WebP and AVIF need an encoder the app does
/// not carry - a line says so). «RAW (DNG)»: Нет / + RAW / Только RAW («Только RAW» dims the photo control, which keeps its choice
/// for «+ RAW»). What each choice means is its long press. The sizes: «Обычный · 12 Мп» and «Подробный · 48 Мп» where the lens
/// has its full resolution, the chosen one with the accent tick; a size the lens lacks is not listed.
struct FormatSizeSheet: View {
    let camera: CameraModel
    @State var tab: Int
    @State private var help: (String, String)?
    private var store: SettingsStore { SettingsStore.shared }

    var body: some View {
        VStack(spacing: 0) {
            SheetGrab().padding(.bottom, 12)
            CameraSegmented(labels: [L("sheet.format"), L("sheet.size")], selected: tab) { tab = $0 }
                .padding(.bottom, 12)
            ZStack {
                if tab == 0 {
                    formatPage.transition(.asymmetric(insertion: .opacity.combined(with: .offset(x: -16)), removal: .opacity))
                } else {
                    sizePage.transition(.asymmetric(insertion: .opacity.combined(with: .offset(x: 16)), removal: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.24), value: tab)
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 18)
        .background(RoundedRectangle(cornerRadius: 36).fill(CameraStyle.control))
        .padding(.horizontal, 8).padding(.bottom, 8)
        .frame(maxHeight: .infinity, alignment: .bottom)
        .presentationBackground(.clear)
        .presentationDetents([.height(tab == 0 ? 410 : 300)])
        .presentationDragIndicator(.hidden)
        .preferredColorScheme(.dark)
        .sheet(isPresented: Binding(get: { help != nil }, set: { if !$0 { help = nil } })) {
            if let help { SettingHelpSheet(title: help.0, text: help.1) }
        }
    }

    private var formatPage: some View {
        let mode = FormatState.rawMode(camera)
        let photo = store.string("pref_photo_format", "jpeg") == "heic" ? 1 : 0
        return VStack(alignment: .leading, spacing: 0) {
            groupTitle(L("sheet.photo"))
            CameraSegmented(labels: ["JPEG", "HEIC"], selected: photo, disabled: mode == FormatState.rawOnly) {
                store.set("pref_photo_format", $0 == 1 ? "heic" : "jpeg")
            }
            .onLongPressGesture(minimumDuration: 0.5) { help = (L("sheet.photo"), L("sheet.photo_help")) }
            .accessibilityLabel(L("sheet.photo"))
            .accessibilityValue(mode == FormatState.rawOnly ? L("sheet.photo_not_saved") : "")
            Text(L("sheet.no_webp")).font(.app(12)).foregroundStyle(CameraStyle.summary).padding(.leading, 6).padding(.top, 6)
            groupTitle("RAW (DNG)")
            CameraSegmented(labels: [L("sheet.raw_none"), "+ RAW", L("sheet.raw_only")], selected: mode) {
                FormatState.setRawMode($0, camera: camera)
            }
            .onLongPressGesture(minimumDuration: 0.5) { help = ("RAW (DNG)", L("sheet.raw_help")) }
            .padding(.bottom, 12)
            // the iPhone's RAW kind (its own setting beside pref_save_raw_key): Bayer RAW for the SHDR merge, or Apple's ProRAW
            groupTitle(L("sheet.raw_kind"))
            CameraSegmented(labels: [RawChoice.bayer.title, RawChoice.proRAW.title], selected: camera.rawKind == .proRAW ? 1 : 0,
                            disabled: mode == FormatState.none) {
                camera.setRawKind($0 == 1 ? .proRAW : .bayer)
            }
            .onLongPressGesture(minimumDuration: 0.5) { help = (L("sheet.raw_kind"), L("sheet.raw_kind_help")) }
            .accessibilityLabel(L("sheet.raw_kind"))
            .padding(.bottom, 12)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("sheet.saved") + FormatState.summary(camera))
    }

    private var sizePage: some View {
        let full = camera.fullResolution && camera.supportsFullResolution
        return VStack(spacing: 0) {
            sizeRow(L("sheet.size_normal") + "12" + L("unit.mp"), about: L("sheet.size_normal_help"), on: !full) {
                Task { await camera.setFullResolution(false) }
            }
            if camera.supportsFullResolution {
                Rectangle().fill(CameraStyle.line).frame(height: 1)
                sizeRow(L("sheet.size_full") + "48" + L("unit.mp"), about: L("sheet.size_full_help"), on: full) {
                    Task { await camera.setFullResolution(true) }
                }
            }
        }
        .background(CameraStyle.row, in: RoundedRectangle(cornerRadius: 24))
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }

    private func sizeRow(_ title: String, about: String, on: Bool, pick: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            Text(title).font(.app(16, 600)).foregroundStyle(CameraStyle.text)
            Spacer(minLength: 0)
            Image("cv_check").renderingMode(.template).resizable().scaledToFit().frame(width: 20, height: 20)
                .foregroundStyle(CameraStyle.accent).opacity(on ? 1 : 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .frame(minHeight: 60)
        .contentShape(Rectangle())
        .onTapGesture { Haptics.tick(); pick() }
        .onLongPressGesture(minimumDuration: 0.5) { help = (title, about + "\n\n" + L("sheet.size_lens")) }
        .accessibilityLabel(title)
        .accessibilityValue(on ? L("sheet.selected") : "")
        .accessibilityAddTraits(.isButton)
    }

    /// A group title: 12 pt semibold caps, letter-spaced, white 55 %
    private func groupTitle(_ text: String) -> some View {
        Text(text.uppercased()).font(.app(12, 600)).kerning(1).foregroundStyle(Color.white.opacity(0.55))
            .padding(.leading, 6).padding(.top, 4).padding(.bottom, 8)
    }
}

/// SettingHelp's sheet: a parameter's title and what it does
struct SettingHelpSheet: View {
    let title: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SheetGrab().frame(maxWidth: .infinity)
            Text(title).font(.app(16.5, 600)).foregroundStyle(CameraStyle.text)
            ScrollView { Text(text).font(.app(15)).foregroundStyle(CameraStyle.muted).lineSpacing(3).frame(maxWidth: .infinity, alignment: .leading) }
        }
        .padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 18)
        .presentationBackground(CameraStyle.control)
        .presentationDetents([.medium])
        .presentationDragIndicator(.hidden)
        .preferredColorScheme(.dark)
    }
}
