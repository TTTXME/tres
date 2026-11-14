import SwiftUI

/// SettingsLayout (settings/SettingsLayout.java, P385): the user's order of the settings - on every page the order of its groups and
/// rows, in each group the order of its rows, and rows moved from one group of a page to another. One main setting
/// (settings_layout, carried by the config file), one line per container that differs from the app's own order:
/// container=item,item,...;container=... A container is a page or a group of it. The stored order is applied to the page as built:
/// an item it does not know keeps its place after the item it follows in the app's order, an item no longer there is dropped; a row
/// goes to another container only between two groups of the same page that share rows (`open`).
enum SettingsLayout {
    static let key = "settings_layout"
    /// The row that opens the main page's layout
    static let editorKey = "settings_layout_editor"
    /// SettingsLayoutTree.BUILT: groups built by code (moved as a whole, their rows untouched)
    static let built: Set<String> = ["root_modules"]
    /// ModulePageFilter.NOT_MODULE: groups whose rows stay in them
    static let notModule: Set<String> = ["root_logs", "root_lab", "root_about", "root_modules", "root_lenses"]

    /// One page as the app builds it: its containers (the page first) with their items in the app's order
    struct Page {
        let key: String
        var containers: [(String, [String])] = []
        var open: Set<String> = []

        func items(_ container: String) -> [String]? { containers.first { $0.0 == container }?.1 }
        func home(_ item: String) -> String? { containers.first { $0.1.contains(item) }?.0 }
        func moves(_ from: String, _ to: String) -> Bool { from == to || open.contains(from) && open.contains(to) }
    }

    static func storable(_ id: String?) -> Bool {
        guard let id, !id.isEmpty else { return false }
        return !id.contains(";") && !id.contains("=") && !id.contains(",")
    }

