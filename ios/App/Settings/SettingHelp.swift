import SwiftUI

/// SettingHelp (ui/settings/SettingHelp.java, P319 / P592): a long press on a settings row opens its help - what it does, then
/// «Сейчас» / «По умолчанию» / «Диапазон» where the row has them and «Вернуть по умолчанию» for a changed slider. A row shows its
/// title and its value only; the description is here and nowhere else.
///
/// The text of a row: the description its builder gave it (rows built in code), else its own text of strings_help.xml (found
/// through SettingHelp's map of keys and key families, gen_settings_ui.py), else its summary - for a state row (the credits, the
/// version) the whole summary, for any other the summary without the value a «%s. …» summary began with.
///
/// The iPhone's long press is its context menu: the menu's preview is the help card, «Описание» opens the full sheet (a long text
/// scrolls there), «Вернуть по умолчанию» puts a changed slider back.
enum SettingHelp {
    struct Fact: Identifiable {
        let label: String
        let value: String
        let now: Bool
        var id: String { label }
    }

    /// Rows whose own touches take the long press (a card's strip reorder, the curve's hold-to-delete, the crop's edges, a text field)
    static let ownTouches: Set<String> = ["text", "custom:FilmLutPreference", "custom:LensFilterPreference", "custom:ToneCurvePreference",
                                          "custom:RawCropPreference", "custom:PhotoStylePreference", "custom:ShadeTransparencyPreference",
                                          "custom:WatermarkPreviewPreference", "custom:FormatPickerPreference", "custom:SizePickerPreference",
                                          "custom:ProfilesPreference", "custom:LutChangesPreference"]

    /// SettingHelp.helps: a keyed row (a page too), never a group's header
    static func helps(_ node: SettingsNode) -> Bool { node.key != nil && node.type != "category" && !ownTouches.contains(node.type) }

    static func title(_ node: SettingsNode) -> String {
        let t = SettingsCatalog.shared.text(node.title) ?? ""
        return t.isEmpty ? (node.type == "row" ? L("set.help.note") : node.key ?? "") : t
    }

    /// The row's description, the «no description yet» line when it has none
    static func text(_ node: SettingsNode, store: any SettingsValues, own: String? = nil) -> String {
        let catalog = SettingsCatalog.shared
        if let own, !own.isEmpty { return own }
        if let help = catalog.helpText(node.key) { return help }
        let summary = catalog.text(node.summary)
        if catalog.summaryIsState(node.key), let summary, !summary.isEmpty { return summary }
        return description(summary, value: currentEntry(node, store: store)) ?? L("set.help.none")
    }

    /// SettingHelp.description: a summary without the value a «%s. …» summary began with and without a placeholder left in it
    static func description(_ summary: String?, value: String?) -> String? {
        guard var s = summary, !s.isEmpty else { return nil }
        if let v = value, !v.isEmpty {
            if s == v { return nil }
            if s.hasPrefix(v + ". ") { s = String(s.dropFirst(v.count + 2)) } else if s.hasPrefix(v + "\n") { s = String(s.dropFirst(v.count + 1)) }
        }
        s = s.replacingOccurrences(of: "%(1\\$)?s", with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: "^[.\\s]+", with: "", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
        return s.isEmpty ? nil : s
    }

    /// A list's chosen entry, a text row's text
    static func currentEntry(_ node: SettingsNode, store: any SettingsValues) -> String? {
        guard let key = node.key else { return nil }
        switch node.type {
        case "list":
            let catalog = SettingsCatalog.shared
            let entries = node.inlineEntries ?? catalog.array(node.entries), values = node.inlineValues ?? catalog.array(node.values)
            let v = key == Sounds.key ? Sounds.value(store) : store.string(key, node.defaultValue?.text ?? values.first ?? "")
            return values.firstIndex(of: v).flatMap { $0 < entries.count ? entries[$0] : nil }
        case "text":
            return store.string(key, node.defaultValue?.text ?? "")
        default:
            return nil
        }
    }

    private static func onOff(_ on: Bool) -> String { on ? L("set.help.on") : L("set.help.off") }

    /// «Сейчас», «По умолчанию» and «Диапазон»: only what the row really has
    static func facts(_ node: SettingsNode, store: any SettingsValues) -> [Fact] {
        guard let key = node.key else { return [] }
        let now = L("set.help.now"), def = L("set.help.default"), range = L("set.help.range")
        var out: [Fact] = []
        switch node.type {
        case "slider":
            let d = SliderGrid.number(node.defaultValue?.text ?? "0", fallback: node.min ?? 0)
            let v = SliderGrid.number(store.string(key, node.defaultValue?.text ?? "0"), fallback: d)
            out.append(Fact(label: now, value: CameraStyle.decimal(SliderGrid.shown(v, node: node)), now: true))
            out.append(Fact(label: def, value: CameraStyle.decimal(SliderGrid.shown(d, node: node)), now: false))
            out.append(Fact(label: range, value: CameraStyle.decimal(SliderGrid.shown(node.min ?? 0, node: node)) + " … "
                            + CameraStyle.decimal(SliderGrid.shown(node.max ?? 100, node: node)), now: false))
        case "switch":
            out.append(Fact(label: now, value: onOff(store.bool(key, node.defaultValue?.bool ?? false)), now: true))
            if let d = node.defaultValue { out.append(Fact(label: def, value: onOff(d.bool), now: false)) }
        case "list":
            if let e = currentEntry(node, store: store), !e.isEmpty { out.append(Fact(label: now, value: e, now: true)) }
            let catalog = SettingsCatalog.shared
            let entries = node.inlineEntries ?? catalog.array(node.entries), values = node.inlineValues ?? catalog.array(node.values)
            if let d = node.defaultValue?.text, let i = values.firstIndex(of: d), i < entries.count {
                out.append(Fact(label: def, value: entries[i], now: false))
            }
        default:
            break
        }
        return out
    }

    /// Puts a changed slider back to its default; nil when it is at it (or the row has no such reset)
    static func reset(_ node: SettingsNode, store: any SettingsValues) -> (() -> Void)? {
        guard node.type == "slider", let key = node.key else { return nil }
        let d = SliderGrid.number(node.defaultValue?.text ?? "0", fallback: node.min ?? 0)
        let v = SliderGrid.number(store.string(key, node.defaultValue?.text ?? "0"), fallback: d)
        guard abs(v - d) > Swift.max(1e-6, abs(d) * 1e-5) else { return nil }
        return { store.set(key, SliderGrid.exact(d, decimal: node.float == true)) }
    }
}

/// The help card: the title, the text in white at 70 %, the facts' card (the value now in the accent)
struct HelpCard: View {
    let title: String
    let text: String
    let facts: [SettingHelp.Fact]
    var lineLimit: Int? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.app(18, 600)).foregroundStyle(.white).accessibilityAddTraits(.isHeader)
            Text(text).font(.app(15)).foregroundStyle(Color.white.opacity(0.7)).lineLimit(lineLimit)
                .fixedSize(horizontal: false, vertical: true)
            if !facts.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(facts.enumerated()), id: \.offset) { i, f in
                        if i > 0 { Divider().padding(.leading, 14) }
                        HStack {
                            Text(f.label).font(.app(14)).foregroundStyle(Color.white.opacity(0.55))
                            Spacer(minLength: 12)
                            Text(f.value).font(.app(14, f.now ? 600 : 500))
                                .foregroundStyle(f.now ? AnyShapeStyle(AccentPalette.settingsColor) : AnyShapeStyle(Color.white))
                                .multilineTextAlignment(.trailing)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .accessibilityElement(children: .combine)
                    }
                }
                .background(Color(white: 0.23), in: RoundedRectangle(cornerRadius: 16))
                .padding(.top, 6)
            }
        }
        .padding(20)
        .background(Color(red: 0x2C / 255, green: 0x2C / 255, blue: 0x2E / 255))
    }
}

