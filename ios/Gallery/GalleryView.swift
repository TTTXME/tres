import ImageIO
import Photos
import SwiftUI
import UIKit

/// The gallery (Android SGallery): the bottom tabs «Фото» / «Альбомы» / «Поиск» (GalleryTabBar) - ImageLibraryFragment's grid by
/// month with its «Выбрать» (multi-select: compare two, favourites, share, delete), «Вид» (order, columns) and «Фильтр» (format,
/// favourites, years) sheets, AlbumsPage, SearchPage - ImageViewerFragment's viewer with its chrome (share, edit, compare, delete,
/// details, favourite), the compare screen and the photo editor; over the photos the app saved («SCAMERA» album) or the whole
/// library. The entry point of the camera screen's gallery button. Videos are listed with the photos; a video page opens the
/// gallery's player (Video/VideoPlayerView, Android VideoPlayerActivity).
@MainActor
struct GalleryView: View {
    @State private var library = GalleryLibrary()
    @State private var tab = Tab.photos
    @State private var viewer: ViewerStart?
    @State private var compare: CompareStart?
    @State private var album: GalleryLibrary.Album?
    @State private var selection: Set<String>?
    @State private var sheet: Sheet?
    @Environment(\.dismiss) private var dismiss

    enum Tab: Int, CaseIterable { case photos, albums, search }
    enum Sheet: Int, Identifiable { case view, filter; var id: Int { rawValue } }

    /// The viewer's photos and the one it opens on; `live`: the «Фото» tab's list (it follows the library and the filter).
    struct ViewerStart: Identifiable {
        let id = UUID()
        let assets: [PHAsset]
        let index: Int
        var live = false
    }

    struct CompareStart: Identifiable {
        let id = UUID()
        let first: PHAsset
        let second: PHAsset
    }

    init() {}

    var body: some View {
        ZStack(alignment: .bottom) {
            GalleryStyle.page.ignoresSafeArea()
            VStack(spacing: 0) {
                switch tab {
                case .photos: photosPage
                case .albums:
                    AlbumsPage(library: library, back: { dismiss() }) { album = $0 }
                case .search:
                    GallerySearchPage(library: library, back: { dismiss() }, open: { list, i in viewer = ViewerStart(assets: list, index: i) },
                                      openAlbum: { album = $0 })
                }
            }
            if selection == nil, library.access == .granted { tabBar }
        }
        // pref_theme_key (GalleryTheme): the system sheets, menus and alerts follow it; the gallery's own pages stay dark
        .environment(\.colorScheme, .dark)
        .preferredColorScheme(GalleryTheme.scheme)
        .task { await library.start() }
        .onDisappear { library.stop() }
        .fullScreenCover(item: $viewer) { start in
            PhotoViewer(library: library, start: start)
        }
        .fullScreenCover(item: $compare) { c in CompareView(library: library, first: c.first, second: c.second) }
        .fullScreenCover(item: $album) { a in AlbumGridPage(library: library, album: a) }
        .sheet(item: $sheet) { s in
            Group {
                if s == .view { GalleryViewSheet(library: library) } else { GalleryFilterSheet(library: library) }
            }
            .presentationDetents([.medium, .large])
        }
    }

    // ---------------------------------------------------------------- «Фото»

    private var photosPage: some View {
        let shown = library.shown
        return VStack(spacing: 0) {
            header(shown)
            content(shown)
        }
    }

