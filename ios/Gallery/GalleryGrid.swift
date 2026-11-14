import Photos
import SwiftUI
import UIKit

/// ImageLibraryFragment's photos in the chosen view (P622 GalleryViews, GalleryLayout.Mode): «Сетка» (LibraryAdapter: cards by day
/// in `prefs.columns` columns, a pinch changes them), «Список» (ListRows) or «Лента» (TimelineRows: the month grid, month and year
/// cards, the floating «Годы · Месяцы · Все фото» control, the month capsule pinned over the photos). A tap opens the viewer;
/// «Выбрать», a long press or the list's «⋮» select - a long press and a drag select every photo between (DragSelectionItemTouchListener,
/// the list scrolls by the edges), a day's title selects the day - and the bar at the bottom: × · the count (a tap selects every photo
/// shown) · «Сравнить» (exactly two) · «Избранное» · «Поделиться» · «Удалить». The fast scroller (FastScroller) at the right edge
/// names the day under the finger. A switch of view keeps the photo on screen.
@MainActor
struct SelectableGrid: View {
    let library: GalleryLibrary
    let assets: [PHAsset]
    @Binding var selection: Set<String>?
    var bottomInset: CGFloat = 16
    /// the timeline control's distance from the bottom (over the tab bar)
    var levelBottom: CGFloat = 82
    let open: (Int) -> Void
    let compare: (PHAsset, PHAsset) -> Void
    /// how far the page has scrolled from its top (pt), for the large title's collapse
    var scrolled: (CGFloat) -> Void = { _ in }

    @State private var share: ShareFiles?
    @State private var confirmDelete = false
    @State private var single: PHAsset?
    @State private var toast: String?
    @State private var lastOpened = GalleryLayout.lastOpened
    @State private var entering = false
    @State private var scroll = GridScroll()
    @State private var runsCache = RunsCache()

    static let scrollSpace = "gallery.scroll", contentSpace = "gallery.content"

    private var prefs: GalleryPrefs { library.prefs }
    private var mode: GalleryLayout.Mode { prefs.mode }
    private var level: GalleryLayout.Level { prefs.level }
    private var selecting: Bool { selection != nil }

    var body: some View {
        let now = Date()
        let runs = currentRuns(now)
        let scroll = self.scroll
        return GeometryReader { outer in
            ScrollViewReader { reader in
                ZStack(alignment: .bottom) {
                    ScrollView {
                        content(runs, now: now, factor: Tablet.widthFactor(outer.size.width))
                            .coordinateSpace(name: Self.contentSpace)
                            .background(GeometryReader { g in
                                Color.clear.preference(key: ContentFrameKey.self, value: g.frame(in: .named(Self.scrollSpace)))
                            })
                            .padding(.bottom, selecting ? 110 : bottomInset + (mode == .timeline ? 56 : 0))
                            .opacity(entering ? 0 : 1)
                            .scaleEffect(entering ? 0.97 : 1)
                    }
                    .coordinateSpace(name: Self.scrollSpace)
                    .scrollIndicators(.hidden)
                    .scrollDisabled(scroll.dragging || scroll.thumbDragging)
                    .onPreferenceChange(ContentFrameKey.self) { f in
                        let moved = abs(f.minY - scroll.offset) > 0.5
                        scroll.offset = f.minY
                        scroll.contentHeight = max(1, f.height)
                        scrolled(-f.minY)
                        if moved { scroll.touchScroller() }
                    }
                    .onPreferenceChange(ItemFramesKey.self) { if scroll.frames != $0 { scroll.frames = $0 } }
                    .onPreferenceChange(HeaderFramesKey.self) { if scroll.headers != $0 { scroll.headers = $0 } }
                    .simultaneousGesture(MagnifyGesture().onEnded { v in pinch(v.magnification) })
                    if mode == .timeline, level == .all { StickyMonth(scroll: scroll, runs: runs) }
                    FastScroller(scroll: scroll, runs: runs, height: outer.size.height) { item in scrollTo(item, runs: runs) }
                    if mode == .timeline, !selecting, !assets.isEmpty {
                        levelBar.padding(.bottom, levelBottom).transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    if let picked = selection { selectionBar(picked) }
                    if let toast {
                        GalleryPill(text: toast).padding(.bottom, selecting ? 100 : bottomInset + 10)
                            .transition(.opacity)
                            .task(id: toast) {
                                try? await Task.sleep(nanoseconds: 2_500_000_000)
                                self.toast = nil
                            }
                    }
                }
                .onAppear {
                    scroll.proxy = reader
                    scroll.viewport = outer.size
                    scroll.shownMode = mode
                    scroll.shownLevel = level
                }
                .onChange(of: outer.size) { _, s in scroll.viewport = s }
            }
        }
        .animation(.spring(response: 0.42, dampingFraction: 0.7), value: selecting)
        .animation(.spring(response: 0.42, dampingFraction: 0.7), value: mode)
        .sheet(item: $share) { s in ActivitySheet(items: s.urls) }
        .confirmationDialog(deleteTitle, isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("gallery.delete"), role: .destructive) { deleteSelected() }
            Button(L("gallery.cancel"), role: .cancel) { single = nil }
        } message: {
            Text(deleteCount == 1 ? L("gallery.delete_message") : L("gallery.delete_message_many"))
        }
        .onChange(of: library.version) { _, _ in
            // photos deleted elsewhere leave the selection
            if let s = selection { selection = s.intersection(Set(assets.map(\.localIdentifier))) }
        }
        .onChange(of: mode) { _, _ in relayout() }
        .onChange(of: level) { _, _ in relayout() }
    }