/// The sheet of a row's help (the camera sheets' card, the grabber) with «Вернуть по умолчанию» for a changed slider
struct SettingHelpSheet: View {
    let title: String
    let text: String
    let facts: [SettingHelp.Fact]
    let reset: (() -> Void)?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HelpCard(title: title, text: text, facts: facts)
                if let reset {
                    Button {
                        reset()
                        dismiss()
                    } label: {
                        Text(L("set.help.reset")).font(.app(15, 600)).padding(.horizontal, 16).frame(minHeight: 40)
                            .background(Color(white: 0.23), in: Capsule())
                    }
                    .padding(.horizontal, 20).padding(.bottom, 22)
                }
            }
        }
        .background(Color(red: 0x2C / 255, green: 0x2C / 255, blue: 0x2E / 255))
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        .preferredColorScheme(.dark)
    }
}

/// The long press of a row (its context menu: the help card as the preview) and the help sheet (`shown`: a note's tap opens it too)
struct SettingHelpGesture: ViewModifier {
    let node: SettingsNode
    let store: any SettingsValues
    let enabled: Bool
    var own: String? = nil
    @Binding var shown: Bool

    func body(content: Content) -> some View {
        if enabled {
            content
                .contextMenu {
                    Button { shown = true } label: { Label(L("set.help.open"), systemImage: "text.alignleft") }
                    if let reset = SettingHelp.reset(node, store: store) {
                        Button(action: reset) { Label(L("set.help.reset"), systemImage: "arrow.counterclockwise") }
                    }
                } preview: {
                    HelpCard(title: SettingHelp.title(node), text: SettingHelp.text(node, store: store, own: own),
                             facts: SettingHelp.facts(node, store: store), lineLimit: 14)
                        .frame(width: 340)
                        .preferredColorScheme(.dark)
                }
                .sheet(isPresented: $shown) {
                    SettingHelpSheet(title: SettingHelp.title(node), text: SettingHelp.text(node, store: store, own: own),
                                     facts: SettingHelp.facts(node, store: store), reset: SettingHelp.reset(node, store: store))
                }
        } else {
            content
        }
    }
}

/// SettingsFragment.tipOnce (P592): the two gestures that explain a setting and put its default back, said once, the first time
/// the settings open
@Observable
final class SettingsTips {
    static let shared = SettingsTips()
    private static let shownKey = "scam_ui.tip_long_press"
    var message: String?

    static func tipOnce() {
        guard !UserDefaults.standard.bool(forKey: shownKey) else { return }
        UserDefaults.standard.set(true, forKey: shownKey)
        show(L("set.tip.long_press"))
    }

    static func show(_ text: String, seconds: Double = 3.5) {
        shared.message = text
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { if shared.message == text { shared.message = nil } }
    }

    /// The toast at the bottom of the settings
    struct Toast: View {
        var body: some View {
            if let m = SettingsTips.shared.message {
                Text(m).font(.app(13)).foregroundStyle(.white).multilineTextAlignment(.center)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color(white: 0.2).opacity(0.95), in: Capsule())
                    .padding(.horizontal, 24).padding(.bottom, 28)
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }
        }
    }
}