    private func header(_ shown: [PHAsset]) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 10) {
                RoundIconButton(icon: "ui_ic_back", label: L("gallery.back")) { dismiss() }
                VStack(alignment: .leading, spacing: 1) {
                    Text(L("gallery.photos")).font(.system(size: 28, weight: .bold)).foregroundStyle(.white)
                    if library.access == .granted {
                        Text(Self.countText(shown.count)).font(.system(size: 13)).foregroundStyle(GalleryStyle.muted)
                    }
                }
                Spacer()
                if library.access == .granted {
                    RoundIconButton(icon: "kb_ui_ic_select_all_rest", label: L("gallery.select")) { selection = [] }
                    RoundIconButton(icon: "kb_ui_ic_filter_rest", label: L("gallery.filter"),
                                    background: library.prefs.filtering ? GalleryStyle.accent.opacity(0.35) : GalleryStyle.card) { sheet = .filter }
                    RoundIconButton(icon: "kb_ui_ic_sort_rest", label: L("gallery.view")) { sheet = .view }
                }
            }
            .padding(.horizontal, 16).padding(.top, 8)
            if library.access == .granted {
                GallerySegments(names: [L("gallery.camera"), L("gallery.all")], selected: library.source.rawValue) { i in
                    library.source = GalleryLibrary.Source(rawValue: i) ?? .camera
                }
                if library.prefs.filtering {
                    HStack {
                        GalleryChip(text: L("gallery.filter") + " · " + L("gallery.filter_reset"), on: true) { library.prefs.clearFilter() }
                        Spacer()
                    }
                    .padding(.horizontal, 18)
                }
            }
        }
    }

    /// GalleryUi.shots: «1 снимок», «3 снимка», «5 снимков»
    static func countText(_ n: Int) -> String {
        let m10 = n % 10, m100 = n % 100
        let key = n == 1 ? L("gallery.count.single") : m10 == 1 && m100 != 11 ? L("gallery.count.one")
            : (2...4).contains(m10) && !(12...14).contains(m100) ? L("gallery.count.few") : L("gallery.count.many")
        return String(format: key, n)
    }

    @ViewBuilder private func content(_ shown: [PHAsset]) -> some View {
        switch library.access {
        case .unknown:
            Spacer()
            ProgressView().tint(.white)
            Spacer()
        case .denied:
            Spacer()
            VStack(spacing: 14) {
                Text(L("gallery.no_access")).font(.system(size: 15)).foregroundStyle(GalleryStyle.muted).multilineTextAlignment(.center)
                Button(L("camera.open_settings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(GalleryStyle.ink)
                .padding(.horizontal, 18).frame(minHeight: 40).background(GalleryStyle.accent, in: Capsule())
            }
            .padding(32)
            Spacer()
        case .granted:
            if shown.isEmpty {
                Spacer()
                Text(library.prefs.filtering ? L("gallery.nothing_filtered") : library.source == .camera ? L("gallery.empty_camera") : L("gallery.empty"))
                    .font(.system(size: 15)).foregroundStyle(GalleryStyle.muted).multilineTextAlignment(.center).padding(32)
                Spacer()
            } else {
                SelectableGrid(library: library, assets: shown, selection: $selection, bottomInset: 96,
                               open: { i in viewer = ViewerStart(assets: shown, index: i, live: true) },
                               compare: { a, b in compare = CompareStart(first: a, second: b) })
            }
        }
    }

    // ---------------------------------------------------------------- GalleryTabBar

    private var tabBar: some View {
        let icons = ["kb_ui_ic_photos_rest", "kb_ui_ic_albums_rest", "kb_ui_ic_search_rest"]
        let names = [L("gallery.photos"), L("gallery.albums"), L("gallery.search")]
        return HStack(spacing: 4) {
            ForEach(Tab.allCases, id: \.rawValue) { t in
                let on = t == tab
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { tab = t }
                } label: {
                    VStack(spacing: 3) {
                        GalleryIcon(name: icons[t.rawValue], size: 22, color: on ? GalleryStyle.accent : GalleryStyle.muted)
                        Text(names[t.rawValue]).font(.system(size: 11, weight: .semibold)).lineLimit(1)
                            .foregroundStyle(on ? GalleryStyle.accent : GalleryStyle.muted)
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(on ? GalleryStyle.thumb : Color.clear, in: RoundedRectangle(cornerRadius: 22))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(names[t.rawValue])
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 26))
        .overlay(RoundedRectangle(cornerRadius: 26).strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5))
        .padding(.horizontal, 40).padding(.bottom, 8)
    }
}

// ---------------------------------------------------------------- the grid with its selection

/// ThumbGrid + LibraryAdapter + SelectionBar: the photos by month in `library.prefs.columns` columns (a pinch changes them), a
/// tap opens the viewer; «Выбрать» or a long press selects - taps then toggle, a month's title selects its photos - and the bar
/// at the bottom: × · the count (a tap selects every photo shown) · «Сравнить» (exactly two) · «Избранное» · «Поделиться» ·
/// «Удалить».
@MainActor
struct SelectableGrid: View {
    let library: GalleryLibrary
    let assets: [PHAsset]
    @Binding var selection: Set<String>?
    var bottomInset: CGFloat = 16
    let open: (Int) -> Void
    let compare: (PHAsset, PHAsset) -> Void
    @State private var share: ShareFiles?
    @State private var confirmDelete = false
    @State private var toast: String?

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: library.prefs.columns)
        let sections = GalleryLibrary.sections(assets)
        let index = Dictionary(assets.enumerated().map { ($1.localIdentifier, $0) }, uniquingKeysWith: { a, _ in a })
        return ZStack(alignment: .bottom) {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 2, pinnedViews: [.sectionHeaders]) {
                    ForEach(sections.indices, id: \.self) { s in
                        Section {
                            ForEach(sections[s].items, id: \.localIdentifier) { asset in
                                GalleryThumb(library: library, asset: asset, selected: selection?.contains(asset.localIdentifier))
                                    .onTapGesture { tap(asset, index[asset.localIdentifier] ?? 0) }
                                    .onLongPressGesture { startSelecting(asset) }
                            }
                        } header: {
                            sectionHeader(sections[s].title, sections[s].items)
                        }
                    }
                }
                .padding(.bottom, selection != nil ? 110 : bottomInset)
            }
            .simultaneousGesture(MagnifyGesture().onEnded { v in
                var p = library.prefs
                if v.magnification > 1.25 { p.columns = Swift.max(GalleryPrefs.minColumns, p.columns - 1) }
                else if v.magnification < 0.8 { p.columns = Swift.min(GalleryPrefs.maxColumns, p.columns + 1) }
                if p != library.prefs { withAnimation(.easeOut(duration: 0.2)) { library.prefs = p } }
            })
            if let picked = selection { selectionBar(picked) }
            if let toast {
                GalleryPill(text: toast).padding(.bottom, selection != nil ? 100 : bottomInset + 10)
                    .transition(.opacity)
                    .task(id: toast) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        self.toast = nil
                    }
            }
        }
        .sheet(item: $share) { s in ActivitySheet(items: s.urls) }
        .confirmationDialog(deleteTitle, isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("gallery.delete"), role: .destructive) { deleteSelected() }
            Button(L("gallery.cancel"), role: .cancel) {}
        } message: {
            Text((selection?.count ?? 0) == 1 ? L("gallery.delete_message") : L("gallery.delete_message_many"))
        }
        .onChange(of: library.version) { _, _ in
            // photos deleted elsewhere leave the selection
            if let s = selection { selection = s.intersection(Set(assets.map(\.localIdentifier))) }
        }
    }

    private func sectionHeader(_ title: String, _ items: [PHAsset]) -> some View {
        HStack {
            Text(title).font(.system(size: 17, weight: .semibold)).foregroundStyle(.white)
            Spacer()
            if let s = selection {
                let ids = Set(items.map(\.localIdentifier))
                let all = ids.isSubset(of: s)
                Button(all ? L("gallery.deselect") : L("gallery.select")) {
                    selection = all ? s.subtracting(ids) : s.union(ids)
                }
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(GalleryStyle.accent)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(GalleryStyle.page.opacity(0.94))
    }

    private func tap(_ asset: PHAsset, _ i: Int) {
        guard var s = selection else {
            open(i)
            return
        }
        if s.contains(asset.localIdentifier) { s.remove(asset.localIdentifier) } else { s.insert(asset.localIdentifier) }
        selection = s
    }

    private func startSelecting(_ asset: PHAsset) {
        var s = selection ?? []
        s.insert(asset.localIdentifier)
        selection = s
    }

    private var picked: [PHAsset] {
        guard let s = selection else { return [] }
        return assets.filter { s.contains($0.localIdentifier) }
    }

    private var deleteTitle: String {
        let n = selection?.count ?? 0
        return n == 1 ? L("gallery.delete_title") : String(format: L("gallery.delete_title_many"), GalleryView.countText(n))
    }

    private func selectionBar(_ s: Set<String>) -> some View {
        let n = s.count
        let favourite = !picked.isEmpty && picked.allSatisfy(\.isFavorite)
        return HStack(spacing: 2) {
            barButton("ui_ic_close", L("gallery.cancel")) { selection = nil }
            Button {
                selection = Set(assets.map(\.localIdentifier))
            } label: {
                Text(n == 0 ? L("gallery.select_items") : GalleryView.countText(n)).font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7).frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("gallery.select_all"))
            barButton("kb_ui_ic_compare_rest", L("gallery.compare"), enabled: n == 2) {
                let p = picked
                if p.count == 2 { compare(p[0], p[1]) }
            }
            barButton(favourite ? "ui_ic_heart_on" : "ui_ic_heart", L("gallery.favourites"), enabled: n > 0) {
                let p = picked
                Task { await library.setFavourite(p, !favourite) }
            }
            barButton("kb_ui_ic_share_rest", L("gallery.share"), enabled: n > 0) {
                let p = picked
                Task { share = ShareFiles(urls: await GalleryLibrary.shareFiles(p)) }
            }
            barButton("kb_ui_ic_trash_rest", L("gallery.delete"), color: GalleryStyle.warn, enabled: n > 0) { confirmDelete = true }
        }
        .padding(.vertical, 6).padding(.horizontal, 8)
        .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 24))
        .padding(.horizontal, 12).padding(.bottom, 10)
    }

    private func barButton(_ icon: String, _ label: String, color: Color = .white, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                GalleryIcon(name: icon, size: 22, color: color)
                Text(label).font(.system(size: 10.5, weight: .medium)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.6)
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
        Task {
            let ok = await library.delete(p)
            toast = ok ? String(format: L("gallery.deleted_n"), GalleryView.countText(p.count)) : L("gallery.delete_failed")
            if ok { selection = nil }
        }
    }
}

