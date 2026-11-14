import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The state of ToneCurvePreference / ToneCurveView: the curve on the graph, the selected point, the point a finger holds, and the
/// steps (P178: every finished edit is one; undo / redo store the curve as any edit does). Stored under the preference's key in
/// ToneCurve's "x:y,..." text when a gesture ends, a slider is let go, a value is typed or a shortcut / template / file is chosen.
@Observable
final class ToneCurveEditing {
    static let maxSteps = 100
    let key: String
    let defaultPoints: String
    var curve: ToneCurve
    var selected: Int
    /// The point under the finger (-1: none)
    var active = -1
    private var history: [String] = []
    private var step = -1
    // the gesture (ToneCurveView.onTouchEvent)
    var down: CGPoint?
    var moved = false
    var added = false
    var hold: Task<Void, Never>?

    init(key: String, defaultPoints: String) {
        self.key = key
        self.defaultPoints = defaultPoints
        let c = ToneCurve.parse(SettingsStore.shared.string(key, defaultPoints))
        curve = c
        selected = c.count / 2
        record()
    }

    var canUndo: Bool { step > 0 }
    var canRedo: Bool { step >= 0 && step < history.count - 1 }

    /// The curve shown becomes a step (nothing when it is the step already); the steps after the shown one go.
    func record() {
        let now = curve.serialize()
        if step >= 0 && step < history.count && history[step] == now { return }
        while history.count > step + 1 { history.removeLast() }
        history.append(now)
        if history.count > Self.maxSteps { history.removeFirst() }
        step = history.count - 1
    }

    func undo() { go(step - 1) }
    func redo() { go(step + 1) }

    private func go(_ to: Int) {
        guard to >= 0 && to < history.count && to != step else { return }
        step = to
        let keep = selected
        curve = ToneCurve.parse(history[to])
        active = -1
        selected = keep >= 0 && keep < curve.count ? keep : curve.count / 2
        UISelectionFeedbackGenerator().selectionChanged()
        persist()
    }

    /// ToneCurvePreference.store: a step, and the text stored when it changed
    func store() {
        record()
        persist()
    }

    private func persist() {
        let text = curve.serialize()
        if text != SettingsStore.shared.string(key, defaultPoints) { SettingsStore.shared.set(key, text) }
    }

    /// A shortcut, a template or a loaded file: shown, a step, stored
    func edit(_ c: ToneCurve) {
        curve = c
        active = -1
        if selected >= c.count { selected = c.count / 2 }
        store()
    }

    func load(_ c: ToneCurve) {
        selected = c.count / 2
        edit(c)
    }

    func select(_ i: Int) { selected = i >= 0 && i < curve.count ? i : -1 }

    /// The selected point, nil for none
    var point: ToneCurve.Point? { selected >= 0 && selected < curve.count ? curve[selected] : nil }

    func moveSelected(x: Float, y: Float) {
        guard selected >= 0 && selected < curve.count else { return }
        curve.move(selected, x, y)
    }

    func deleteSelected() {
        guard selected >= 0, curve.remove(selected) else { return }
        selected -= 1
        store()
    }

    /// A hold on an inner point: it goes
    func deleteActive() {
        guard active >= 0, curve.remove(active) else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        selected = active - 1
        active = -1
        store()
    }
}

/// ToneCurveView: x the exposure -5..+5 EV around middle grey, y the display value. A tap on an empty spot adds a point, a drag
/// moves the point under the finger (the ends only up and down), a hold on an inner point deletes it; the selected point is a
/// filled dot in a halo. Undo / redo over the top left corner.
struct ToneCurveGraph: View {
    let model: ToneCurveEditing
    let enabled: Bool
    /// ToneCurveCompare: the operators drawn dashed beside the own curve
    var references: [ToneCurveCompare.Reference] = []
    static let aspect: CGFloat = 0.78
    static let pad: CGFloat = 14, bottom: CGFloat = 22

