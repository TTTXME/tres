import SwiftUI

/// PrecisionEditor (ui/controls/PrecisionEditor.java, P137 / P574): the exact-value editor of a settings slider - «Отмена» / the title
/// / «Готово» on top; the value big in the middle (a tap types it in place), in the accent while the slider is held; «Сбросить до N»
/// under it («По умолчанию» at the default); the slider over the whole range between its ends; «Точно»: a − | + stepper of one step
/// of the setting, repeating while held. «Готово» applies; «Отмена» or a swipe down close without applying.
struct PrecisionEditor: View {
    let title: String
    let min: Double
    let max: Double
    let fallback: Double
    let decimal: Bool
    /// The setting's own step (the slider's grid and the stepper's ±)
    let step: Double
    /// The decimals the setting shows (at least; a typed value keeps its own)
    let decimals: Int
    let apply: (Double) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value: Double
    @State private var held = false
    @State private var typing = false
    @State private var field = ""
    @State private var invalid = false
    @FocusState private var focused: Bool

    init(title: String, min: Double, max: Double, value: Double, fallback: Double, decimal: Bool, step: Double, decimals: Int,
         apply: @escaping (Double) -> Void) {
        self.title = title
        let lo = Swift.min(min, max), hi = Swift.max(min, max)
        self.min = lo
        self.max = hi
        self.decimal = decimal
        let grid = step > 0 && step.isFinite ? step : (decimal ? 0.1 : 1)
        self.step = decimal ? grid : Swift.max(1, grid.rounded())
        self.decimals = decimal ? Swift.min(6, Swift.max(0, decimals)) : 0
        let clamp = { (v: Double) in Swift.max(lo, Swift.min(hi, v)) }
        self.fallback = clamp(fallback)
        var v = clamp(value.isFinite ? value : fallback)
        if !decimal { v = v.rounded() }
        _value = State(initialValue: v)
        self.apply = apply
    }

    private var steps: Int { Swift.max(1, Swift.min(1_000_000, Int(((max - min) / step).rounded()))) }
    private func clamp(_ v: Double) -> Double { Swift.max(min, Swift.min(max, v)) }
    private func position(_ v: Double) -> Int { Swift.max(0, Swift.min(steps, Int(((clamp(v) - min) / step).rounded()))) }
    private func round(_ v: Double, _ places: Int) -> Double {
        let p = pow(10, Double(Swift.max(0, Swift.min(9, places))))
        return (v * p).rounded(.toNearestOrAwayFromZero) / p
    }
    private var stepDecimals: Int { SliderGrid.decimalsOf(String(format: "%.6f", step)) }
    /// The value of slider position i: on the setting's grid, its float noise rounded away
    private func at(_ i: Int) -> Double {
        let v = i >= steps ? max : min + Double(i) * step
        return decimal ? round(clamp(v), Swift.max(decimals, stepDecimals)) : clamp(v).rounded()
    }