struct ShareFiles: Identifiable {
    let id = UUID()
    let urls: [URL]
}

/// ThumbGrid's cell: a square thumbnail, the RAW / favourite badges; selecting: the check (accent) or an empty ring.
@MainActor
struct GalleryThumb: View {
    let library: GalleryLibrary
    let asset: PHAsset
    var selected: Bool? = nil
    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    var body: some View {
        Color(white: 0.12)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill() }
            }
            .clipped()
            .overlay(alignment: .bottom) {
                // ThumbGrid: the part of a video watched (VideoResume)
                if asset.mediaType == .video {
                    let watched = VideoResume.progress(asset.localIdentifier)
                    if watched >= 0 {
                        GeometryReader { g in
                            Rectangle().fill(GalleryStyle.accent).frame(width: g.size.width * CGFloat(watched), height: 3)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                        }
                    }
                }
            }
            .overlay(alignment: .bottomLeading) {
                HStack(spacing: 4) {
                    if asset.mediaType == .video {
                        GalleryIcon(name: "ui_ic_play", size: 14)
                        Text(VideoResume.clock(Int64(asset.duration * 1000))).font(.system(size: 10, weight: .semibold).monospacedDigit())
                            .foregroundStyle(.white).shadow(color: .black, radius: 1)
                    }
                    if asset.isFavorite { GalleryIcon(name: "ui_ic_heart_on", size: 14) }
                    if library.kinds[asset.localIdentifier]?.raw ?? false {
                        Text("RAW").font(.system(size: 9, weight: .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 4).padding(.vertical, 1).background(GalleryStyle.bar, in: Capsule())
                    }
                }
                .padding(4)
            }
            .overlay(alignment: .topTrailing) {
                if let selected {
                    ZStack {
                        Circle().fill(selected ? GalleryStyle.accent : Color.black.opacity(0.25)).frame(width: 22, height: 22)
                        Circle().strokeBorder(Color.white, lineWidth: selected ? 0 : 1.5).frame(width: 22, height: 22)
                        if selected { GalleryIcon(name: "ui_ic_check", size: 14, color: GalleryStyle.ink) }
                    }
                    .padding(6)
                }
            }
            .overlay { if selected == true { Rectangle().strokeBorder(GalleryStyle.accent, lineWidth: 2) } }
            .contentShape(Rectangle())
            .accessibilityElement()
            .accessibilityLabel(asset.creationDate.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "")
            .accessibilityAddTraits(selected == true ? .isSelected : [])
            .task(id: asset.localIdentifier) {
                image = await library.thumbnail(asset, side: 200 * scale / 2)
            }
    }
}

// ---------------------------------------------------------------- «Вид» and «Фильтр»