    static func plot(_ size: CGSize) -> CGRect {
        CGRect(x: pad, y: pad, width: max(1, size.width - 2 * pad), height: max(1, size.height - pad - bottom))
    }

    var body: some View {
        GeometryReader { geo in
            let plot = Self.plot(geo.size)
            Canvas { ctx, size in draw(ctx, size: size, plot: plot) }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in changed(v, plot: plot) }
                    .onEnded { _ in ended() }, including: enabled ? .all : .subviews)
        }
        .aspectRatio(1 / Self.aspect, contentMode: .fit)
        .overlay(alignment: .topLeading) {
            HStack(spacing: 2) {
                Button { model.undo() } label: { Image(systemName: "arrow.uturn.backward").frame(width: 34, height: 34) }
                    .disabled(!enabled || !model.canUndo)
                    .accessibilityLabel(Lang.t("Отменить", "Undo"))
                Button { model.redo() } label: { Image(systemName: "arrow.uturn.forward").frame(width: 34, height: 34) }
                    .disabled(!enabled || !model.canRedo)
                    .accessibilityLabel(Lang.t("Повторить", "Redo"))
            }
            .buttonStyle(.borderless)
            .padding(3)
            .background(.thinMaterial, in: Capsule())
            .padding(Self.pad + 6)
        }
        .accessibilityLabel(Lang.t("Тоновая кривая", "Tone curve"))
    }

    private func px(_ x: Float, _ p: CGRect) -> CGFloat { p.minX + CGFloat(x) * p.width }
    private func py(_ y: Float, _ p: CGRect) -> CGFloat { p.maxY - CGFloat(y) * p.height }
    private func vx(_ sx: CGFloat, _ p: CGRect) -> Float { Float((sx - p.minX) / p.width) }
    private func vy(_ sy: CGFloat, _ p: CGRect) -> Float { Float((p.maxY - sy) / p.height) }

    /// The point within 28 pt of a touch, -1 for none
    private func pointNear(_ at: CGPoint, _ p: CGRect) -> Int {
        var best = -1
        var bestD: CGFloat = 28
        for i in 0..<model.curve.count {
            let d = hypot(px(model.curve[i].x, p) - at.x, py(model.curve[i].y, p) - at.y)
            if d < bestD { bestD = d; best = i }
        }
        return best
    }

    private func changed(_ v: DragGesture.Value, plot: CGRect) {
        let m = model
        if m.down == nil {
            m.down = v.startLocation
            m.moved = false
            m.added = false
            m.active = pointNear(v.startLocation, plot)
            if m.active < 0 && plot.contains(v.startLocation) {
                var c = m.curve
                let i = c.add(vx(v.startLocation.x, plot), vy(v.startLocation.y, plot))
                if i >= 0 {
                    m.curve = c
                    m.active = i
                    m.added = true
                    UISelectionFeedbackGenerator().selectionChanged()
                }
            }
            if m.active > 0 && !m.added && m.active < m.curve.count - 1 {
                m.hold = Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    if !Task.isCancelled && !m.moved { m.deleteActive() }
                }
            }
            if m.active >= 0 { m.selected = m.active }
            return
        }
        guard m.active >= 0, let down = m.down else { return }
        if !m.moved && hypot(v.location.x - down.x, v.location.y - down.y) > 8 {
            m.moved = true
            m.hold?.cancel()
        }
        if m.moved || m.added { m.curve.move(m.active, vx(v.location.x, plot), vy(v.location.y, plot)) }
    }

    private func ended() {
        let m = model
        m.hold?.cancel()
        m.hold = nil
        let changed = m.active >= 0 && (m.moved || m.added)
        m.active = -1
        m.down = nil
        if changed { m.store() }
    }

    private func draw(_ ctx: GraphicsContext, size: CGSize, plot p: CGRect) {
        let accent = Color.accentColor
        ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 14), with: .color(Color(white: 0.08)))
        var grid = Path()
        for i in 1..<4 {
            let f = Float(i) / 4
            grid.move(to: CGPoint(x: px(f, p), y: p.minY)); grid.addLine(to: CGPoint(x: px(f, p), y: p.maxY))
            grid.move(to: CGPoint(x: p.minX, y: py(f, p))); grid.addLine(to: CGPoint(x: p.maxX, y: py(f, p)))
        }
        ctx.stroke(grid, with: .color(.white.opacity(0.1)), lineWidth: 1)
        var diagonal = Path()
        diagonal.move(to: CGPoint(x: p.minX, y: p.maxY))
        diagonal.addLine(to: CGPoint(x: p.maxX, y: p.minY))
        ctx.stroke(diagonal, with: .color(.white.opacity(0.35)), style: StrokeStyle(lineWidth: 1.2, dash: [4, 4]))
        let labelY = size.height - 11
        ctx.draw(Text("−5 EV").font(.app(10.5)).foregroundStyle(.secondary), at: CGPoint(x: p.minX, y: labelY), anchor: .leading)
        ctx.draw(Text(Lang.t("0 (серый)", "0 (grey)")).font(.app(10.5)).foregroundStyle(.secondary),
                 at: CGPoint(x: px(0.5, p), y: labelY), anchor: .center)
        ctx.draw(Text("+5 EV").font(.app(10.5)).foregroundStyle(.secondary), at: CGPoint(x: p.maxX, y: labelY), anchor: .trailing)
        // ToneCurveView.setReferences: each operator dashed in its colour, its name at its end
        for (k, ref) in references.enumerated() where ref.samples.count > 1 {
            var path = Path()
            for (i, v) in ref.samples.enumerated() {
                let pt = CGPoint(x: px(Float(i) / Float(ref.samples.count - 1), p), y: py(v, p))
                if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
            }
            ctx.stroke(path, with: .color(ref.color.opacity(0.9)), style: StrokeStyle(lineWidth: 1.6, dash: [6, 4]))
            ctx.draw(Text(ref.name).font(.app(10.5, 600)).foregroundStyle(ref.color),
                     at: CGPoint(x: p.maxX - 4, y: p.minY + 10 + CGFloat(k) * 14), anchor: .trailing)
        }
        let s = model.curve.samples(128)
        var line = Path(), area = Path()
        area.move(to: CGPoint(x: px(0, p), y: p.maxY))
        for (i, v) in s.enumerated() {
            let pt = CGPoint(x: px(Float(i) / Float(s.count - 1), p), y: py(v, p))
            if i == 0 { line.move(to: pt) } else { line.addLine(to: pt) }
            area.addLine(to: pt)
        }
        area.addLine(to: CGPoint(x: px(1, p), y: p.maxY))
        area.closeSubpath()
        ctx.fill(area, with: .color(accent.opacity(0.12)))
        ctx.stroke(line, with: .color(accent), style: StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))
        for i in 0..<model.curve.count {
            let c = CGPoint(x: px(model.curve[i].x, p), y: py(model.curve[i].y, p))
            func circle(_ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)) }
            if i == model.active {
                ctx.fill(circle(9), with: .color(accent))
            } else if i == model.selected {
                ctx.fill(circle(13), with: .color(accent.opacity(0.25)))
                ctx.fill(circle(7), with: .color(accent))
            } else {
                ctx.fill(circle(6.5), with: .color(Color(white: 0.08)))
                ctx.stroke(circle(6.5), with: .color(accent), lineWidth: 2.2)
            }
        }
    }
}

