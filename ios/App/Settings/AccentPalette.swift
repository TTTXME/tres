import SwiftUI

/// AccentPalette (circularbarlib ui/AccentPalette.java, P130): one stored accent (pref_theme_accent_key) shared by the settings, the
/// camera's controls and the gallery. «Из системы» (also the default, never chosen) is the phone's own accent on Android 12+; an
/// iPhone gives apps no system accent, so it is the camera's yellow (Android's value without a system colour) - and the camera's
/// and the gallery's own yellows stay exactly as they were.
enum AccentPalette {
    static let key = "pref_theme_accent_key"
    static let system = "system"
    static let values = [system, "lavender", "blue", "green", "amber", "red", "pink", "cyan", "white"]

    static func selected(_ values: [String: Any]) -> String { (values[key] as? String) ?? "default" }
    static func isSystem(_ v: String) -> Bool { v == system || v == "default" }

    static func name(_ v: String) -> String { L("accent." + (isSystem(v) ? system : v)) }

    /// AccentPalette.color(value)
    static func rgb(_ v: String) -> UInt32 {
        switch v {
        case "blue": return 0x90C7FF
        case "green": return 0xABE0BA
        case "amber", "orange": return 0xFFD47E
        case "red": return 0xFFA99E
        case "pink": return 0xF1ACD8
        case "cyan", "teal": return 0x93DEDF
        case "white": return 0xE7E9F0
        default: return 0xC7B5FF
        }
    }

    static func color(_ rgb: UInt32) -> Color {
        Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }

    /// The chosen accent; nil for «Из системы» (each surface keeps its own yellow). Read from the observable store, so a view that
    /// uses it is drawn again when it changes.
    static var chosen: Color? {
        let v = selected(SettingsStore.shared.values)
        return isSystem(v) ? nil : color(rgb(v))
    }

    /// AccentPalette.camera: the settings and the app take the camera's accent (yellow by default)
    static var settingsColor: Color { chosen ?? color(0xFFD447) }

    /// A swatch of the accent page
    static func swatch(_ v: String) -> Color { isSystem(v) ? color(0xFFD447) : color(rgb(v)) }

    static func select(_ v: String) { SettingsStore.shared.set(key, v) }
}

/// «Тема галереи» (pref_theme_key: -1 as the system, 1 light, 2 dark; GalleryActivity.setLocalNightMode): the gallery's system
/// surfaces (sheets, menus, alerts) take it while its own pages keep the dark look, as Android's gallery (GalleryUi's fixed colours)
/// does. «Как в системе» leaves it to the app's window, which is dark.
enum GalleryTheme {
    static var scheme: ColorScheme? {
        switch SettingsStore.shared.string("pref_theme_key", "-1") {
        case "1": return .light
        case "2": return .dark
        default: return nil
        }
    }
}

/// The app's tint: the chosen accent (AccentPalette.camera)
struct AccentTint: ViewModifier {
    func body(content: Content) -> some View { content.tint(AccentPalette.settingsColor) }
}

/// AccentSettingsFragment («Цвет оформления»): one card with the accents as a grid of three - a dot over its name, the chosen one
/// with an accent border; a tap applies it at once.
struct AccentPage: View {
    private var store: SettingsStore { SettingsStore.shared }

    var body: some View {
        let chosen = AccentPalette.selected(store.values)
        ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                ForEach(AccentPalette.values, id: \.self) { v in
                    let on = v == chosen || (AccentPalette.isSystem(v) && AccentPalette.isSystem(chosen))
                    Button { AccentPalette.select(v) } label: {
                        VStack(spacing: 8) {
                            Circle().fill(AccentPalette.swatch(v)).frame(width: 30, height: 30)
                            Text(AccentPalette.name(v)).font(.app(12.5)).foregroundStyle(.white).multilineTextAlignment(.center)
                                .lineLimit(2)
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 12).padding(.horizontal, 8)
                        .background(Color(white: 0.23), in: RoundedRectangle(cornerRadius: 16))
                        .overlay { if on { RoundedRectangle(cornerRadius: 16).strokeBorder(AccentPalette.settingsColor, lineWidth: 2) } }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(AccentPalette.name(v) + (on ? L("accent.selected") : ""))
                }
            }
            .padding(14)
            .background(Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255), in: RoundedRectangle(cornerRadius: 24))
            .padding(16)
        }
        .navigationTitle(L("accent.title"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