/// GallerySheets.showView: the order and the columns (the grid changes at once, the sheet stays).
@MainActor
struct GalleryViewSheet: View {
    let library: GalleryLibrary
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(L("gallery.view_title")).font(.system(size: 22, weight: .bold)).foregroundStyle(.white).padding(.horizontal, 18)
                Text(L("gallery.view_sub")).font(.system(size: 14)).foregroundStyle(GalleryStyle.muted).padding(.horizontal, 18).padding(.top, 4)
                GallerySection(text: L("gallery.order"))
                GallerySegments(names: [L("gallery.newest_first"), L("gallery.oldest_first")], selected: library.prefs.oldestFirst ? 1 : 0) {
                    library.prefs.oldestFirst = $0 == 1
                }
                GallerySection(text: L("gallery.columns"))
                GallerySegments(names: (GalleryPrefs.minColumns...GalleryPrefs.maxColumns).map(String.init),
                                selected: library.prefs.columns - GalleryPrefs.minColumns) {
                    library.prefs.columns = GalleryPrefs.minColumns + $0
                }
                Button { dismiss() } label: {
                    Text(L("gallery.done")).font(.system(size: 16, weight: .semibold)).foregroundStyle(GalleryStyle.ink)
                        .frame(maxWidth: .infinity, minHeight: 48).background(GalleryStyle.accent, in: Capsule())
                }
                .buttonStyle(.plain).padding(18)
            }
            .padding(.top, 20)
        }
        .background(GalleryStyle.page.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }
}