    // ---------------------------------------------------------------- the views

    /// The sections of the view, made again only when the list, the view or the day changes (not on every scroll frame).
    private func currentRuns(_ now: Date) -> [GalleryRun] {
        var h = Hasher()
        for a in assets { h.combine(a.localIdentifier) }
        let day = Calendar.current.ordinality(of: .day, in: .era, for: now) ?? 0
        let key = "\(mode.rawValue)|\(level.rawValue)|\(assets.count)|\(h.finalize())|\(day)"
        if runsCache.key == key { return runsCache.runs }
        let runs = makeRuns(now)
        runsCache.key = key
        runsCache.runs = runs
        return runs
    }

    private func makeRuns(_ now: Date) -> [GalleryRun] {
        switch mode {
        case .grid, .list: return GallerySections.days(assets, now: now)
        case .timeline:
            switch level {
            case .years: return GallerySections.years(assets)
            case .months, .all: return GallerySections.months(assets)
            }
        }
    }

    /// `factor`: GalleryUi.widthFactor (P502) - an iPad's width shows the columns that many times (the list keeps one), the
    /// timeline's month and year cards that many a row
    @ViewBuilder private func content(_ runs: [GalleryRun], now: Date, factor: Int) -> some View {
        switch mode {
        case .grid: gridContent(runs, columns: prefs.columns * factor)
        case .list: listContent(runs, now: now)
        case .timeline:
            if level == .all { timelineContent(runs, columns: prefs.timelineColumns * factor) } else { cardsContent(runs, perRow: factor) }
        }
    }