    /// The stored text as containers and their items (in the text's order); malformed lines are skipped
    static func parse(_ stored: String?) -> [(String, [String])] {
        var out: [(String, [String])] = []
        for line in (stored ?? "").split(separator: ";", omittingEmptySubsequences: true).map(String.init) {
            guard let eq = line.firstIndex(of: "="), eq != line.startIndex else { continue }
            let container = line[..<eq].trimmingCharacters(in: .whitespaces)
            var items: [String] = []
            for item in line[line.index(after: eq)...].split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) })
                where !item.isEmpty && !items.contains(item) { items.append(item) }
            if !container.isEmpty && !out.contains(where: { $0.0 == container }) { out.append((container, items)) }
        }
        return out
    }

    static func text(_ layout: [(String, [String])]) -> String {
        layout.filter { storable($0.0) }.map { $0.0 + "=" + $0.1.filter { storable($0) }.joined(separator: ",") }.joined(separator: ";")
    }

    /// `page` in the `stored` order (every container of the page, in the page's order)
    static func apply(_ page: Page, _ stored: [(String, [String])]) -> [(String, [String])] {
        var home: [String: String] = [:]
        for (c, items) in page.containers { for i in items { home[i] = c } }
        var placed = Set<String>()
        var out: [(String, [String])] = []
        for (container, _) in page.containers {
            var list: [String] = []
            for item in stored.first(where: { $0.0 == container })?.1 ?? [] {
                guard let from = home[item], !placed.contains(item), page.moves(from, container) else { continue }
                list.append(item)
                placed.insert(item)
            }
            out.append((container, list))
        }
        // the items the stored order does not place: after the nearest item before them in the app's order still in their own
        // container, else at its start (in their own order)
        for (ci, (_, own)) in page.containers.enumerated() {
            var list = out[ci].1
            var head = 0
            for (i, item) in own.enumerated() where !placed.contains(item) {
                var at = -1
                var j = i - 1
                while j >= 0 && at < 0 {
                    if let k = list.firstIndex(of: own[j]) { at = k + 1 }
                    j -= 1
                }
                if at < 0 {
                    at = head
                    head += 1
                }
                list.insert(item, at: Swift.min(at, list.count))
                placed.insert(item)
            }
            out[ci].1 = list
        }
        return out
    }

    /// The stored text with `page`'s containers as `edited` (those equal to the app's order left out); other pages' lines kept
    static func store(_ stored: String?, _ page: Page, _ edited: [(String, [String])]) -> String {
        var all = parse(stored).filter { line in !page.containers.contains { $0.0 == line.0 } }
        let own = apply(page, [])
        for (container, _) in page.containers {
            guard let items = edited.first(where: { $0.0 == container })?.1 else { continue }
            if items != own.first(where: { $0.0 == container })?.1 { all.append((container, items)) }
        }
        return text(all)
    }

    static func reset(_ stored: String?, _ page: Page) -> String { store(stored, page, []) }

    static func isDefault(_ page: Page, _ stored: String?) -> Bool {
        let a = apply(page, parse(stored)), b = apply(page, [])
        return a.map { $0.1 } == b.map { $0.1 }
    }

    /// P629: the lines of `pages` joined into one line of `into` (unchanged when none has a line, or `into` has one)
    static func joinPages(_ stored: String?, into: String, pages: [String]) -> String {
        var all = parse(stored)
        if all.contains(where: { $0.0 == into }) { return stored ?? "" }
        var items: [String] = []
        var any = false
        for p in pages {
            guard let i = all.firstIndex(where: { $0.0 == p }) else { continue }
            any = true
            for item in all[i].1 where !items.contains(item) { items.append(item) }
            all.remove(at: i)
        }
        if !any { return stored ?? "" }
        all.append((into, items))
        return text(all)
    }

    /// P629b: a line of `into` made of the items of `from`'s line that are among `moved` (unchanged when `into` has a line already)
    static func carry(_ stored: String?, into: String, from: String, moved: [String]) -> String {
        var all = parse(stored)
        guard !all.contains(where: { $0.0 == into }), let own = all.first(where: { $0.0 == from })?.1 else { return stored ?? "" }
        let items = own.filter { moved.contains($0) }
        if items.isEmpty { return stored ?? "" }
        all.append((into, items))
        return text(all)
    }

    // ───── the page as built (SettingsLayoutTree) ─────

    /// The id of an item: its key, or (no usable key) its place in the app's order
    static func id(_ n: SettingsNode, container: String, index: Int) -> String {
        storable(n.key) ? n.key! : "#" + container + "." + String(index)
    }

    /// SettingsLayoutTree.snapshot: the page's model and its items by id (nil: no key to store it under)
    static func snapshot(_ page: SettingsNode) -> (model: Page, items: [String: SettingsNode])? {
        guard let pageKey = page.key, storable(pageKey) else { return nil }
        var model = Page(key: pageKey)
        var items: [String: SettingsNode] = [:]
        var top: [String] = []
        var groups: [(String, [String])] = []
        for (i, p) in (page.children ?? []).enumerated() {
            let id = Self.id(p, container: pageKey, index: i)
            top.append(id)
            items[id] = p
            guard p.type == "category", storable(p.key), !built.contains(id) else { continue }
            var rows: [String] = []
            for (j, r) in (p.children ?? []).enumerated() {
                let rid = Self.id(r, container: id, index: j)
                rows.append(rid)
                items[rid] = r
            }
            groups.append((id, rows))
            if !notModule.contains(id) { model.open.insert(id) }
        }
        model.containers = [(pageKey, top)] + groups
        return (model, items)
    }

    /// The page in the stored order; a group the order took every row from is left out
    static func arranged(_ page: SettingsNode, stored: String) -> SettingsNode {
        guard !stored.isEmpty, let snap = snapshot(page) else { return page }
        let layout = apply(snap.model, parse(stored))
        var out = page
        var top: [SettingsNode] = []
        for id in layout.first?.1 ?? [] {
            guard var n = snap.items[id] else { continue }
            if let rows = layout.first(where: { $0.0 == id })?.1 {
                let own = snap.model.items(id) ?? []
                if rows.isEmpty && !own.isEmpty { continue }   // lost every row to another group
                n.children = rows.compactMap { snap.items[$0] }
            }
            top.append(n)
        }
        out.children = top
        return out
    }

    /// SettingsLayoutFragment.editable: a page with something to arrange
    static func editable(_ page: SettingsNode) -> Bool {
        guard let snap = snapshot(page) else { return false }
        return snap.model.containers.contains { $0.1.count > 1 }
    }
}