/// GallerySheets.showFilter: format, status (favourites) and time (the library's years, several at once), «Сбросить» and «Готово».
@MainActor
struct GalleryFilterSheet: View {
    let library: GalleryLibrary
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(L("gallery.filter")).font(.system(size: 22, weight: .bold)).foregroundStyle(.white).padding(.horizontal, 18)
                GallerySection(text: L("gallery.format"))
                GallerySegments(names: [L("gallery.album.all"), "RAW", "JPEG", "HEIC"], selected: library.prefs.type.rawValue) {
                    library.prefs.type = GalleryPrefs.TypeFilter(rawValue: $0) ?? .all
                }
                GallerySection(text: L("gallery.status"))
                GallerySegments(names: [L("gallery.album.all"), "♥ " + L("gallery.album.favourites")], selected: library.prefs.favouritesOnly ? 1 : 0) {
                    library.prefs.favouritesOnly = $0 == 1
                }
                let years = library.years
                if !years.isEmpty {
                    GallerySection(text: L("gallery.time"))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(years, id: \.self) { y in
                                GalleryChip(text: String(y), on: library.prefs.years.contains(y)) {
                                    if library.prefs.years.contains(y) { library.prefs.years.remove(y) } else { library.prefs.years.insert(y) }
                                }
                            }
                        }
                        .padding(.horizontal, 18).padding(.vertical, 8)
                    }
                }
                HStack(spacing: 10) {
                    Button { library.prefs.clearFilter() } label: {
                        Text(L("gallery.filter_reset")).font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 48).background(GalleryStyle.card, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    Button { dismiss() } label: {
                        Text(L("gallery.done")).font(.system(size: 16, weight: .semibold)).foregroundStyle(GalleryStyle.ink)
                            .frame(maxWidth: .infinity, minHeight: 48).background(GalleryStyle.accent, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                .padding(18)
            }
            .padding(.top, 20)
        }
        .background(GalleryStyle.page.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }
}

// ---------------------------------------------------------------- the viewer

/// ImageViewerFragment + ViewerChrome: the photos paged, pinch / double-tap zoom, a tap hides the chrome; the top discs (back,
/// favourite, more), the bottom bar (share, edit, compare, delete, details).
@MainActor
struct PhotoViewer: View {
    let library: GalleryLibrary
    let live: Bool
    @State private var items: [PHAsset]
    @State private var index: Int
    @State private var chrome = true
    @State private var details: PhotoDetails?
    @State private var confirmDelete = false
    @State private var share: ShareFiles?
    @State private var editor: EditorStart?
    @State private var compare: GalleryView.CompareStart?
    @State private var toast: String?
    @State private var video: VideoStart?
    @Environment(\.dismiss) private var dismiss

    struct EditorStart: Identifiable { let id = UUID(); let asset: PHAsset; let raw: Bool }
    /// The player on the list's videos (previous / next), at the shown one
    struct VideoStart: Identifiable { let id = UUID(); let items: [PHAsset]; let index: Int }

    init(library: GalleryLibrary, start: GalleryView.ViewerStart) {
        self.library = library
        self.live = start.live
        _items = State(initialValue: start.assets)
        _index = State(initialValue: start.index)
    }

    private var asset: PHAsset? { items.indices.contains(index) ? items[index] : nil }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.element.localIdentifier) { i, a in
                    ViewerPage(library: library, asset: a, play: { playVideo(a) }) { withAnimation(.easeOut(duration: 0.2)) { chrome.toggle() } }
                        .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            if chrome { topBar; bottomBar }
            if let toast {
                VStack { Spacer(); GalleryPill(text: toast).padding(.bottom, 110) }
                    .transition(.opacity)
                    .task(id: toast) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        self.toast = nil
                    }
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(!chrome)
        .onChange(of: library.version) { _, _ in refresh() }
        .onChange(of: library.prefs) { _, _ in refresh() }
        .sheet(item: $details) { d in DetailsSheet(details: d).presentationDetents([.medium, .large]) }
        .sheet(item: $share) { s in ActivitySheet(items: s.urls) }
        .fullScreenCover(item: $editor) { e in PhotoEditorView(asset: e.asset, raw: e.raw) }
        .fullScreenCover(item: $compare) { c in CompareView(library: library, first: c.first, second: c.second) }
        .fullScreenCover(item: $video) { v in VideoPlayerView(library: library, items: v.items, index: v.index) }
        .confirmationDialog(L("gallery.delete_title"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("gallery.delete"), role: .destructive) { delete() }
            Button(L("gallery.cancel"), role: .cancel) {}
        } message: {
            Text(L("gallery.delete_message"))
        }
    }

    /// The library changed: the «Фото» tab's list again (live), else the photos that are still there; the shown one kept.
    private func refresh() {
        let current = asset?.localIdentifier
        if live {
            items = library.shown
        } else {
            let ids = items.map(\.localIdentifier)
            let still = Set(GalleryLibrary.list(PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)).map(\.localIdentifier))
            items = items.filter { still.contains($0.localIdentifier) }
        }
        if items.isEmpty {
            dismiss()
            return
        }
        if let current, let i = items.firstIndex(where: { $0.localIdentifier == current }) { index = i } else { index = Swift.min(index, items.count - 1) }
    }

    private var topBar: some View {
        VStack {
            HStack(spacing: 10) {
                RoundIconButton(icon: "ui_ic_back", label: L("gallery.back_grid"), background: GalleryStyle.bar) { dismiss() }
                if let a = asset, let d = a.creationDate {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(d.formatted(date: .abbreviated, time: .omitted)).font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                        Text(d.formatted(date: .omitted, time: .shortened)).font(.system(size: 12)).foregroundStyle(GalleryStyle.muted)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 4).background(GalleryStyle.bar, in: Capsule())
                }
                Spacer()
                if let a = asset {
                    RoundIconButton(icon: a.isFavorite ? "ui_ic_heart_on" : "ui_ic_heart",
                                    label: a.isFavorite ? L("gallery.unfavourite") : L("gallery.favourite"), background: GalleryStyle.bar) {
                        Task { await library.toggleFavourite(a) }
                    }
                    Menu {
                        if a.mediaType == .video {
                            Button(L("video.play")) { playVideo(a) }
                        } else {
                            Button(L("gallery.edit")) { editor = EditorStart(asset: a, raw: false) }
                        }
                        if a.mediaType != .video, GalleryLibrary.resources(a).raw != nil, GalleryLibrary.resources(a).photo != nil {
                            Button(L("gallery.edit_raw")) { editor = EditorStart(asset: a, raw: true) }
                        }
                        Button(L("gallery.share")) { shareCurrent() }
                    } label: {
                        GalleryIcon(name: "ui_ic_more", size: 22).frame(width: 40, height: 40).background(GalleryStyle.bar, in: Circle())
                    }
                    .accessibilityLabel(L("gallery.more"))
                }
            }
            .padding(.horizontal, 12).padding(.top, 6)
            Spacer()
        }
    }

    private var bottomBar: some View {
        VStack {
            Spacer()
            HStack(spacing: 0) {
                barButton("kb_ui_ic_share_rest", L("gallery.share")) { shareCurrent() }
                // ViewerChrome.setVideo: a video page has no «Изменить» (the editor takes photos)
                if asset?.mediaType != .video {
                    barButton("kb_ui_ic_edit_rest", L("gallery.edit")) {
                        if let a = asset { editor = EditorStart(asset: a, raw: GalleryLibrary.resources(a).photo == nil) }
                    }
                }
                barButton("kb_ui_ic_compare_rest", L("gallery.compare"), enabled: items.count > 1 && asset?.mediaType != .video) { compareWithNext() }
                barButton("kb_ui_ic_trash_rest", L("gallery.delete"), color: GalleryStyle.warn) { confirmDelete = true }
                barButton("kb_ui_ic_info_rest", L("gallery.details")) { showDetails() }
            }
            .padding(.vertical, 8).padding(.horizontal, 8)
            .background(GalleryStyle.bar, in: RoundedRectangle(cornerRadius: 24))
            .padding(.horizontal, 16).padding(.bottom, 10)
        }
    }

    private func barButton(_ icon: String, _ label: String, color: Color = .white, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                GalleryIcon(name: icon, size: 24, color: color)
                Text(label).font(.system(size: 11, weight: .medium)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.35)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    /// ImageViewerFragment.openVideo: the shown video in the gallery's player; the other videos of the list are its previous / next
    private func playVideo(_ a: PHAsset) {
        let videos = items.filter { $0.mediaType == .video }
        guard let i = videos.firstIndex(where: { $0.localIdentifier == a.localIdentifier }) else { return }
        video = VideoStart(items: videos, index: i)
    }

    /// ViewerChrome «Сравнить»: this photo with the next one (the previous one for the last).
    private func compareWithNext() {
        guard let a = asset, items.count > 1 else { return }
        let other = index + 1 < items.count ? items[index + 1] : items[index - 1]
        compare = GalleryView.CompareStart(first: a, second: other)
    }

    private func delete() {
        guard let a = asset else { return }
        Task {
            let ok = await library.delete([a])
            toast = ok ? L("gallery.deleted") : L("gallery.delete_failed")
        }
    }

    private func shareCurrent() {
        guard let a = asset else { return }
        Task {
            let urls = await GalleryLibrary.shareFiles([a])
            if !urls.isEmpty { share = ShareFiles(urls: urls) }
        }
    }

    private func showDetails() {
        guard let a = asset else { return }
        Task { details = await PhotoDetails.of(a) }
    }
}