    /// LibraryAdapter: the day's caption over its cards (14pt corners, 6pt apart, 12pt from the edges).
    private func gridContent(_ runs: [GalleryRun], columns count: Int) -> some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: count)
        return LazyVGrid(columns: columns, spacing: 6) {
            ForEach(Array(runs.enumerated()), id: \.element.id) { r, run in
                Section {
                    ForEach(run.range, id: \.self) { i in
                        let a = assets[i]
                        GalleryThumb(library: library, asset: a, selected: selection?.contains(a.localIdentifier))
                            .id(a.localIdentifier)
                            .modifier(ItemFrame(index: i))
                            .modifier(cellGestures(i))
                    }
                } header: {
                    dayHeader(run, index: r).padding(.horizontal, 5)
                }
            }
        }
        .padding(.horizontal, 12)
    }

    /// The day caption (12pt semibold capitals, 55 % white) and its count; while selecting, a tap selects the whole day.
    private func dayHeader(_ run: GalleryRun, index: Int) -> some View {
        let items = assets[run.range]
        let videos = items.filter { $0.mediaType == .video }.count
        let ids = Set(items.map(\.localIdentifier))
        let all = selection.map { ids.isSubset(of: $0) } ?? false
        return Button {
            guard let s = selection else { return }
            selection = all ? s.subtracting(ids) : s.union(ids)
        } label: {
            HStack(spacing: 8) {
                Text(run.title.uppercased()).font(.app(12, 600)).kerning(0.72).foregroundStyle(GalleryStyle.section).lineLimit(1)
                Spacer(minLength: 8)
                Text(GalleryText.dayCount(items.count - videos, videos)).font(.app(12)).foregroundStyle(GalleryStyle.section)
                if selecting {
                    GalleryIcon(name: all ? "ui_ic_check" : "kb_ui_ic_select_all_rest", size: 18, color: GalleryStyle.accent)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.top, 16).padding(.bottom, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .allowsHitTesting(selecting)
        .modifier(HeaderFrame(index: index))
        .accessibilityElement(children: .combine)
        .accessibilityHint(selecting ? L("gallery.select_day") : "")
    }

    /// ListRows: the day captions, under each a card of rows (20pt outer corners).
    private func listContent(_ runs: [GalleryRun], now: Date) -> some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(Array(runs.enumerated()), id: \.element.id) { r, run in
                dayHeader(run, index: r).padding(.horizontal, 8)
                ForEach(run.range, id: \.self) { i in
                    let a = assets[i]
                    GalleryListRow(library: library, asset: a, now: now, first: i == run.range.lowerBound,
                                   last: i == run.range.upperBound - 1, selected: selection?.contains(a.localIdentifier),
                                   lastOpened: lastOpened == a.localIdentifier, menu: { rowMenu(i) })
                        .id(a.localIdentifier)
                        .modifier(ItemFrame(index: i))
                        .modifier(cellGestures(i))
                }
            }
        }
        .padding(.horizontal, 12)
    }

    /// TimelineRows «Все фото»: the month's title (the year in grey) and count, the photos edge to edge with hairline gaps.
    private func timelineContent(_ runs: [GalleryRun], columns count: Int) -> some View {
        let gap = GalleryLayout.gap
        let columns = Array(repeating: GridItem(.flexible(), spacing: gap), count: count)
        return LazyVGrid(columns: columns, spacing: gap) {
            ForEach(Array(runs.enumerated()), id: \.element.id) { r, run in
                Section {
                    ForEach(run.range, id: \.self) { i in
                        let a = assets[i]
                        TimelineTile(library: library, asset: a, selected: selection?.contains(a.localIdentifier))
                            .id(a.localIdentifier)
                            .modifier(ItemFrame(index: i))
                            .modifier(cellGestures(i))
                    }
                } header: {
                    monthHeader(run).modifier(HeaderFrame(index: r))
                }
            }
        }
        .padding(.horizontal, max(0, GalleryLayout.edge - gap / 2))
    }

    private func monthHeader(_ run: GalleryRun) -> some View {
        let items = assets[run.range]
        let videos = items.filter { $0.mediaType == .video }.count
        let ids = Set(items.map(\.localIdentifier))
        let space = run.title.lastIndex(of: " ")
        return HStack(alignment: .lastTextBaseline, spacing: 8) {
            Group {
                if let space {
                    Text(run.title[..<space]) + Text(run.title[space...]).foregroundColor(GalleryStyle.muted)
                } else {
                    Text(run.title)
                }
            }
            .font(.app(GalleryLayout.titleSize, 700)).kerning(-0.01 * GalleryLayout.titleSize).foregroundStyle(.white).lineLimit(2)
            .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            Text(GalleryText.dayCount(items.count - videos, videos)).font(.app(13))
                .foregroundStyle(selecting ? GalleryStyle.accent : GalleryStyle.section)
        }
        .padding(.horizontal, 10).padding(.top, 20).padding(.bottom, 8)
        .contentShape(Rectangle())
        .onTapGesture {
            guard let s = selection else { return }
            selection = ids.isSubset(of: s) ? s.subtracting(ids) : s.union(ids)
        }
    }

    /// TimelineRows' month cards (the newest photo, the title on a fade) and year cards (three months side by side).
    @ViewBuilder private func cardsContent(_ runs: [GalleryRun], perRow: Int) -> some View {
        if perRow > 1 {
            // TimelineRows.span: a card is one span, so a wide screen has `perRow` of them side by side
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: perRow), spacing: 12) {
                ForEach(runs) { run in card(run) }
            }
            .padding(.horizontal, 9)
            .padding(.top, 6)
        } else {
            LazyVStack(spacing: 12) {
                ForEach(runs) { run in card(run) }
            }
            .padding(.horizontal, 9)
            .padding(.top, 6)
        }
    }

    private func card(_ run: GalleryRun) -> some View {
        TimelineCard(library: library, assets: assets, run: run, year: level == .years) {
            zoom(level == .years ? .months : .all, item: run.range.lowerBound)
        }
        .id("card:" + run.id)
        .modifier(ItemFrame(index: run.range.lowerBound))
    }

    // ---------------------------------------------------------------- taps, long press, drag selection

    private func cellGestures(_ i: Int) -> CellGestures {
        CellGestures(tap: { tap(i) }, pressed: { startDrag(i) }, moved: { p in dragged(to: p) }, ended: { endDrag() })
    }

    private func tap(_ i: Int) {
        guard assets.indices.contains(i) else { return }
        let asset = assets[i]
        guard var s = selection else {
            lastOpened = asset.localIdentifier
            GalleryLayout.lastOpened = asset.localIdentifier
            open(i)
            return
        }
        if s.contains(asset.localIdentifier) { s.remove(asset.localIdentifier) } else { s.insert(asset.localIdentifier) }
        selection = s
    }

    /// DragSelectionItemTouchListener.onLongPress: the selection starts with the pressed photo.
    private func startDrag(_ i: Int) {
        guard assets.indices.contains(i) else { return }
        var s = selection ?? []
        let base = s
        s.insert(assets[i].localIdentifier)
        selection = s
        let f = scroll.frames[i] ?? .zero
        scroll.drag = DragSelect(anchor: i, base: base, current: i, viewportY: f.midY + scroll.offset, x: f.midX)
        scroll.dragging = true
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    /// onDragRange: the photos from the pressed one to the one under the finger (rows and columns alike) over the selection before.
    private func dragged(to p: CGPoint) {
        guard var d = scroll.drag else { return }
        d.viewportY = p.y + scroll.offset
        d.x = p.x
        let changed: Bool
        if let i = scroll.index(at: p), i != d.current {
            d.current = i
            changed = true
            UISelectionFeedbackGenerator().selectionChanged()
        } else {
            changed = false
        }
        scroll.drag = d
        if changed { applyRange(d) }
        let edge: CGFloat = 56
        if d.viewportY < edge || d.viewportY > scroll.viewport.height - edge - (selecting ? 90 : 0) {
            if scroll.edgeTask == nil { scroll.edgeTask = Task { await autoScroll() } }
        } else {
            scroll.edgeTask?.cancel()
            scroll.edgeTask = nil
        }
    }

    private func endDrag() {
        scroll.edgeTask?.cancel()
        scroll.edgeTask = nil
        scroll.drag = nil
        scroll.dragging = false
    }

    private func applyRange(_ d: DragSelect) {
        let lo = min(d.anchor, d.current), hi = max(d.anchor, d.current)
        var s = d.base
        for i in lo...hi where assets.indices.contains(i) { s.insert(assets[i].localIdentifier) }
        if s != selection { selection = s }
    }

    /// Near the top / bottom edge the list scrolls a row at a time and the range follows the finger.
    private func autoScroll() async {
        while !Task.isCancelled, let d = scroll.drag, let proxy = scroll.proxy {
            let up = d.viewportY < 56
            let factor = Tablet.widthFactor(scroll.viewport.width)
            let step = mode == .grid ? prefs.columns * factor : mode == .timeline ? prefs.timelineColumns * factor : 1
            let target = up ? max(0, d.current - step) : min(assets.count - 1, d.current + step)
            withAnimation(.linear(duration: 0.12)) { proxy.scrollTo(assets[target].localIdentifier, anchor: up ? .top : .bottom) }
            try? await Task.sleep(nanoseconds: 130_000_000)
            guard var now = scroll.drag else { return }
            if let i = scroll.index(at: CGPoint(x: now.x, y: now.viewportY - scroll.offset)) { now.current = i }
            scroll.drag = now
            applyRange(now)
        }
    }

    // ---------------------------------------------------------------- pinch, view switch

    /// The grid's pinch changes its columns; the timeline's walks its columns and levels (GalleryLayout.zoomStep); the list has one.
    private func pinch(_ m: CGFloat) {
        guard m > 1.25 || m < 0.8 else { return }
        let spread = m > 1.25
        var p = library.prefs
        switch mode {
        case .grid:
            p.columns = spread ? max(GalleryPrefs.minColumns, p.columns - 1) : min(GalleryPrefs.maxColumns, p.columns + 1)
        case .timeline:
            let (l, c) = GalleryLayout.zoomStep(p.level, p.timelineColumns, spread: spread)
            if l != p.level {
                zoom(l, item: anchorItem(), columns: l == .all && p.level == .months ? c : nil)
                return
            }
            p.timelineColumns = c
        case .list:
            return
        }
        if p != library.prefs {
            UISelectionFeedbackGenerator().selectionChanged()
            withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) { library.prefs = p }
        }
    }

    /// TimelineRows.Nav.zoom / the control: the timeline at `l` around `item`.
    private func zoom(_ l: GalleryLayout.Level, item: Int, columns: Int? = nil) {
        guard mode == .timeline, l != level else { return }
        scroll.pendingAnchor = item
        var p = library.prefs
        p.level = l
        if let columns { p.timelineColumns = columns }
        library.prefs = p
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func anchorItem() -> Int { scroll.anchorItem() }

    /// GalleryViews.transition: the page dips, is laid out anew around the same photo and comes back on the entry spring.
    private func relayout() {
        let item = scroll.pendingAnchor ?? anchorItem()
        scroll.pendingAnchor = nil
        guard scroll.shownMode != mode || scroll.shownLevel != level else { return }
        scroll.shownMode = mode
        scroll.shownLevel = level
        if UIAccessibility.isReduceMotionEnabled {
            scrollTo(item)
            return
        }
        entering = true
        DispatchQueue.main.async {
            scrollTo(item)
            withAnimation(.spring(response: 0.42, dampingFraction: 0.7)) { entering = false }
        }
    }

    private func scrollTo(_ item: Int, runs: [GalleryRun]? = nil) {
        guard let proxy = scroll.proxy, assets.indices.contains(item) else { return }
        if mode == .timeline, level != .all {
            if let run = (runs ?? currentRuns(Date())).first(where: { $0.range.contains(item) }) { proxy.scrollTo("card:" + run.id, anchor: .top) }
        } else {
            proxy.scrollTo(assets[item].localIdentifier, anchor: .top)
        }
    }

    // ---------------------------------------------------------------- the timeline's control and capsule

    /// GalleryViews' level control: «Годы · Месяцы · Все фото» on the dark bar capsule, 300pt wide, floating over the tab bar.
    private var levelBar: some View {
        let levels = GalleryLayout.Level.allCases
        return HStack(spacing: 2) {
            ForEach(levels, id: \.self) { l in
                let on = l == level
                Button { zoom(l, item: anchorItem()) } label: {
                    Text(l.label).font(.app(13, on ? 600 : 400)).lineLimit(1).minimumScaleFactor(0.7)
                        .foregroundStyle(.white).frame(maxWidth: .infinity).frame(height: 32)
                        .background(on ? Color(white: 0.39) : Color.clear, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .frame(width: 300)
        .padding(3)
        .background(GalleryStyle.bar, in: Capsule())
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.35), radius: 8, y: 3)
        .animation(.spring(response: 0.3, dampingFraction: 0.86), value: level)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("gallery.timeline_zoom"))
    }

    // ---------------------------------------------------------------- the list's «⋮» and the selection bar

    /// ListRows' «⋮»: open, share, favourite, select, delete - the photo alone.
    @ViewBuilder private func rowMenu(_ i: Int) -> some View {
        let a = assets[i]
        Button { tap(i) } label: { Label(L("gallery.open"), image: "kb_ui_ic_open_rest") }
        Button {
            Task { share = ShareFiles(urls: await GalleryLibrary.shareFiles([a])) }
        } label: { Label(L("gallery.share"), image: "kb_ui_ic_share_rest") }
        Button {
            Task { await library.toggleFavourite(a) }
        } label: { Label(a.isFavorite ? L("gallery.unfavourite") : L("gallery.favourite"), image: a.isFavorite ? "ui_ic_heart_on" : "ui_ic_heart") }
        Button {
            var s = selection ?? []
            s.insert(a.localIdentifier)
            selection = s
        } label: { Label(L("gallery.select"), image: "kb_ui_ic_select_all_rest") }
        Button(role: .destructive) {
            single = a
            confirmDelete = true
        } label: { Label(L("gallery.delete"), image: "kb_ui_ic_trash_rest") }
    }

    private var picked: [PHAsset] {
        if let single { return [single] }
        guard let s = selection else { return [] }
        return assets.filter { s.contains($0.localIdentifier) }
    }

    private var deleteCount: Int { single != nil ? 1 : selection?.count ?? 0 }

    private var deleteTitle: String {
        let n = deleteCount
        return n == 1 ? L("gallery.delete_title") : String(format: L("gallery.delete_title_many"), GalleryView.countText(n))
    }

    private func selectionBar(_ s: Set<String>) -> some View {
        let n = s.count
        let chosen = assets.filter { s.contains($0.localIdentifier) }
        let favourite = !chosen.isEmpty && chosen.allSatisfy(\.isFavorite)
        return HStack(spacing: 2) {
            barButton("ui_ic_close", L("gallery.cancel")) { selection = nil }
            Button {
                selection = Set(assets.map(\.localIdentifier))
            } label: {
                Text(n == 0 ? L("gallery.select_items") : GalleryView.countText(n)).font(.app(15, 600))
                    .foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7).frame(maxWidth: .infinity, minHeight: 48)
                    .contentTransition(.numericText())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("gallery.select_all"))
            barButton("kb_ui_ic_compare_rest", L("gallery.compare"), enabled: n == 2) {
                if chosen.count == 2 { compare(chosen[0], chosen[1]) }
            }
            barButton(favourite ? "ui_ic_heart_on" : "ui_ic_heart", L("gallery.favourites"), enabled: n > 0) {
                Task { await library.setFavourite(chosen, !favourite) }
            }
            barButton("kb_ui_ic_share_rest", L("gallery.share"), enabled: n > 0) {
                Task { share = ShareFiles(urls: await GalleryLibrary.shareFiles(chosen)) }
            }
            barButton("kb_ui_ic_trash_rest", L("gallery.delete"), color: GalleryStyle.warn, enabled: n > 0) {
                single = nil
                confirmDelete = true
            }
        }
        .padding(.vertical, 6).padding(.horizontal, 8)
        .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 24))
        .padding(.horizontal, 12).padding(.bottom, 10)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func barButton(_ icon: String, _ label: String, color: Color = .white, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                GalleryIcon(name: icon, size: 22, color: color)
                Text(label).font(.app(10.5, 500)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.6)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.35)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func deleteSelected() {
        let p = picked
        let wasSingle = single != nil
        single = nil
        Task {
            let ok = await library.delete(p)
            toast = ok ? (p.count == 1 ? L("gallery.deleted") : String(format: L("gallery.deleted_n"), GalleryView.countText(p.count)))
                : L("gallery.delete_failed")
            if ok, !wasSingle { selection = nil }
        }
    }
}

