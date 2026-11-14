import SwiftUI

/// P665 EditDevelopPanel: the editor's «Проявка» tab - every processing setting of the camera (DevelopCatalog), grouped as the
/// camera's settings (Тон, Света, Резкость, Шумоподавление, Цвет, Плёнка, LUT, Портрет, Oldstyle for a shot of that mode, and
/// Склейка greyed). A DNG of ours with its develop record: the switch «Проявка камерой» develops it with the camera's own
/// pipeline, each row starting at the value the photo was shot with; a change re-develops the preview. Any other photo: the rows
/// are greyed with why. The rows are the settings screen's own (SettingsRow) over the shot's values with the changes.
@MainActor
struct EditDevelopPanel: View {
    let model: EditorModel
    @State private var help: (title: String, text: String)?
    /// the open group, kept while the app runs (EditDevelopPanel.openGroup)
    @AppStorage("editor_develop_group") private var openGroup = DevelopCatalog.tone

    var body: some View {
        let why = model.developUnavailable
        let groups = model.developGroups
        let group = groups.first { $0.id == openGroup } ?? groups.first
        VStack(spacing: 0) {
            hint(why ?? model.developStatusText)
            if why == nil { developSwitch }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(groups) { g in GalleryChip(text: g.title, on: g.id == group?.id) { openGroup = g.id } }
                }
                .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 6)
            }
            if let group { rows(group, why) }
        }
        .onAppear { model.loadDevelopGroups() }
        .alert(help?.title ?? "", isPresented: Binding(get: { help != nil }, set: { if !$0 { help = nil } })) {
            Button(L("gallery.ok"), role: .cancel) { help = nil }
        } message: {
            Text(help?.text ?? "")
        }
    }

    private var developSwitch: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("editor.develop.switch")).font(.system(size: 16)).foregroundStyle(GalleryStyle.text)
                Text(L("editor.develop.switch_note")).font(.system(size: 12)).foregroundStyle(GalleryStyle.muted)
            }
            Spacer(minLength: 8)
            Toggle("", isOn: Binding(get: { model.state.develop }, set: { model.setDevelop($0) }))
                .labelsHidden()
                .tint(GalleryStyle.accent)
        }
        .padding(.horizontal, 16).padding(.vertical, 8).frame(minHeight: 52)
    }

    /// The open group's rows: its sections' captions, a control per setting, greyed where it changes nothing.
    @ViewBuilder private func rows(_ g: DevelopCatalog.Group, _ why: String?) -> some View {
        let active = why == nil && model.state.develop
        let shot = model.developShot
        let values = model.developValues
        if g.id == DevelopCatalog.merge { hint(L("editor.develop.merge_note")) }
        ForEach(Array(g.rows.enumerated()), id: \.element.id) { i, r in
            if let section = r.section, section != (i > 0 ? g.rows[i - 1].section : nil) { caption(section) }
            let inert: String? = !active ? (why ?? L("editor.develop.switch_on")) : DevelopCatalog.inert(r, values)
            row(r, shot: shot, values: values, inert: inert)
        }
    }

    @ViewBuilder private func row(_ r: DevelopCatalog.Row, shot: [String: Any], values: [String: Any], inert: String?) -> some View {
        let now = DevelopCatalog.value(r, shot: shot, changes: model.state.devChanges)
        let asShot = shot[r.key] ?? r.def
        let changed = model.state.devChanges[r.key] != nil
        VStack(alignment: .leading, spacing: 0) {
            switch r.kind {
            case .toggle, .list, .slider:
                SettingsRow(node: r.node, source: DevelopValues(values: values) { [model] _, v in model.setDevelopValue(r, v) },
                            fixedReason: inert, menuLists: true)
            case .custom, .text:
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.title).font(.system(size: 15)).foregroundStyle(GalleryStyle.text)
                    Text(L("editor.develop.as_shot") + " " + DevelopCatalog.shown(r, now)).font(.caption).foregroundStyle(GalleryStyle.muted)
                        .lineLimit(2)
                    if let inert { Text(inert).font(.caption2).foregroundStyle(.orange) }
                }
                .opacity(0.45)
            }
            if changed {
                Text(L("editor.develop.changed") + " " + DevelopCatalog.shown(r, asShot)).font(.caption2).foregroundStyle(GalleryStyle.accent)
            }
        }
        .padding(.horizontal, 18).padding(.vertical, 4)
        .helpOnLongPress {
            let base = SettingsCatalog.shared.text(r.node.summary) ?? r.title
            help = (r.title, inert.map { base + "\n\n" + $0 } ?? base)
        }
    }

    private func caption(_ text: String) -> some View {
        Text(text).font(.system(size: 13, weight: .semibold)).foregroundStyle(GalleryStyle.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 2)
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.system(size: 12)).foregroundStyle(GalleryStyle.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18).padding(.top, 6).padding(.bottom, 4)
    }
}