/// A saved curve's small picture (ToneCurveThumb)
struct ToneCurveThumb: View {
    let curve: ToneCurve
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 6), with: .color(Color(white: 0.08)))
            let s = curve.samples(48)
            var line = Path()
            for (i, v) in s.enumerated() {
                let pt = CGPoint(x: 3 + CGFloat(i) / CGFloat(s.count - 1) * (size.width - 6), y: size.height - 3 - CGFloat(v) * (size.height - 6))
                if i == 0 { line.move(to: pt) } else { line.addLine(to: pt) }
            }
            ctx.stroke(line, with: .color(.accentColor), lineWidth: 1.6)
        }
        .frame(width: 58, height: 44)
    }
}

/// The curve files: Documents/ToneCurves (Android: Download/SCAMERA/ToneCurves; the Files app shows the app's folder)
enum ToneCurveFiles {
    static var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("ToneCurves", isDirectory: true)
    }

    static func list() -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        return all.filter { !$0.hasDirectoryPath }.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
    }

    /// ScameraFolder.baseName: no extension, no characters a file name cannot hold
    static func baseName(_ typed: String) -> String {
        var base = typed.trimmingCharacters(in: .whitespaces)
        for e in [".txt", ".json"] where base.lowercased().hasSuffix(e) { base = String(base.dropLast(e.count)) }
        return base.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "[\\\\/:*?\"<>|\\s]+", with: "_", options: .regularExpression)
    }

    /// The first «Кривая_N» not taken
    static func freeName() -> String {
        let taken = Set(list().map { $0.lastPathComponent.lowercased() })
        let stem = Lang.t("Кривая_", "Curve_")
        var i = 1
        while taken.contains((stem + "\(i).txt").lowercased()) { i += 1 }
        return stem + "\(i)"
    }

    static func save(_ name: String, _ text: String) throws -> URL {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(name)
        try Data(text.utf8).write(to: url, options: .atomic)
        return url
    }

    /// A file's text, nil above 256 KB
    static func read(_ url: URL) -> String? {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), data.count <= 256 * 1024 else { return nil }
        return String(decoding: data, as: UTF8.self)
    }
}