// ---------------------------------------------------------------- frames and gestures

private struct ContentFrameKey: PreferenceKey {
    static let defaultValue = CGRect.zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) { value = nextValue() }
}

private struct ItemFramesKey: PreferenceKey {
    static let defaultValue: [Int: CGRect] = [:]
    static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) { value.merge(nextValue()) { $1 } }
}

private struct HeaderFramesKey: PreferenceKey {
    static let defaultValue: [Int: CGRect] = [:]
    static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) { value.merge(nextValue()) { $1 } }
}

/// An item's frame in the content (stable while scrolling: the drag selection's hit test, the anchor of a view switch).
private struct ItemFrame: ViewModifier {
    let index: Int
    func body(content: Content) -> some View {
        content.background(GeometryReader { g in
            Color.clear.preference(key: ItemFramesKey.self, value: [index: g.frame(in: .named(SelectableGrid.contentSpace))])
        })
    }
}

private struct HeaderFrame: ViewModifier {
    let index: Int
    func body(content: Content) -> some View {
        content.background(GeometryReader { g in
            Color.clear.preference(key: HeaderFramesKey.self, value: [index: g.frame(in: .named(SelectableGrid.contentSpace))])
        })
    }
}

/// A cell's tap, and its long press that goes on as a selection drag (the location in the content's space).
private struct CellGestures: ViewModifier {
    let tap: () -> Void
    let pressed: () -> Void
    let moved: (CGPoint) -> Void
    let ended: () -> Void
    @State private var started = false

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .onTapGesture { if !started { tap() } }
            .gesture(LongPressGesture(minimumDuration: 0.4)
                .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named(SelectableGrid.contentSpace)))
                .onChanged { value in
                    guard case .second(true, let drag) = value else { return }
                    if !started {
                        started = true
                        pressed()
                    }
                    if let drag { moved(drag.location) }
                }
                .onEnded { _ in
                    if started { ended() }
                    // the release after a long press is not a tap
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { started = false }
                })
    }
}

