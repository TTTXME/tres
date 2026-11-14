import SwiftUI

/// GalleryUi / SettingsStyle (Android P579): the gallery's page and cards, white text, the muted line, the camera's accent with
/// dark ink on it, the red of a destructive action, the dark bars and pills over a photo.
enum GalleryStyle {
    static let page = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255)
    static let card = Color(red: 0x2C / 255, green: 0x2C / 255, blue: 0x2E / 255)
    static let text = Color.white
    static let muted = Color.white.opacity(0.6)
    static let section = Color.white.opacity(0.55)
    static let accent = Color(red: 0xFF / 255, green: 0xD4 / 255, blue: 0x47 / 255)
    static let ink = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255)
    static let warn = Color(red: 0xFF / 255, green: 0x45 / 255, blue: 0x3A / 255)
    static let bar = Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x16 / 255).opacity(0.9)
    static let thumb = Color(red: 0x76 / 255, green: 0x76 / 255, blue: 0x80 / 255).opacity(0.32)
    /// ViewerChrome.FAV: the favourites' pink (the hearts on the cells)
    static let favourite = Color(red: 1, green: 0x6B / 255, blue: 0x81 / 255)
    static let hairline = Color(red: 0x54 / 255, green: 0x54 / 255, blue: 0x58 / 255).opacity(0.65)
}

/// An Android vector drawable (Icons.xcassets, tools/gen_settings_ui.py) tinted.
struct GalleryIcon: View {
    let name: String
    var size: CGFloat = 22
    var color: Color = .white
    var body: some View {
        Image(name).renderingMode(.template).resizable().scaledToFit().frame(width: size, height: size).foregroundStyle(color)
    }
}

/// PhotoEditorFragment.iconButton / ViewerChrome.disc: a 40dp round button on the card colour with a white 22dp icon.
struct RoundIconButton: View {
    let icon: String
    let label: String
    var enabled = true
    var background: Color = GalleryStyle.card
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            GalleryIcon(name: icon, size: 22)
                .frame(width: 40, height: 40)
                .background(background, in: Circle())
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.35)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// GalleryUi.pill: a line of text on the dark bar colour over a photo.
struct GalleryPill: View {
    let text: String
    var body: some View {
        Text(text).font(.app(13, 600)).foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(GalleryStyle.bar, in: Capsule())
    }
}

/// GalleryUi.chip / paintChip: the accent capsule with dark ink when chosen, #2C2C2E with white text otherwise.
struct GalleryChip: View {
    let text: String
    let on: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(.app(14, 500)).foregroundStyle(on ? GalleryStyle.ink : GalleryStyle.text)
                .padding(.horizontal, 14).frame(minHeight: 36)
                .background(on ? GalleryStyle.accent : GalleryStyle.card, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// IosSegmented: a choice of a few on a grey capsule, the chosen one on the lighter thumb.
struct GallerySegments: View {
    let names: [String]
    let selected: Int
    let pick: (Int) -> Void
    var body: some View {
        HStack(spacing: 2) {
            ForEach(names.indices, id: \.self) { i in
                Button { pick(i) } label: {
                    Text(names[i]).font(.app(13, i == selected ? 600 : 400)).lineLimit(1).minimumScaleFactor(0.7)
                        .foregroundStyle(.white).frame(maxWidth: .infinity).frame(height: 30)
                        .background(i == selected ? Color(white: 0.39) : Color.clear, in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(2)
        .background(GalleryStyle.thumb, in: RoundedRectangle(cornerRadius: 9))
        .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 6)
        .animation(.easeOut(duration: 0.2), value: selected)
    }
}

/// GalleryUi.sectionLabel: a small caption over a group.
struct GallerySection: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.app(12, 600)).foregroundStyle(GalleryStyle.section)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 2)
    }
}

/// EditSlider: the label at the left, the value in its pill at the right (the accent when changed), the slider under them - a
/// bipolar slider fills from its middle, a colour scale shows its gradient; a double tap puts the default back; changed while
/// dragging, committed when let go (one undo step).
struct EditSliderRow: View {
    let label: String
    let min: Float
    let max: Float
    let def: Float
    let step: Float
    let bipolar: Bool
    let value: Float
    var gradient: [Color]? = nil
    var format: (Float) -> String = { v in
        let i = EditParam.javaRound(v)
        return i > 0 ? "+\(i)" : "\(i)"
    }
    let changed: (Float, Bool) -> Void

    @State private var dragStart: Float?

    private func snap(_ v: Float) -> Float {
        let r = Float(EditParam.javaRound(Swift.max(min, Swift.min(max, v)) / step)) * step
        return Swift.max(min, Swift.min(max, r))
    }

    var body: some View {
        VStack(spacing: 4) {
            HStack {
                Text(label).font(.app(15)).foregroundStyle(GalleryStyle.text).lineLimit(1)
                Spacer(minLength: 8)
                Text(format(value)).font(.app(14, 700).monospacedDigit())
                    .foregroundStyle(value != def ? GalleryStyle.ink : GalleryStyle.accent)
                    .padding(.horizontal, 11).frame(minWidth: 44, minHeight: 24)
                    .background(value != def ? GalleryStyle.accent : GalleryStyle.accent.opacity(0.16), in: Capsule())
            }
            .frame(minHeight: 28)
            GeometryReader { geo in
                let w = geo.size.width
                let t = CGFloat((value - min) / Swift.max(1e-6, max - min))
                let mid = CGFloat((def - min) / Swift.max(1e-6, max - min))
                ZStack(alignment: .leading) {
                    Group {
                        if let gradient {
                            Capsule().fill(LinearGradient(colors: gradient, startPoint: .leading, endPoint: .trailing))
                        } else {
                            Capsule().fill(Color.white.opacity(0.18))
                        }
                    }
                    .frame(height: 4)
                    if gradient == nil {
                        let from = bipolar ? Swift.min(mid, t) : 0, to = bipolar ? Swift.max(mid, t) : t
                        Capsule().fill(GalleryStyle.accent).frame(width: Swift.max(0, (to - from) * w), height: 4).offset(x: from * w)
                        if bipolar { Rectangle().fill(Color.white.opacity(0.5)).frame(width: 2, height: 10).offset(x: mid * w - 1) }
                    }
                    Circle().fill(.white).frame(width: 24, height: 24).shadow(color: .black.opacity(0.3), radius: 2, y: 1)
                        .offset(x: t * w - 12)
                }
                .frame(height: 28)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        if dragStart == nil { dragStart = value }
                        let start = dragStart ?? value
                        let v = start + Float(g.translation.width / Swift.max(1, w)) * (max - min)
                        changed(snap(v), false)
                    }
                    .onEnded { _ in
                        let changedValue = dragStart != value
                        dragStart = nil
                        if changedValue { changed(value, true) }
                    })
                .simultaneousGesture(TapGesture(count: 2).onEnded { changed(def, true) })
            }
            .frame(height: 28)
        }
        .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(format(value))
        .accessibilityAdjustableAction { dir in
            let v = dir == .increment ? value + step : value - step
            changed(snap(v), true)
        }
    }
}
