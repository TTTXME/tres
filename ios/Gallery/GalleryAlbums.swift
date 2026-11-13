import Photos
import SwiftUI
import UIKit

/// AlbumsPage (P502 / P579): the albums in the settings' look - sections with a 20pt title, a collapse chevron and «Ещё», each
/// remembered folded or open: «Часто используемые» (Все, Камера, Избранное, RAW, Скриншоты - those that have photos) as big cards
/// with 16pt corners, «Другие альбомы» (the library's other albums, nine until «Ещё»), «Тип» (RAW, HEIC, JPEG with their counts),
/// «Другое» (the folder the «Фото» tab shows). A tap opens the album's grid. Android's «Видео» / «Записи экрана» (no video player
/// here) and «Недавно удаленные» (PhotoKit does not expose the library's trash) are left out.
@MainActor
struct AlbumsPage: View {
    let library: GalleryLibrary
    let back: () -> Void
    let open: (GalleryLibrary.Album) -> Void
    @State private var lists: (frequent: [GalleryLibrary.Album], others: [GalleryLibrary.Album], types: [GalleryLibrary.Album]) = ([], [], [])
    @State private var collapsed = GalleryPrefs.collapsed
    @State private var expanded: Set<String> = []
    @State private var folders = false

    static let firstFrequent = 4, firstOthers = 9

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                RoundIconButton(icon: "ui_ic_back", label: L("gallery.back")) { back() }
                Text(L("gallery.albums")).font(.system(size: 28, weight: .bold)).foregroundStyle(.white)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 4)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if !lists.frequent.isEmpty {
                        section("frequent", L("gallery.albums.frequent"), more: lists.frequent.count > Self.firstFrequent) {
                            let shown = expanded.contains("frequent") ? lists.frequent : Array(lists.frequent.prefix(Self.firstFrequent))
                            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                                ForEach(shown) { a in card(a) }
                            }
                        }
                    }
                    if !lists.others.isEmpty {
                        section("others", L("gallery.albums.others"), more: lists.others.count > Self.firstOthers) {
                            let shown = expanded.contains("others") ? lists.others : Array(lists.others.prefix(Self.firstOthers))
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 12) {
                                ForEach(shown) { a in card(a, small: true) }
                            }
                        }
                    }
                    if !lists.types.isEmpty {
                        section("types", L("gallery.albums.types"), more: false) {
                            rows(lists.types.map { a -> (String, String, String, () -> Void) in (a.icon, a.title, "\(a.count)", { open(a) }) })
                        }
                    }
                    section("other", L("gallery.albums.other"), more: false) {
                        rows([("kb_ui_ic_folders_rest", L("gallery.albums.photo_folders"),
                               library.source == .camera ? L("gallery.camera") : L("gallery.all"), { folders = true })])
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 120)
            }
        }
        .task(id: "\(library.version)|\(library.kinds.count / 2000)") { lists = library.albums() }
        .confirmationDialog(L("gallery.albums.photo_folders"), isPresented: $folders, titleVisibility: .visible) {
            Button(L("gallery.camera")) { library.source = .camera }
            Button(L("gallery.all")) { library.source = .all }
            Button(L("gallery.cancel"), role: .cancel) {}
        } message: {
            Text(L("gallery.albums.photo_folders_sub"))
        }
    }

    /// A section: the title, «Ещё ›» / «Свернуть» when it has more, the chevron folding it (remembered).
    private func section<Content: View>(_ key: String, _ title: String, more: Bool, @ViewBuilder content: () -> Content) -> some View {
        let folded = collapsed.contains(key)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) {
                        if folded { collapsed.remove(key) } else { collapsed.insert(key) }
                        GalleryPrefs.collapsed = collapsed
                    }
                } label: {
                    HStack(spacing: 6) {
                        Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
                        GalleryIcon(name: "cs_chevron", size: 14, color: GalleryStyle.section).rotationEffect(.degrees(folded ? 0 : 90))
                    }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(.isHeader)
                Spacer()
                if more && !folded {
                    Button(expanded.contains(key) ? L("gallery.less") : L("gallery.more_arrow")) {
                        withAnimation(.easeOut(duration: 0.2)) {
                            if expanded.contains(key) { expanded.remove(key) } else { expanded.insert(key) }
                        }
                    }
                    .font(.system(size: 15, weight: .semibold)).foregroundStyle(GalleryStyle.accent)
                }
            }
            .padding(.top, 18)
            if !folded { content() }
        }
    }

    /// AlbumViews: a cover with 16pt corners, the title and the count under it.
    private func card(_ a: GalleryLibrary.Album, small: Bool = false) -> some View {
        Button { open(a) } label: {
            VStack(alignment: .leading, spacing: 4) {
                AlbumCover(library: library, asset: a.assets.first, icon: a.icon)
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                Text(a.title).font(.system(size: small ? 13 : 15, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                Text("\(a.count)").font(.system(size: 12)).foregroundStyle(GalleryStyle.muted)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(a.title + ", " + GalleryView.countText(a.count))
    }

    /// «Тип» / «Другое»: rows of one #2C2C2E card (24pt corners, hairlines, a chevron).
    private func rows(_ list: [(String, String, String, () -> Void)]) -> some View {
        VStack(spacing: 0) {
            ForEach(list.indices, id: \.self) { i in
                Button(action: list[i].3) {
                    HStack(spacing: 14) {
                        GalleryIcon(name: list[i].0, size: 22, color: GalleryStyle.accent)
                        Text(list[i].1).font(.system(size: 16)).foregroundStyle(.white)
                        Spacer()
                        Text(list[i].2).font(.system(size: 15)).foregroundStyle(GalleryStyle.muted)
                        GalleryIcon(name: "cs_chevron", size: 14, color: GalleryStyle.section)
                    }
                    .padding(.horizontal, 16).frame(minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if i < list.count - 1 { Rectangle().fill(GalleryStyle.hairline).frame(height: 0.5).padding(.leading, 52) }
            }
        }
        .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 24))
    }
}

/// An album's cover: its newest photo, the album's icon on an empty one.
@MainActor
struct AlbumCover: View {
    let library: GalleryLibrary
    let asset: PHAsset?
    let icon: String
    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    var body: some View {
        GalleryStyle.card
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill() } else { GalleryIcon(name: icon, size: 28, color: GalleryStyle.muted) }
            }
            .clipped()
            .task(id: asset?.localIdentifier ?? "") {
                if let asset { image = await library.thumbnail(asset, side: 360 * scale / 2) }
            }
    }
}

/// An album opened from AlbumsPage or the search: its grid (with the selection) and the viewer over its photos.
@MainActor
struct AlbumGridPage: View {
    let library: GalleryLibrary
    let album: GalleryLibrary.Album
    @State private var assets: [PHAsset] = []
    @State private var selection: Set<String>?
    @State private var viewer: GalleryView.ViewerStart?
    @State private var compare: GalleryView.CompareStart?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            GalleryStyle.page.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    RoundIconButton(icon: "ui_ic_back", label: L("gallery.back")) { dismiss() }
                    VStack(alignment: .leading, spacing: 1) {
                        Text(album.title).font(.system(size: 24, weight: .bold)).foregroundStyle(.white).lineLimit(1)
                        Text(GalleryView.countText(assets.count)).font(.system(size: 13)).foregroundStyle(GalleryStyle.muted)
                    }
                    Spacer()
                    RoundIconButton(icon: "kb_ui_ic_select_all_rest", label: L("gallery.select")) { selection = [] }
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
                if assets.isEmpty {
                    Spacer()
                    Text(L("gallery.empty")).font(.system(size: 15)).foregroundStyle(GalleryStyle.muted)
                    Spacer()
                } else {
                    SelectableGrid(library: library, assets: assets, selection: $selection,
                                   open: { i in viewer = GalleryView.ViewerStart(assets: assets, index: i) },
                                   compare: { a, b in compare = GalleryView.CompareStart(first: a, second: b) })
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { assets = album.assets }
        .onChange(of: library.version) { _, _ in
            // deleted elsewhere: the photos still there, in order
            let ids = assets.map(\.localIdentifier)
            let still = Set(GalleryLibrary.list(PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)).map(\.localIdentifier))
            assets = assets.filter { still.contains($0.localIdentifier) }
        }
        .fullScreenCover(item: $viewer) { start in PhotoViewer(library: library, start: start) }
        .fullScreenCover(item: $compare) { c in CompareView(library: library, first: c.first, second: c.second) }
    }
}
