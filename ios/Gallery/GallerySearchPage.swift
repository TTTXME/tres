import Photos
import SwiftUI

/// SearchPage (P502 / P579): the field (the glass, clear) in the settings' search capsule; while it is empty the recent queries (a
/// tap repeats one, the bin clears them), quick searches (today, this month, raw, heic, favourites, screenshots, the library's years)
/// and the albums; with text, the matching photos in a 4-column grid that opens the viewer on them («Найдено: 12 снимков»).
/// Matching: GallerySearch over every photo of the library (names, kinds, dates; ru and en). Android's text read on the photos (OCR)
/// and its «видео» are not ported.
@MainActor
struct GallerySearchPage: View {
    let library: GalleryLibrary
    let back: () -> Void
    let open: ([PHAsset], Int) -> Void
    let openAlbum: (GalleryLibrary.Album) -> Void
    @State private var query = ""
    @State private var recent = GalleryPrefs.recentSearches
    @State private var all: [PHAsset] = []
    @State private var albums: [GalleryLibrary.Album] = []
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                RoundIconButton(icon: "ui_ic_back", label: L("gallery.back")) { back() }
                Text(L("gallery.search")).font(.app(28, 700)).foregroundStyle(.white)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 8)
            field
            if query.trimmingCharacters(in: .whitespaces).isEmpty { suggestions } else { results }
        }
        .task(id: library.version) {
            all = GalleryLibrary.list(PHAsset.fetchAssets(with: library.photoOptions()))
            let a = library.albums()
            albums = a.frequent + a.others
        }
    }

    private var field: some View {
        HStack(spacing: 8) {
            GalleryIcon(name: "kb_ui_ic_search_rest", size: 20, color: GalleryStyle.section).accessibilityHidden(true)
            TextField("", text: $query, prompt: Text(L("gallery.search_hint")).foregroundStyle(GalleryStyle.section))
                .font(.app(16)).foregroundStyle(.white)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($focused)
                .onSubmit { remember(query) }
            if !query.isEmpty {
                Button { query = "" } label: { GalleryIcon(name: "ui_ic_close", size: 20, color: GalleryStyle.section).frame(width: 40, height: 40) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("gallery.clear"))
            }
        }
        .padding(.leading, 12).padding(.trailing, 4).frame(minHeight: 44)
        .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 22))
        .padding(.horizontal, 16).padding(.bottom, 8)
    }

    // ---------------------------------------------------------------- suggestions

    private var suggestions: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !recent.isEmpty {
                    HStack {
                        heading(L("gallery.search_recent"))
                        Spacer()
                        Button {
                            recent = []
                            GalleryPrefs.recentSearches = []
                        } label: { GalleryIcon(name: "kb_ui_ic_trash_rest", size: 18, color: GalleryStyle.section).frame(width: 38, height: 38) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(L("gallery.search_clear_history"))
                    }
                    chips(recent)
                }
                heading(L("gallery.search_quick"))
                chips(quick)
                if !albums.isEmpty {
                    heading(L("gallery.albums"))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(albums) { a in
                                Button { openAlbum(a) } label: {
                                    HStack(spacing: 10) {
                                        GalleryIcon(name: a.icon, size: 20, color: GalleryStyle.accent)
                                        Text(a.title).font(.app(15)).foregroundStyle(.white).lineLimit(1)
                                        Text("\(a.count)").font(.app(14)).foregroundStyle(GalleryStyle.muted)
                                    }
                                    .padding(.horizontal, 14).frame(minHeight: 48)
                                    .background(GalleryStyle.card, in: RoundedRectangle(cornerRadius: 18))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 120)
        }
        .scrollDismissesKeyboard(.immediately)
    }

    /// The quick searches: today, this month, raw, heic, favourites, screenshots, then the library's years (newest first).
    private var quick: [String] {
        let cal = Calendar.current, now = Date()
        let months = [L("gallery.search_month_1"), L("gallery.search_month_2"), L("gallery.search_month_3"), L("gallery.search_month_4"),
                      L("gallery.search_month_5"), L("gallery.search_month_6"), L("gallery.search_month_7"), L("gallery.search_month_8"),
                      L("gallery.search_month_9"), L("gallery.search_month_10"), L("gallery.search_month_11"), L("gallery.search_month_12")]
        var out = [L("gallery.search_today"), months[cal.component(.month, from: now) - 1] + " \(cal.component(.year, from: now))", "raw", "heic",
                   L("gallery.search_favourite"), L("gallery.search_screenshot")]
        let years = Set(all.compactMap { $0.creationDate.map { cal.component(.year, from: $0) } }).sorted(by: >)
        out += years.map(String.init)
        return out
    }

    private func heading(_ title: String) -> some View {
        Text(title.uppercased()).font(.app(12, 600)).foregroundStyle(GalleryStyle.section).kerning(0.7)
            .padding(.leading, 4).padding(.top, 16).padding(.bottom, 6)
            .accessibilityAddTraits(.isHeader)
    }

    /// Wrapping chips: a tap puts the text into the field.
    private func chips(_ words: [String]) -> some View {
        FlowLayout(spacing: 8) {
            ForEach(words.indices, id: \.self) { i in
                Button {
                    query = words[i]
                    remember(words[i])
                } label: {
                    Text(words[i]).font(.app(15)).foregroundStyle(.white)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(GalleryStyle.card, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func remember(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return }
        var list = [t]
        for r in recent where r != t { list.append(r) }
        recent = Array(list.prefix(12))
        GalleryPrefs.recentSearches = recent
    }

    // ---------------------------------------------------------------- results

    private var results: some View {
        let found = GallerySearch.find(all, query, kinds: library.kinds, now: Date())
        let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 4)
        return VStack(alignment: .leading, spacing: 0) {
            Text(found.isEmpty ? L("gallery.search_nothing") : L("gallery.search_found") + " " + GalleryView.countText(found.count))
                .font(.app(13)).foregroundStyle(GalleryStyle.muted).padding(.horizontal, 18).padding(.bottom, 8)
            ScrollView {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(found.indices, id: \.self) { i in
                        GalleryThumb(library: library, asset: found[i])
                            .onTapGesture {
                                remember(query)
                                focused = false
                                open(found, i)
                            }
                    }
                }
                .padding(.horizontal, 8).padding(.bottom, 120)
            }
            .scrollDismissesKeyboard(.immediately)
        }
    }
}

/// SearchPage.FlowLayout: a row that wraps its children onto new lines.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += line + spacing
                line = 0
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            line = max(line, size.height)
        }
        return CGSize(width: proposal.width ?? widest, height: y + line)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > bounds.width {
                x = 0
                y += line + spacing
                line = 0
            }
            s.place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            line = max(line, size.height)
        }
    }
}