// ---------------------------------------------------------------- the list's row, the timeline's tile and cards

/// ListRows.Holder: a 104 x 78 rounded thumbnail (a video's length over its corner, the heart, the selection check), the file name
/// on up to two lines, the date / size line, the tags (format, big megapixels, SHDR, Ultra HDR) and a «⋮»; a card piece of the
/// day (20pt outer corners, hairlines from the text). The photo opened last is tinted in the accent.
@MainActor
struct GalleryListRow<Actions: View>: View {
    let library: GalleryLibrary
    let asset: PHAsset
    let now: Date
    let first: Bool
    let last: Bool
    var selected: Bool?
    var lastOpened = false
    @ViewBuilder let menu: () -> Actions
    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    static var thumbW: CGFloat { 104 }
    static var thumbH: CGFloat { 78 }

    var body: some View {
        let kind = library.kinds[asset.localIdentifier]
        let meta = RowMeta.shared.info(asset)
        let tags = GalleryText.tags(asset, kind: kind, route: meta?.route, ultraHdr: meta?.ultraHdr ?? false)
        let on = selected == true
        let fill = on ? Self.tint(0.24) : lastOpened ? Self.tint(0.12) : GalleryStyle.card
        let name = kind?.fileName ?? ""
        HStack(spacing: 12) {
            ZStack {
                Color(red: 0x3A / 255, green: 0x3A / 255, blue: 0x3C / 255)
                if let image { Image(uiImage: image).resizable().scaledToFill() }
            }
            .frame(width: Self.thumbW, height: Self.thumbH)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(alignment: .bottomTrailing) {
                if asset.mediaType == .video {
                    Text(VideoResume.clock(Int64(asset.duration * 1000))).font(.app(11, 700)).foregroundStyle(.white)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 7)).padding(5)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if asset.isFavorite { GalleryIcon(name: "ui_ic_heart_on", size: 14, color: GalleryStyle.favourite).padding(6) }
            }
            .overlay(alignment: .topLeading) {
                if let selected { SelectCheck(on: selected, size: 22).padding(5) }
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(name).font(.app(15, 600)).foregroundStyle(lastOpened ? GalleryStyle.accent : .white).lineLimit(2)
                Text(GalleryText.secondary(asset, bytes: meta?.bytes ?? 0, now: now)).font(.app(13)).foregroundStyle(GalleryStyle.muted)
                    .lineLimit(2).padding(.top, 4)
                if !tags.isEmpty {
                    TagFlow(tags: tags).padding(.top, 6)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Menu { menu() } label: {
                GalleryIcon(name: "ui_ic_more", size: 20, color: GalleryStyle.muted).frame(width: 44, height: 44).contentShape(Circle())
            }
            .opacity(selected == nil ? 1 : 0)
            .disabled(selected != nil)
            .accessibilityLabel(L("gallery.actions") + " " + name)
        }
        .padding(.leading, 10).padding(.trailing, 2).padding(.vertical, 9)
        .frame(minHeight: 96)
        .background(UnevenRoundedRectangle(topLeadingRadius: first ? 20 : 0, bottomLeadingRadius: last ? 20 : 0,
                                           bottomTrailingRadius: last ? 20 : 0, topTrailingRadius: first ? 20 : 0).fill(fill))
        .overlay(alignment: .bottom) {
            if !last { Rectangle().fill(GalleryStyle.hairline).frame(height: 0.5).padding(.leading, 10 + Self.thumbW + 12) }
        }
        .animation(.easeOut(duration: 0.18), value: on)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(on ? .isSelected : [])
        .task(id: asset.localIdentifier) {
            image = await library.thumbnail(asset, side: Self.thumbW * scale)
        }
    }

    /// RowViews.tint: the accent at `alpha` over the card colour.
    static func tint(_ alpha: Double) -> Color {
        Color(red: (0x2C * (1 - alpha) + 0xFF * alpha) / 255, green: (0x2C * (1 - alpha) + 0xD4 * alpha) / 255,
              blue: (0x2E * (1 - alpha) + 0x47 * alpha) / 255)
    }
}

/// RowViews.tag in a FlowRow: 10.5pt bold capsules, 5pt apart, wrapping.
struct TagFlow: View {
    let tags: [GalleryText.Tag]
    var body: some View {
        FlowLayout(spacing: 5) {
            ForEach(tags, id: \.self) { t in
                let fg: Color = t.kind == .format ? GalleryStyle.accent : t.kind == .hdr ? Color(red: 1, green: 0xD6 / 255, blue: 0x0A / 255)
                    : Color.white.opacity(0.85)
                let bg: Color = t.kind == .format ? GalleryStyle.accent.opacity(0.18) : t.kind == .hdr
                    ? Color(red: 1, green: 0xD6 / 255, blue: 0x0A / 255).opacity(0.18) : Color.white.opacity(0.12)
                Text(t.label).font(.app(10.5, 700)).kerning(0.3).foregroundStyle(fg).lineLimit(1)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(bg, in: RoundedRectangle(cornerRadius: 7))
            }
        }
        .accessibilityHidden(true)
    }
}

/// RowViews.check: a light ring on a dark disc while selecting, the accent disc with the tick when chosen (it pops).
struct SelectCheck: View {
    let on: Bool
    var size: CGFloat = 24
    var body: some View {
        ZStack {
            Circle().fill(on ? GalleryStyle.accent : Color.black.opacity(0.4))
            Circle().strokeBorder(Color.white, lineWidth: on ? 0 : 1.5)
            if on { GalleryIcon(name: "ui_ic_check", size: size * 0.56, color: GalleryStyle.ink).transition(.scale) }
        }
        .frame(width: size, height: size)
        .scaleEffect(on ? 1 : 0.92)
        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: on)
    }
}