/// One page: the photo at screen size, pinch and double tap to zoom (a UIScrollView), a tap toggles the chrome.
@MainActor
private struct ViewerPage: View {
    let library: GalleryLibrary
    let asset: PHAsset
    var play: () -> Void = {}
    let tap: () -> Void
    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { geo in
            ZoomImage(image: image, tap: tap)
                .frame(width: geo.size.width, height: geo.size.height)
                .overlay {
                    // P498: a video page is its poster frame with the play button
                    if asset.mediaType == .video {
                        Button(action: play) {
                            GalleryIcon(name: "ui_ic_play", size: 34)
                                .frame(width: 76, height: 76)
                                .background(GalleryStyle.bar, in: Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L("video.play_video"))
                    }
                }
                .task(id: asset.localIdentifier) {
                    image = await library.display(asset, side: max(geo.size.width, geo.size.height) * scale * 1.5)
                }
        }
    }
}

private struct ZoomImage: UIViewRepresentable {
    let image: UIImage?
    let tap: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(tap: tap) }

    func makeUIView(context: Context) -> UIScrollView {
        let s = UIScrollView()
        s.delegate = context.coordinator
        s.minimumZoomScale = 1
        s.maximumZoomScale = 8
        s.showsVerticalScrollIndicator = false
        s.showsHorizontalScrollIndicator = false
        s.backgroundColor = .black
        s.contentInsetAdjustmentBehavior = .never
        let v = UIImageView()
        v.contentMode = .scaleAspectFit
        v.isUserInteractionEnabled = true
        s.addSubview(v)
        context.coordinator.imageView = v
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        s.addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap))
        single.require(toFail: double)
        s.addGestureRecognizer(single)
        return s
    }

    func updateUIView(_ s: UIScrollView, context: Context) {
        context.coordinator.tap = tap
        guard let v = context.coordinator.imageView else { return }
        if v.image !== image {
            v.image = image
            s.zoomScale = 1
        }
        if v.frame.size != s.bounds.size, s.zoomScale == 1 { v.frame = s.bounds }
        DispatchQueue.main.async { if s.zoomScale == 1 { v.frame = s.bounds; s.contentSize = s.bounds.size } }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var tap: () -> Void
        weak var imageView: UIImageView?
        init(tap: @escaping () -> Void) { self.tap = tap }
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
        @objc func singleTap() { tap() }
        @objc func doubleTap(_ g: UITapGestureRecognizer) {
            guard let s = g.view as? UIScrollView else { return }
            if s.zoomScale > 1.01 {
                s.setZoomScale(1, animated: true)
            } else {
                let p = g.location(in: imageView)
                let k: CGFloat = 3
                let size = CGSize(width: s.bounds.width / k, height: s.bounds.height / k)
                s.zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: true)
            }
        }
    }
}

struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// ---------------------------------------------------------------- «Сведения»

/// GallerySheets.Details + PhotoMore: when (and the album), the lens and device, the size (Ultra HDR); «Как снято» (ISO, shutter,
/// aperture, focal length); «Подробнее» from the file's EXIF; the merge in words; the histogram.
struct PhotoDetails: Identifiable {
    let id = UUID()
    var fileName = ""
    var when: String?
    var folder: String?
    var lens: String?
    var device: String?
    var width = 0, height = 0
    var bytes: Int64 = 0
    var format: String?
    var ultraHdr = false
    var route: String?
    var iso: String?, shutter: String?, fnum: String?, focal: String?
    var more: [(String, String)] = []
    var histogram: [[Int]]?