/// SettingsLayoutFragment («Расположение», P385): a settings page's groups and rows in the user's order. «Группы» - the page's groups
/// and loose rows, each a card; «Пункты» - the rows under their group's title (a row also goes to another group of the page). Every
/// move is saved at once (settings_layout: carried by the config file); the reset puts this page back in the app's order.
struct SettingsLayoutPage: View {
    let page: SettingsNode
    @State private var mode = 0
    @State private var asking = false
    @State private var refused = false
    private var store: SettingsStore { SettingsStore.shared }
    private var catalog: SettingsCatalog { SettingsCatalog.shared }

    private struct Item: Identifiable {
        let id: String
        let header: Bool
    }

    private var snap: (model: SettingsLayout.Page, items: [String: SettingsNode])? { SettingsLayout.snapshot(page) }

    private var layout: [(String, [String])] {
        guard let snap else { return [] }
        return SettingsLayout.apply(snap.model, SettingsLayout.parse(store.string(SettingsLayout.key, "")))
    }

    /// SettingsLayoutTree.hidden: rows hidden on this phone, rows without a title (the notes stay after the row they follow)
    private func hidden(_ id: String) -> Bool {
        guard let n = snap?.items[id] else { return true }
        if let key = n.key, SettingsAvailability(store.values).hidden(key) || SettingsExtras.codeHidden.contains(key) { return true }
        return (catalog.text(n.title) ?? "").isEmpty
    }

    private func title(_ id: String) -> String {
        guard let n = snap?.items[id] else { return id }
        return catalog.text(n.title) ?? id
    }

    private func subtitle(_ id: String) -> String {
        guard let model = snap?.model, model.items(id) != nil || SettingsLayout.built.contains(id) else { return "" }
        if SettingsLayout.built.contains(id) { return L("set.layout.built") }
        let rows = (layout.first { $0.0 == id }?.1 ?? []).filter { !hidden($0) }
        if rows.isEmpty { return L("set.layout.empty") }
        let names = rows.prefix(3).map(title)
        return names.joined(separator: ", ") + (rows.count > 3 ? String(format: L("set.layout.more"), rows.count - 3) : "")
    }

    private var pageTitle: String {
        page.key == "prefscreen" || (catalog.text(page.title) ?? "").isEmpty ? L("set.layout.main_page")
            : String(format: L("set.layout.page"), catalog.text(page.title) ?? "")
    }

    /// «Пункты»: the page's loose rows under the page's title, then each group's title and rows
    private var rowItems: [Item] {
        guard let snap else { return [] }
        var out: [Item] = []
        for (container, items) in layout {
            out.append(Item(id: container, header: true))
            let rows = container == snap.model.key ? items.filter { snap.model.items($0) == nil && !SettingsLayout.built.contains($0) } : items
            out += rows.filter { !hidden($0) }.map { Item(id: $0, header: false) }
        }
        return out
    }