/// TimelineRows.TileHolder: a square tile (2.8pt corners), the video's length in plain white with a soft shadow, a RAW's small mark,
/// the heart; selected: the check and the picture dimmed.
@MainActor
struct TimelineTile: View {
    let library: GalleryLibrary
    let asset: PHAsset
    var selected: Bool?
    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    var body: some View {
        GalleryStyle.card
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill().opacity(selected == true ? 0.62 : 1) }
            }
            .clipShape(RoundedRectangle(cornerRadius: GalleryLayout.tileRadius))
            .overlay(alignment: .bottomTrailing) {
                if let selected {
                    SelectCheck(on: selected, size: 22).padding(5)
                } else if asset.mediaType == .video {
                    Text(VideoResume.clock(Int64(asset.duration * 1000))).font(.app(11, 600)).foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.7), radius: 3).padding(5)
                }
            }
            .overlay(alignment: .topTrailing) {
                if library.kinds[asset.localIdentifier]?.format == .raw {
                    Text("RAW").font(.app(8.5, 700)).foregroundStyle(.white)
                        .padding(.horizontal, 4).padding(.vertical, 1)
                        .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 5)).padding(4)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if asset.isFavorite { GalleryIcon(name: "ui_ic_heart_on", size: 12).shadow(radius: 2).padding(5) }
            }
            .animation(.easeOut(duration: 0.18), value: selected)
            .accessibilityElement()
            .accessibilityLabel(asset.creationDate.map { PhotoDetails.dayTime($0, now: Date()) } ?? "")
            .accessibilityAddTraits(selected == true ? .isSelected : [])
            .task(id: asset.localIdentifier) {
                image = await library.thumbnail(asset, side: 140 * scale / 2)
            }
    }
}