    @MainActor static func of(_ asset: PHAsset) async -> PhotoDetails {
        var d = PhotoDetails()
        // P498: a video has no photo EXIF; its length in the shutter's place
        if asset.mediaType == .video {
            let r = GalleryLibrary.videoResource(asset)
            d.fileName = r?.originalFilename ?? ""
            d.width = asset.pixelWidth
            d.height = asset.pixelHeight
            if let date = asset.creationDate { d.when = dayTime(date, now: Date()) }
            d.folder = GalleryLibrary.albumTitles(of: asset).first
            if let r, let size = r.value(forKey: "fileSize") as? NSNumber { d.bytes = size.int64Value }
            d.format = (d.fileName as NSString).pathExtension.uppercased()
            d.shutter = VideoResume.clock(Int64(asset.duration * 1000))
            return d
        }
        let res = GalleryLibrary.resources(asset)
        let r = res.photo ?? res.raw
        d.fileName = r?.originalFilename ?? ""
        d.width = asset.pixelWidth
        d.height = asset.pixelHeight
        if let date = asset.creationDate { d.when = dayTime(date, now: Date()) }
        d.folder = GalleryLibrary.albumTitles(of: asset).first
        if let r, let size = r.value(forKey: "fileSize") as? NSNumber { d.bytes = size.int64Value }
        d.format = (d.fileName as NSString).pathExtension.uppercased() + (res.raw != nil && res.photo != nil ? " + RAW" : "")
        guard let r, let data = try? await GalleryLibrary.data(of: r),
              let src = CGImageSourceCreateWithData(data as CFData, nil),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return d }
        if d.bytes == 0 { d.bytes = Int64(data.count) }
        d.ultraHdr = data.range(of: Data("hdrgm:Version".utf8)) != nil
        let exif = p[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let tiff = p[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        let gps = p[kCGImagePropertyGPSDictionary] as? [CFString: Any] ?? [:]
        d.device = WatermarkExif.device(make: tiff[kCGImagePropertyTIFFMake] as? String, model: tiff[kCGImagePropertyTIFFModel] as? String)
        if let e = (exif[kCGImagePropertyExifFocalLenIn35mmFilm] as? NSNumber)?.intValue, e > 0 {
            d.lens = "\(e) " + L("gallery.mm") + " " + L("gallery.equiv")
        } else {
            d.lens = exif[kCGImagePropertyExifLensModel] as? String
        }
        if let iso = (exif[kCGImagePropertyExifISOSpeedRatings] as? [NSNumber])?.first { d.iso = "ISO \(iso.intValue)" }
        if let t = (exif[kCGImagePropertyExifExposureTime] as? NSNumber)?.doubleValue, t > 0 {
            d.shutter = t >= 1 ? String(format: "%.1f s", t) : "1/\(Int((1 / t).rounded())) s"
        }
        if let f = (exif[kCGImagePropertyExifFNumber] as? NSNumber)?.doubleValue { d.fnum = "f/" + decimal(f, 2) }
        if let f = (exif[kCGImagePropertyExifFocalLength] as? NSNumber)?.doubleValue { d.focal = decimal(f, 1) + " " + L("gallery.mm") }
        if let desc = tiff[kCGImagePropertyTIFFImageDescription] as? String, let m = desc.range(of: "Route=(SHDR|Hybrid|SCAM HDR)", options: .regularExpression) {
            let route = String(desc[m].dropFirst("Route=".count))
            d.route = route == "Hybrid" ? "SHDR" : route
        }
        d.more = more(exif: exif, tiff: tiff, gps: gps, properties: p)
        d.histogram = histogram(src)
        return d
    }

    /// GalleryUi.decimal: at most `digits` decimals, no trailing zeros, the interface's separator.
    static func decimal(_ v: Double, _ digits: Int) -> String {
        var s = String(format: "%.\(digits)f", v)
        while s.contains("."), s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s.replacingOccurrences(of: ".", with: L("gallery.decimal_separator"))
    }

    /// GalleryUi.dayTime: «Сегодня, 11:17», «Вчера, 09:05», «3 ноября, 18:40», «3 ноября 2025, 18:40».
    static func dayTime(_ date: Date, now: Date) -> String {
        let cal = Calendar.current
        var day: String
        if cal.isDate(date, inSameDayAs: now) {
            day = L("gallery.today")
        } else if let y = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(date, inSameDayAs: y) {
            day = L("gallery.yesterday")
        } else {
            let c = cal.dateComponents([.year, .month, .day], from: date)
            day = String(format: L("gallery.day_month"), c.day ?? 1, monthOf((c.month ?? 1) - 1))
            if c.year != cal.component(.year, from: now) { day = String(format: L("gallery.day_year"), day, c.year ?? 0) }
        }
        let c = cal.dateComponents([.hour, .minute], from: date)
        return day + ", " + String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    /// GalleryUi.monthName: the month in the genitive («октября») / "October".
    static func monthOf(_ m: Int) -> String {
        let names = [L("gallery.month_of_1"), L("gallery.month_of_2"), L("gallery.month_of_3"), L("gallery.month_of_4"), L("gallery.month_of_5"),
                     L("gallery.month_of_6"), L("gallery.month_of_7"), L("gallery.month_of_8"), L("gallery.month_of_9"), L("gallery.month_of_10"),
                     L("gallery.month_of_11"), L("gallery.month_of_12")]
        return names[Swift.max(0, Swift.min(11, m))]
    }

    /// PhotoMore.rows: what the file's EXIF holds beyond «Как снято», in plain words; a tag the file lacks gives no row.
    static func more(exif: [CFString: Any], tiff: [CFString: Any], gps: [CFString: Any], properties p: [CFString: Any]) -> [(String, String)] {
        var out: [(String, String)] = []
        func add(_ title: String, _ value: String?) {
            if let v = value?.trimmingCharacters(in: .whitespaces), !v.isEmpty { out.append((title, v)) }
        }
        if let v = exif[kCGImagePropertyExifDateTimeOriginal] as? String {
            let c = Array(v)
            add(L("gallery.taken"), c.count >= 19 && c[4] == ":" ? String(c[8..<10]) + "." + String(c[5..<7]) + "." + String(c[0..<4]) + String(c[10..<19]) : v)
        }
        if let ev = (exif[kCGImagePropertyExifExposureBiasValue] as? NSNumber)?.doubleValue {
            add(L("gallery.exposure_bias"), abs(ev) < 0.005 ? "0 EV"
                : String(format: "%+.1f EV", ev).replacingOccurrences(of: ".", with: L("gallery.decimal_separator")))
        }
        if let v = (exif[kCGImagePropertyExifWhiteBalance] as? NSNumber)?.intValue {
            add(L("gallery.white_balance"), v == 0 ? L("gallery.auto") : v == 1 ? L("gallery.manual") : nil)
        }
        if let v = (exif[kCGImagePropertyExifFlash] as? NSNumber)?.intValue {
            add(L("gallery.flash"), v & 1 == 1 ? L("gallery.flash_on") : L("gallery.flash_off"))
        }
        if let v = (exif[kCGImagePropertyExifMeteringMode] as? NSNumber)?.intValue {
            let modes = [L("gallery.metering_average"), L("gallery.metering_centre"), L("gallery.metering_spot"), L("gallery.metering_multi_spot"),
                         L("gallery.metering_matrix"), L("gallery.metering_partial")]
            add(L("gallery.metering"), (1...6).contains(v) ? modes[v - 1] : nil)
        }
        if let r = (exif[kCGImagePropertyExifDigitalZoomRatio] as? NSNumber)?.doubleValue, r > 1.001 {
            add(L("gallery.digital_zoom"), String(format: "%.1f×", r).replacingOccurrences(of: ".", with: L("gallery.decimal_separator")))
        }
        if let v = (exif[kCGImagePropertyExifColorSpace] as? NSNumber)?.intValue {
            add(L("gallery.colour_space"), v == 1 ? "sRGB" : v == 0xFFFF ? L("gallery.colour_space_wide") : nil)
        }
        if let o = (p[kCGImagePropertyOrientation] as? NSNumber)?.intValue {
            let degrees = [3: 180, 4: 180, 5: 90, 6: 90, 7: 270, 8: 270][o] ?? 0
            add(L("gallery.rotation"), degrees == 0 ? nil : "\(degrees)°")
        }
        if let lat = (gps[kCGImagePropertyGPSLatitude] as? NSNumber)?.doubleValue,
           let lon = (gps[kCGImagePropertyGPSLongitude] as? NSNumber)?.doubleValue {
            let la = (gps[kCGImagePropertyGPSLatitudeRef] as? String) == "S" ? -lat : lat
            let lo = (gps[kCGImagePropertyGPSLongitudeRef] as? String) == "W" ? -lon : lon
            add(L("gallery.location"), String(format: "%.5f, %.5f", la, lo))
        }
        add(L("gallery.software"), tiff[kCGImagePropertyTIFFSoftware] as? String)
        return out
    }

    /// The details' RGB histogram (R, G, B counts of a 256 px thumbnail).
    static func histogram(_ src: CGImageSource) -> [[Int]]? {
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 256,
                                        kCGImageSourceCreateThumbnailWithTransform: true]
        guard let image = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary),
              let decoded = PhotoDecode.draw(image, p3: false) else { return nil }
        var counts = [[Int]](repeating: [Int](repeating: 0, count: 256), count: 3)
        decoded.rgba.withUnsafeBytes { raw in
            let b = raw.bindMemory(to: UInt8.self)
            var i = 0
            while i + 3 < b.count {
                counts[0][Int(b[i])] += 1
                counts[1][Int(b[i + 1])] += 1
                counts[2][Int(b[i + 2])] += 1
                i += 4
            }
        }
        return counts
    }
}