    var body: some View {
        List {
            Section {
                Picker("", selection: $mode) {
                    Text(L("set.layout.groups")).tag(0)
                    Text(L("set.layout.rows")).tag(1)
                }
                .pickerStyle(.segmented)
            } footer: {
                Text(pageTitle + ". " + (mode == 0 ? L("set.layout.tip_groups") : L("set.layout.tip_rows")))
            }
            if mode == 0 {
                Section {
                    let top = (layout.first?.1 ?? []).filter { !hidden($0) }
                    ForEach(top, id: \.self) { id in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title(id))
                            let sub = subtitle(id)
                            if !sub.isEmpty { Text(sub).font(.app(12)).foregroundStyle(.secondary).lineLimit(2) }
                        }
                    }
                    .onMove { from, to in moveGroups(top, from, to) }
                }
            } else {
                Section {
                    let items = rowItems
                    ForEach(items) { item in
                        if item.header {
                            Text(item.id == snap?.model.key ? pageTitle : title(item.id)).font(.app(12, 600))
                                .foregroundStyle(.secondary).textCase(.uppercase)
                                .moveDisabled(true)
                        } else {
                            Text(title(item.id)).padding(.leading, 8)
                        }
                    }
                    .onMove { from, to in moveRows(items, from, to) }
                }
            }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle(L("set.layout.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { asking = true } label: { Image(systemName: "arrow.counterclockwise") }
                    .accessibilityLabel(L("set.layout.default_order"))
            }
        }
        .alert(L("set.layout.reset_title"), isPresented: $asking) {
            Button(L("set.layout.reset"), role: .destructive) {
                guard let snap else { return }
                if SettingsLayout.isDefault(snap.model, store.string(SettingsLayout.key, "")) {
                    SettingsTips.show(L("set.layout.already"))
                } else {
                    store.set(SettingsLayout.key, SettingsLayout.reset(store.string(SettingsLayout.key, ""), snap.model))
                }
            }
            Button(L("settings.cancel"), role: .cancel) {}
        } message: {
            Text(L("set.layout.reset_message"))
        }
        .overlay(alignment: .bottom) { SettingsTips.Toast() }
    }

    private func save(_ edited: [(String, [String])]) {
        guard let snap else { return }
        store.set(SettingsLayout.key, SettingsLayout.store(store.string(SettingsLayout.key, ""), snap.model, edited))
    }

    private func moveGroups(_ shown: [String], _ from: IndexSet, _ to: Int) {
        var moved = shown
        moved.move(fromOffsets: from, toOffset: to)
        var edited = layout
        guard !edited.isEmpty else { return }
        edited[0].1 = SettingsLayoutArrange.arrange(old: edited[0].1, shown: moved, hidden: Set(edited[0].1.filter(hidden)))
        save(edited)
    }

    /// A row dropped among the flat list belongs to the header above it; a move between groups that do not share rows is refused
    private func moveRows(_ items: [Item], _ from: IndexSet, _ to: Int) {
        guard let snap else { return }
        var list = items
        list.move(fromOffsets: from, toOffset: to)
        guard list.first?.header == true else { return }
        var shownBy: [(String, [String])] = []
        for item in list {
            if item.header { shownBy.append((item.id, [])) } else { shownBy[shownBy.count - 1].1.append(item.id) }
        }
        var edited = layout
        for (container, rows) in shownBy {
            for r in rows {
                guard let home = snap.model.home(r) else { continue }
                if !snap.model.moves(home, container) {
                    SettingsTips.show(L("set.layout.refused"))
                    return
                }
            }
            guard let i = edited.firstIndex(where: { $0.0 == container }) else { continue }
            let old = edited[i].1
            // the page's own list: its groups stay after the row they followed, as its hidden rows do
            let kept = Set(old.filter { hidden($0) || snap.model.items($0) != nil || SettingsLayout.built.contains($0) })
            edited[i].1 = SettingsLayoutArrange.arrange(old: old, shown: rows, hidden: kept)
        }
        save(edited)
    }
}

/// SettingsLayout.arrange: a container's items after a move among the items shown - the shown ones in their new order, each hidden
/// item kept after the shown item it followed (or at the start), an item moved out gone
enum SettingsLayoutArrange {
    static func arrange(old: [String], shown: [String], hidden: Set<String>) -> [String] {
        let stays = Set(shown)
        var after: [String: [String]] = [:]
        var head: [String] = []
        var prev: String?
        for item in old {
            if hidden.contains(item) {
                if let prev { after[prev, default: []].append(item) } else { head.append(item) }
            } else if stays.contains(item) {
                prev = item
            }
        }
        var out = head
        for item in shown where !hidden.contains(item) {
            out.append(item)
            out += after[item] ?? []
        }
        return out
    }
}