/// TimelineRows.CardHolder: a month card (its newest photo, 0.62 as tall as wide) or a year card (three months' photos side by side,
/// 0.78), the title at the top on a fade (26 / 34pt bold), the counts under it; a tap zooms in.
@MainActor
struct TimelineCard: View {
    let library: GalleryLibrary
    let assets: [PHAsset]
    let run: GalleryRun
    let year: Bool
    let tap: () -> Void
    @State private var images: [Int: UIImage] = [:]
    @Environment(\.displayScale) private var scale

    var body: some View {
        let covers = year ? GallerySections.monthCovers(assets, run.range, max: 3) : [run.range.lowerBound]
        Button(action: tap) {
            GeometryReader { g in
                let w = g.size.width, h = g.size.height, gap = GalleryLayout.gap
                ZStack(alignment: .topLeading) {
                    GalleryStyle.card
                    if year {
                        HStack(spacing: gap) {
                            cover(covers, 0).frame(width: (w - gap) * 2 / 3)
                            VStack(spacing: gap) {
                                cover(covers, 1)
                                cover(covers, 2)
                            }
                        }
                    } else {
                        cover(covers, 0)
                    }
                    LinearGradient(colors: [Color.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).frame(height: min(h, 110))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(run.title).font(.app(year ? 34 : 26, 700)).kerning(year ? -0.4 : -0.3).foregroundStyle(.white).lineLimit(2)
                            .shadow(color: .black.opacity(0.4), radius: 6)
                        Text(GalleryText.counts(assets[run.range], kinds: library.kinds)).font(.app(13)).foregroundStyle(.white.opacity(0.9))
                            .lineLimit(2).shadow(color: .black.opacity(0.4), radius: 4)
                    }
                    .padding(16)
                }
            }
            .aspectRatio(1 / (year ? 0.78 : 0.62), contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: GalleryLayout.cardRadius))
        }
        .buttonStyle(PressScaleStyle(scale: 0.97))
        .accessibilityLabel(run.title + ", " + GalleryText.counts(assets[run.range], kinds: library.kinds) + ", "
                            + (year ? L("gallery.open_months") : L("gallery.open_month")))
        .task(id: run.id) {
            for (i, index) in covers.enumerated() {
                // an iPad has Tablet.widthFactor cards a row: each that share of the width
                let width = UIScreen.main.bounds.width / CGFloat(Tablet.widthFactor(UIScreen.main.bounds.width))
                let side = width * scale * (year && i > 0 ? 1.0 / 3 : year ? 2.0 / 3 : 1)
                images[i] = await library.thumbnail(assets[index], side: side)
            }
        }
    }

    @ViewBuilder private func cover(_ covers: [Int], _ i: Int) -> some View {
        ZStack {
            GalleryStyle.card
            if i < covers.count, let image = images[i] { Image(uiImage: image).resizable().scaledToFill() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }
}

/// Motion.press: a press shrinks the control a little, the release springs it back.
struct PressScaleStyle: ButtonStyle {
    var scale: CGFloat = 0.94
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(configuration.isPressed ? .easeOut(duration: 0.12) : .spring(response: 0.3, dampingFraction: 0.6),
                       value: configuration.isPressed)
    }
}

// ---------------------------------------------------------------- what moves with the scroll

/// A long press's selection drag: the photo pressed, the selection before it, the photo under the finger, the finger in the viewport.
struct DragSelect {
    let anchor: Int
    let base: Set<String>
    var current: Int
    var viewportY: CGFloat
    var x: CGFloat
}

/// The grid's scroll state: what changes on every scroll frame lives here, read only by the overlays that draw it (the fast
/// scroller, the month capsule) and by the gesture handlers, so the page itself is not laid out again per frame.
@MainActor
@Observable
final class GridScroll {
    /// the content's top in the scroll view (negative once scrolled), its height, the scroll view's size
    var offset: CGFloat = 0
    var contentHeight: CGFloat = 1
    var viewport: CGSize = .zero
    /// the items' and the section titles' frames in the content (the ones laid out)
    var frames: [Int: CGRect] = [:]
    var headers: [Int: CGRect] = [:]
    /// FastScroller: shown while the list moves (1.2 s after), the finger's fraction while dragged, the day there
    var scrollerVisible = false
    var thumb: CGFloat?
    var thumbLabel = ""
    var dragging = false
    var thumbDragging: Bool { thumb != nil }
    @ObservationIgnored var lastMove = Date.distantPast
    @ObservationIgnored var drag: DragSelect?
    @ObservationIgnored var proxy: ScrollViewProxy?
    @ObservationIgnored var edgeTask: Task<Void, Never>?
    @ObservationIgnored var pendingAnchor: Int?
    @ObservationIgnored var shownMode: GalleryLayout.Mode?
    @ObservationIgnored var shownLevel: GalleryLayout.Level?

    nonisolated init() {}