@MainActor
private struct DetailsSheet: View {
    let details: PhotoDetails

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(L("gallery.details")).font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
                if !details.fileName.isEmpty { Text(details.fileName).font(.system(size: 14)).foregroundStyle(GalleryStyle.muted) }
                VStack(spacing: 0) {
                    row("ui_ic_clock", details.when ?? "—", details.folder.map { L("gallery.folder") + " " + $0 })
                    Divider().background(GalleryStyle.hairline)
                    row("kb_ui_ic_lens_rest", details.lens ?? L("gallery.no_lens"), details.device)
                    Divider().background(GalleryStyle.hairline)
                    row("kb_ui_ic_size_rest", sizeLine, whatLine)
                }
                .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
                GallerySection(text: L("gallery.how_shot")).padding(.horizontal, -18)
                VStack(spacing: 0) {
                    HStack(spacing: 0) { cell(L("gallery.iso"), details.iso); cell(L("gallery.shutter"), details.shutter) }
                    Divider().background(GalleryStyle.hairline)
                    HStack(spacing: 0) { cell(L("gallery.aperture"), details.fnum); cell(L("gallery.focal"), details.focal) }
                }
                .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
                if !details.more.isEmpty {
                    GallerySection(text: L("gallery.more_details")).padding(.horizontal, -18)
                    VStack(spacing: 0) {
                        ForEach(details.more.indices, id: \.self) { i in
                            HStack(alignment: .top) {
                                Text(details.more[i].0).font(.system(size: 14)).foregroundStyle(.white)
                                Spacer(minLength: 10)
                                Text(details.more[i].1).font(.system(size: 14)).foregroundStyle(GalleryStyle.muted)
                                    .multilineTextAlignment(.trailing).textSelection(.enabled)
                            }
                            .padding(.horizontal, 16).frame(minHeight: 44)
                            if i < details.more.count - 1 { Divider().background(GalleryStyle.hairline) }
                        }
                    }
                    .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 16))
                }
                if let route = details.route {
                    Text(String(format: L("gallery.route_note"), route)).font(.system(size: 13)).foregroundStyle(GalleryStyle.muted)
                        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                        .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 16))
                        .padding(.top, 12)
                }
                if let h = details.histogram {
                    GallerySection(text: L("gallery.histogram")).padding(.horizontal, -18)
                    HistogramView(counts: h).frame(height: 100).padding(12)
                        .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
                        .accessibilityLabel(L("gallery.histogram_rgb"))
                }
            }
            .padding(20)
        }
        .background(GalleryStyle.page.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }

    private var sizeLine: String {
        let size = ByteCountFormatter.string(fromByteCount: details.bytes, countStyle: .file)
        guard details.width > 0, details.height > 0 else { return size }
        let mp = max(1, Int((Double(details.width) * Double(details.height) / 1e6).rounded()))
        return "\(mp) " + L("gallery.mp") + " · " + size
    }

    private var whatLine: String {
        var s = details.width > 0 && details.height > 0 ? "\(details.width) × \(details.height)" : ""
        if let f = details.format, !f.isEmpty { s += s.isEmpty ? f : " · " + f }
        if details.ultraHdr { s += " · Ultra HDR" }
        return s
    }

    private func row(_ icon: String, _ title: String, _ sub: String?) -> some View {
        HStack(spacing: 14) {
            GalleryIcon(name: icon, size: 20, color: .white)
                .frame(width: 32, height: 32)
                .background(Color(red: 0x3A / 255, green: 0x3A / 255, blue: 0x3C / 255), in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                if let sub, !sub.isEmpty { Text(sub).font(.system(size: 13)).foregroundStyle(GalleryStyle.muted) }
            }
            Spacer()
        }
        .padding(.horizontal, 16).frame(minHeight: 64)
    }

    private func cell(_ title: String, _ value: String?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.system(size: 12.5)).foregroundStyle(GalleryStyle.muted).lineLimit(1).minimumScaleFactor(0.7)
            Text(value ?? "—").font(.system(size: 16, weight: .semibold).monospacedDigit()).foregroundStyle(.white).lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 11)
    }
}