    private func format(_ v: Double) -> String {
        let text: String
        if decimal {
            let own = SliderGrid.decimalsOf(SliderGrid.exact(v, decimal: true))
            text = String(format: "%.\(Swift.max(decimals, Swift.min(6, own)))f", locale: Locale(identifier: "en_US_POSIX"), v)
        } else {
            text = String(Int(v.rounded()))
        }
        return CameraStyle.decimal(text)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Group {
                    if typing {
                        TextField("", text: $field)
                            .font(.system(size: 34, weight: .bold).monospacedDigit())
                            .multilineTextAlignment(.center)
                            .keyboardType(min < 0 ? .numbersAndPunctuation : (decimal ? .decimalPad : .numberPad))
                            .focused($focused)
                            .padding(.horizontal, 16).padding(.vertical, 6)
                            .background(Color(white: 0.17), in: RoundedRectangle(cornerRadius: 12))
                            .onSubmit { _ = commitField() }
                    } else {
                        Text(format(value))
                            .font(.system(size: 64, weight: .bold).monospacedDigit())
                            .foregroundStyle(held ? AnyShapeStyle(AccentPalette.settingsColor) : AnyShapeStyle(Color.white))
                            .scaleEffect(held ? 1.08 : 1)
                            .minimumScaleFactor(0.4)
                            .lineLimit(1)
                            .onTapGesture { startTyping() }
                            .accessibilityAddTraits(.isButton)
                            .accessibilityHint(L("set.precise.type"))
                    }
                }
                .frame(height: 76)
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .animation(.spring(duration: 0.25), value: held)
                if invalid {
                    Text(L("set.precise.invalid")).font(.caption).foregroundStyle(.red)
                }
                Button {
                    if commitField() { value = fallback }
                } label: {
                    Text(abs(value - fallback) < 1e-9 ? L("set.precise.default") : L("set.precise.reset_to") + " " + format(fallback))
                        .font(.system(size: 14)).padding(.horizontal, 12).frame(minHeight: 30)
                }
                .disabled(abs(value - fallback) < 1e-9)
                .padding(.top, 2).padding(.bottom, 6)
                HStack(spacing: 10) {
                    Text(format(min)).font(.caption).foregroundStyle(.secondary).frame(minWidth: 22, alignment: .trailing)
                    Slider(value: Binding(get: { Double(position(value)) }, set: { p in
                        if typing { closeField() }
                        value = at(Int(p.rounded()))
                    }), in: 0...Double(steps), step: 1, onEditingChanged: { held = $0 })
                        .accessibilityLabel(L("set.precise.value"))
                    Text(format(max)).font(.caption).foregroundStyle(.secondary).frame(minWidth: 22, alignment: .leading)
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 4)
                HStack {
                    Text(L("set.precise.fine")).font(.system(size: 16))
                    Spacer()
                    HStack(spacing: 0) {
                        RepeatButton(text: "−", label: L("set.precise.less")) { nudge(-1) }
                        Rectangle().fill(Color.white.opacity(0.2)).frame(width: 1, height: 18)
                        RepeatButton(text: "+", label: L("set.precise.more")) { nudge(1) }
                    }
                    .background(Color(red: 118 / 255, green: 118 / 255, blue: 128 / 255).opacity(0.24), in: RoundedRectangle(cornerRadius: 9))
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(Color(white: 0.17), in: RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 16).padding(.top, 12)
                Spacer(minLength: 0)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("set.precise.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("set.precise.done")) {
                        guard commitField() else { return }
                        apply(value)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.height(400), .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(34)
        .preferredColorScheme(.dark)
    }

    private func startTyping() {
        field = decimal ? SliderGrid.exact(value, decimal: true) : String(Int(value.rounded()))
        invalid = false
        typing = true
        DispatchQueue.main.async { focused = true }
    }

    private func closeField() {
        typing = false
        focused = false
        invalid = false
    }

    /// The typed number taken (within the range); false when it is no number in the range (the field stays open)
    private func commitField() -> Bool {
        guard typing else { return true }
        let text = field.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: "−", with: "-")
        guard let v = Double(text), v.isFinite, v >= min - 1e-9, v <= max + 1e-9 else {
            invalid = true
            return false
        }
        value = decimal ? clamp(v) : clamp(v).rounded()
        closeField()
        return true
    }

    private func nudge(_ dir: Double) {
        guard commitField() else { return }
        var next = clamp(value + dir * step)
        next = decimal ? round(next, Swift.max(Swift.max(decimals, stepDecimals), SliderGrid.decimalsOf(SliderGrid.exact(value, decimal: true))))
            : next.rounded()
        if next != value { value = next }
    }
}

/// A stepper half: one step on the press, then repeating every 80 ms while held (after 400 ms)
private struct RepeatButton: View {
    let text: String
    let label: String
    let action: () -> Void
    @State private var timer: Timer?
    @State private var pressed = false

    var body: some View {
        Text(text).font(.system(size: 22, weight: .medium)).frame(width: 46, height: 32)
            .foregroundStyle(pressed ? AnyShapeStyle(AccentPalette.settingsColor) : AnyShapeStyle(Color.white))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !pressed else { return }
                    pressed = true
                    action()
                    timer?.invalidate()
                    timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { _ in
                        DispatchQueue.main.async {
                            guard pressed else { return }
                            timer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { _ in DispatchQueue.main.async { action() } }
                        }
                    }
                }
                .onEnded { _ in
                    pressed = false
                    timer?.invalidate()
                    timer = nil
                })
            .accessibilityElement()
            .accessibilityLabel(label)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { action() }
    }
}