    /// FastScroller: the thumb shows while the list moves and fades 1.2 s after it stops (only for a list of over two screens).
    func touchScroller() {
        OcrIndex.shared.touch() // P617: the text index waits while the grid moves
        guard contentHeight > viewport.height * 2 else { return }
        lastMove = Date()
        if !scrollerVisible { withAnimation(.easeOut(duration: 0.16)) { scrollerVisible = true } }
        let mark = lastMove
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            if lastMove == mark, thumb == nil { withAnimation(.easeOut(duration: 0.26)) { scrollerVisible = false } }
        }
    }

    /// The photo under a point of the content (its frame, else the nearest one in the same row).
    func index(at p: CGPoint) -> Int? {
        if let hit = frames.first(where: { $0.value.contains(p) }) { return hit.key }
        let row = frames.filter { $0.value.minY - 3 <= p.y && $0.value.maxY + 3 >= p.y }
        return row.min(by: { abs($0.value.midX - p.x) < abs($1.value.midX - p.x) })?.key
    }

    /// GalleryViews.anchorItem: the first photo fully shown (a card: its first photo).
    func anchorItem() -> Int {
        let top = -offset
        return frames.filter { $0.value.minY >= top - 1 }
            .min(by: { $0.value.minY == $1.value.minY ? $0.key < $1.key : $0.value.minY < $1.value.minY })?.key
            ?? frames.keys.min() ?? 0
    }
}

/// The sections made for a list, the view and the day (GalleryLayout's rows).
final class RunsCache {
    var key = ""
    var runs: [GalleryRun] = []
}

/// FastScroller (P498): a pill thumb at the right edge that shows while the list scrolls and fades 1.2 s after it stops; dragged, it
/// moves the list by its share of the height and a bubble beside the finger names the day there («6 октября»).
@MainActor
struct FastScroller: View {
    let scroll: GridScroll
    let runs: [GalleryRun]
    let height: CGFloat
    let go: (Int) -> Void

    private let thumbH: CGFloat = 52

    var body: some View {
        let track = max(1, height - thumbH - 16)
        let range = max(1, scroll.contentHeight - height)
        let fraction = scroll.thumb ?? min(1, max(0, -scroll.offset / range))
        let y = 8 + track * fraction
        let can = scroll.contentHeight > height * 2
        let shown = scroll.scrollerVisible && can
        ZStack(alignment: .topTrailing) {
            Color.clear
            if scroll.thumb != nil, !scroll.thumbLabel.isEmpty {
                Text(scroll.thumbLabel).font(.app(14, 600)).foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(GalleryStyle.bar, in: Capsule())
                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5))
                    .shadow(color: .black.opacity(0.3), radius: 4)
                    .offset(x: -28, y: y + thumbH / 2 - 18)
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
            Capsule().fill(GalleryStyle.accent)
                .frame(width: 6, height: thumbH)
                .scaleEffect(x: scroll.thumb != nil ? 1.6 : shown ? 1 : 0.6, y: 1)
                .opacity(shown ? 1 : 0)
                .frame(width: 40, height: thumbH + 48)
                .contentShape(Rectangle())
                .offset(x: 13, y: y - 24)
                .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named("gallery.scroller"))
                    .onChanged { g in
                        guard scroll.scrollerVisible, can else { return }
                        dragged(min(1, max(0, (g.location.y - 8) / max(1, height - 16))))
                    }
                    .onEnded { _ in
                        scroll.thumb = nil
                        scroll.touchScroller()
                    })
                .allowsHitTesting(shown)
                .accessibilityLabel(L("gallery.fast_scroll"))
        }
        .coordinateSpace(name: "gallery.scroller")
        .animation(.easeOut(duration: 0.12), value: scroll.thumb != nil)
    }

    private func dragged(_ f: CGFloat) {
        scroll.thumb = f
        guard let last = runs.last?.range.upperBound, last > 0 else { return }
        let item = min(last - 1, Int((f * CGFloat(last - 1)).rounded()))
        let label = runs.first(where: { $0.range.contains(item) })?.title ?? ""
        if label != scroll.thumbLabel {
            scroll.thumbLabel = label
            UISelectionFeedbackGenerator().selectionChanged()
        }
        go(item)
    }
}

/// GalleryViews.updateSticky: the timeline's month on screen in a capsule over the photos, pushed away by the next month's title;
/// none at the top or while the month's own title is still half on screen.
@MainActor
struct StickyMonth: View {
    let scroll: GridScroll
    let runs: [GalleryRun]

    var body: some View {
        let top = -scroll.offset
        let first = scroll.frames.filter { $0.value.maxY > top }.min(by: { $0.key < $1.key })?.key
        let r = first.flatMap { f in runs.firstIndex(where: { $0.range.contains(f) }) }
        let hidden = r.flatMap { scroll.headers[$0] }.map { $0.midY > top } ?? false
        let capsuleBottom: CGFloat = 8 + 28
        let next = r.flatMap { scroll.headers[$0 + 1] }.map { $0.midY - top } ?? .greatestFiniteMagnitude
        let push = next < capsuleBottom + 6 ? next - capsuleBottom - 6 : 0
        VStack {
            HStack {
                if top > 8, let r, !hidden {
                    GalleryPill(text: runs[r].title).transition(.opacity)
                }
                Spacer()
            }
            Spacer()
        }
        .padding(.leading, 10).padding(.top, 8)
        .offset(y: push)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .animation(.easeOut(duration: 0.16), value: top > 8 && !hidden)
    }
}
