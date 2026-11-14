import SwiftUI

/// SettingsSearchFragment (ui/settings/SettingsSearchFragment.java): search keeps navigation separate from editing - a hit opens
/// the real page with its row highlighted. A row is found by every word of the query (case and «ё» ignored) among its title, the
/// path of pages and groups to it, its summary, its key, its words in the other interface language and its help text (which names
/// a moved row's former title and place). The developer page's scam_dev.txt lines are found by their key and meaning.
enum SettingsSearch {
    struct Entry: Identifiable {
        let key: String
        /// The page that shows the row (a page's own key for a page)
        let page: String
        let title: String
        let path: String
        let summary: String
        let icon: String?
        /// The other language's words and the help text
        let other: String
        var id: String { key }
    }

    static func normalize(_ s: String) -> String { s.lowercased().replacingOccurrences(of: "ё", with: "е") }

    static func matches(_ e: Entry, _ query: String) -> Bool {
        let haystack = normalize(e.title + " " + e.path + " " + e.summary + " " + e.key + " " + e.other)
        return normalize(query).split(whereSeparator: \.isWhitespace).allSatisfy { haystack.contains($0) }
    }

    /// The rows of the settings as shown now (rows hidden on this phone or by the page's code are not found), and the paths of the
    /// pages among them
    static func index() -> (entries: [Entry], pagePaths: Set<String>) {
        let catalog = SettingsCatalog.shared
        guard let root = catalog.tree else { return ([], []) }
        let availability = SettingsAvailability(SettingsStore.shared.values)
        var out: [Entry] = [], pagePaths = Set<String>()
        var developerPath: String?
        func collect(_ group: SettingsNode, page: String, path: String, otherPath: String) {
            for p in group.children ?? [] {
                if let key = p.key, availability.hidden(key) || SettingsExtras.codeHidden.contains(key) { continue }
                let title = catalog.text(p.title) ?? "", otherTitle = catalog.otherText(p.title) ?? ""
                if let key = p.key, !title.isEmpty {
                    let help = [catalog.helpText(key), catalog.helpText(key, otherLanguage: true)].compactMap { $0 }.joined(separator: " ")
                    out.append(Entry(key: key, page: p.type == "screen" ? key : page, title: title, path: path,
                                     summary: catalog.text(p.summary) ?? "", icon: p.icon,
                                     other: otherTitle + " " + otherPath + " " + (catalog.otherText(p.summary) ?? "") + " " + help))
                    if p.type == "screen" { pagePaths.insert(path.isEmpty ? title : path + " › " + title) }
                    if key == DeveloperPage.screen { developerPath = path.isEmpty ? title : path + " › " + title }
                }
                if p.children != nil {
                    collect(p, page: p.type == "screen" ? (p.key ?? page) : page, path: path.isEmpty ? title : path + " › " + title,
                            otherPath: otherPath + " " + otherTitle)
                }
            }
        }
        collect(root, page: root.key ?? "prefscreen", path: "", otherPath: "")
        // the developer page's rows (DeveloperPreferences.populate builds them before the index on Android)
        if let developerPath {
            for k in catalog.devKeys {
                out.append(Entry(key: DeveloperPage.rowID(k.k), page: DeveloperPage.screen, title: k.k, path: developerPath + " › " + k.group,
                                 summary: k.text, icon: nil, other: k.otherText))
            }
        }
        return (out, pagePaths)
    }

    /// P584: the path under a hit - the pages to it (its groups left out), «Настройки» for a row of the main page
    static func pages(_ e: Entry, pagePaths: Set<String>) -> String {
        if e.path.isEmpty { return L("set.search.root") }
        var out: [String] = [], at = ""
        for part in e.path.components(separatedBy: " › ") {
            at = at.isEmpty ? part : at + " › " + part
            if pagePaths.contains(at) { out.append(part) }
        }
        return out.isEmpty ? L("set.search.root") : out.joined(separator: " › ")
    }
}

/// The hits of the root page's search: a card of rows - the row's icon tile, its title, the pages to it, the chevron; a tap opens
/// the page with the row highlighted
struct SettingsSearchResults: View {
    let query: String

    var body: some View {
        let index = SettingsSearch.index()
        let results = index.entries.filter { SettingsSearch.matches($0, query) }
        Section {
            ForEach(results) { e in
                NavigationLink {
                    if let node = PageReset.find(e.page) { SettingsPages.destination(node, highlight: e.key) }
                } label: {
                    HStack(spacing: 12) {
                        if let icon = e.icon {
                            Image(icon).renderingMode(.template).resizable().scaledToFit().frame(width: 19, height: 19)
                                .foregroundStyle(AccentPalette.settingsColor)
                                .frame(width: 32, height: 32)
                                .background(Color(white: 0.23), in: RoundedRectangle(cornerRadius: 9))
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.title).font(.app(16, 600))
                            Text(SettingsSearch.pages(e, pagePaths: index.pagePaths)).font(.app(13)).foregroundStyle(.secondary)
                                .lineLimit(3)
                        }
                    }
                }
            }
        } header: {
            Text(query.trimmingCharacters(in: .whitespaces).isEmpty ? L("set.search.hint")
                 : results.isEmpty ? L("set.search.none") : String(format: L("set.search.found"), results.count))
        }
    }
}