/// ToneCurvePreference: the graph, «S-кривая» / «Прямая», the selected point (‹ ›, «Удалить», «Экспозиция» -5..+5 EV and «Яркость»
/// 0..100 % with values to type), «Сохранить» (a named .txt in the curve folder) and «Загрузить» (the saved curves with a picture,
/// the templates, «Из файла…»).
struct ToneCurveEditor: View {
    @State private var model: ToneCurveEditing
    let enabled: Bool
    @State private var typing: Bool? = nil   // true: the exposure, false: the brightness
    @State private var typed = ""
    @State private var saving = false
    @State private var saveName = ""
    @State private var loading = false
    @State private var pendingImport = false
    @State private var importing = false
    @State private var status: String?
    @State private var files: [CurveFile] = []
    @State private var references: [ToneCurveCompare.Reference] = []

    struct CurveFile: Identifiable {
        let url: URL
        let curve: ToneCurve
        var id: URL { url }
    }

    init(key: String, defaultPoints: String?, enabled: Bool) {
        _model = State(initialValue: ToneCurveEditing(key: key, defaultPoints: defaultPoints ?? ToneCurve.settingPoints))
        self.enabled = enabled
    }

    static func evText(_ x: Float) -> String {
        let v = (Double((x - 0.5) * 10) * 100).rounded() / 100
        return (v > 0 ? "+" : v < 0 ? "−" : "") + String(format: "%.2f EV", locale: Locale(identifier: "en_US_POSIX"), abs(v))
    }

    static func percentText(_ y: Float) -> String {
        String(format: "%.1f %%", locale: Locale(identifier: "en_US_POSIX"), Double(y) * 100)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ToneCurveGraph(model: model, enabled: enabled, references: references)
            ToneCurveCompareChips(references: $references)
            HStack(spacing: 8) {
                chip(Lang.t("S-кривая", "S-curve")) { model.edit(ToneCurve.defaults()) }
                chip(Lang.t("Прямая", "Straight")) { model.edit(ToneCurve.identity()) }
            }
            pointPanel
            HStack(spacing: 8) {
                chip(Lang.t("Сохранить", "Save")) {
                    saveName = ToneCurveFiles.freeName()
                    saving = true
                }
                chip(Lang.t("Загрузить", "Load")) {
                    refreshFiles()
                    loading = true
                }
            }
            if let status { Text(status).font(.app(12)).foregroundStyle(.secondary) }
        }
        .buttonStyle(.borderless)
        .disabled(!enabled)
        .padding(.vertical, 6)
        .alert(typing == true ? Lang.t("Экспозиция точки \(model.selected + 1), EV", "Point \(model.selected + 1) exposure, EV")
               : Lang.t("Яркость точки \(model.selected + 1), %", "Point \(model.selected + 1) brightness, %"),
               isPresented: Binding(get: { typing != nil }, set: { if !$0 { typing = nil } })) {
            TextField("", text: $typed).keyboardType(.numbersAndPunctuation)
            Button("OK") { applyTyped() }
            Button(Lang.t("Отмена", "Cancel"), role: .cancel) {}
        }
        .alert(Lang.t("Сохранить кривую", "Save the curve"), isPresented: $saving) {
            TextField(Lang.t("Название", "Name"), text: $saveName)
            Button(Lang.t("Сохранить", "Save")) { save() }
            Button(Lang.t("Отмена", "Cancel"), role: .cancel) {}
        } message: {
            Text(Lang.t("Папка: ", "Folder: ") + "SCAMERA/ToneCurves")
        }
        .sheet(isPresented: $loading, onDismiss: {
            if pendingImport {
                pendingImport = false
                importing = true
            }
        }) { loadSheet }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText, .json, .text, .data]) { result in
            guard case .success(let url) = result else { return }
            guard let text = ToneCurveFiles.read(url), let curve = ToneCurve.fromFileText(text) else {
                status = Lang.t("В файле нет кривой", "The file holds no curve")
                return
            }
            model.load(curve)
            status = Lang.t("Кривая загружена: ", "Curve loaded: ") + (ToneCurve.jsonName(text) ?? url.deletingPathExtension().lastPathComponent)
        }
    }

    private func chip(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.app(15, 700)).frame(maxWidth: .infinity).padding(.vertical, 10)
                .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    @ViewBuilder private var pointPanel: some View {
        let c = model.curve, i = model.selected, n = c.count
        let has = i >= 0 && i < n, end = has && (i == 0 || i == n - 1)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button { model.select(i - 1) } label: { Text("‹").font(.app(22).bold()).frame(width: 34, height: 34) }
                    .disabled(!(has && i > 0))
                    .accessibilityLabel(Lang.t("Предыдущая точка", "Previous point"))
                Text(has ? Lang.t("Точка \(i + 1) из \(n)", "Point \(i + 1) of \(n)") : Lang.t("Нажмите на точку", "Tap a point"))
                    .font(.app(15, 600)).frame(maxWidth: .infinity)
                Button { model.select(i + 1) } label: { Text("›").font(.app(22).bold()).frame(width: 34, height: 34) }
                    .disabled(!(has && i < n - 1))
                    .accessibilityLabel(Lang.t("Следующая точка", "Next point"))
                Button(role: .destructive) { model.deleteSelected() } label: {
                    Text(Lang.t("Удалить", "Delete")).font(.app(15).bold()).padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Color.red.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                }
                .disabled(!(has && !end))
            }
            slider(Lang.t("Экспозиция", "Exposure"), value: has ? Self.evText(c[i].x) : "—", on: has && !end,
                   get: { model.point.map { Double($0.x) } ?? 0.5 }, set: { v in if let p = model.point { model.moveSelected(x: Float(v), y: p.y) } },
                   type: { typed = String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), Double((c[i].x - 0.5) * 10)); typing = true })
            slider(Lang.t("Яркость", "Brightness"), value: has ? Self.percentText(c[i].y) : "—", on: has,
                   get: { model.point.map { Double($0.y) } ?? 0.5 }, set: { v in if let p = model.point { model.moveSelected(x: p.x, y: Float(v)) } },
                   type: { typed = String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), Double(c[i].y) * 100); typing = false })
        }
        .padding(10)
        .background(Color(white: 0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    private func slider(_ title: String, value: String, on: Bool, get: @escaping () -> Double, set: @escaping (Double) -> Void,
                        type: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title).font(.app(15))
                Spacer()
                Button(action: type) {
                    Text(value).font(.app(13).monospacedDigit().bold()).padding(.horizontal, 10).padding(.vertical, 4)
                        .background(Color.accentColor.opacity(0.15), in: Capsule())
                }
            }
            Slider(value: Binding(get: get, set: set), in: 0...1, step: 0.001, onEditingChanged: { editing in
                if !editing { model.store() }
            })
        }
        .disabled(!on)
        .opacity(on ? 1 : 0.4)
    }

    /// PointPanel.typeX / typeY: the exposure within the neighbours' range (the ends keep theirs), the brightness 0..100 %
    private func applyTyped() {
        defer { typing = nil }
        let c = model.curve, i = model.selected
        guard i >= 0 && i < c.count, let v = Float(typed.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)) else { return }
        if typing == true {
            guard i > 0 && i < c.count - 1 else { return }
            let lo = (c[i - 1].x + ToneCurve.minGap - 0.5) * 10, hi = (c[i + 1].x - ToneCurve.minGap - 0.5) * 10
            model.moveSelected(x: 0.5 + max(lo, min(hi, v)) / 10, y: c[i].y)
        } else {
            model.moveSelected(x: c[i].x, y: max(0, min(100, v)) / 100)
        }
        model.store()
    }

    private func save() {
        let base = ToneCurveFiles.baseName(saveName)
        guard !base.isEmpty else {
            status = Lang.t("Введите название", "Enter a name")
            return
        }
        do {
            _ = try ToneCurveFiles.save(base + ".txt", model.curve.fileText())
            status = Lang.t("Кривая сохранена: ", "Curve saved: ") + "SCAMERA/ToneCurves/" + base + ".txt"
        } catch {
            status = Lang.t("Не сохранилось: ", "Not saved: ") + error.localizedDescription
        }
    }

    private func refreshFiles() {
        files = ToneCurveFiles.list().compactMap { url -> CurveFile? in
            guard let text = ToneCurveFiles.read(url), let c = ToneCurve.fromFileText(text) else { return nil }
            return CurveFile(url: url, curve: c)
        }
    }

    private var loadSheet: some View {
        NavigationStack {
            List {
                // ToneCurveCompare.pickTemplate: the S-curve, the straight line and the operators as an own curve of nine points
                Section(Lang.t("Шаблоны", "Templates")) {
                    ForEach(Array(ToneCurveCompare.templateNames.enumerated()), id: \.offset) { i, name in
                        Button {
                            let t = ToneCurveCompare.template(i)
                            model.load(t)
                            status = Lang.t("Кривая загружена: ", "Curve loaded: ") + name
                            loading = false
                        } label: {
                            HStack(spacing: 14) {
                                ToneCurveThumb(curve: ToneCurveCompare.template(i))
                                Text(name)
                            }
                        }
                    }
                }
                Section {
                    if files.isEmpty {
                        Text(Lang.t("Сохранённых кривых пока нет. Папка: ", "No saved curves yet. Folder: ") + "SCAMERA/ToneCurves")
                            .font(.app(13)).foregroundStyle(.secondary)
                    }
                    ForEach(files) { file in
                        Button {
                            model.load(file.curve)
                            status = Lang.t("Кривая загружена: ", "Curve loaded: ") + file.url.deletingPathExtension().lastPathComponent
                            loading = false
                        } label: {
                            HStack(spacing: 14) {
                                ToneCurveThumb(curve: file.curve)
                                Text(file.url.pathExtension.lowercased() == "txt" ? file.url.deletingPathExtension().lastPathComponent
                                     : file.url.lastPathComponent).lineLimit(1)
                            }
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                try? FileManager.default.removeItem(at: file.url)
                                refreshFiles()
                            } label: { Text(Lang.t("Удалить", "Delete")) }
                        }
                    }
                }
                Section {
                    Button(Lang.t("Из файла…", "From a file…")) {
                        pendingImport = true
                        loading = false
                    }
                }
            }
            .navigationTitle(Lang.t("Загрузить кривую", "Load a curve"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}
