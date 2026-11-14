import SwiftUI

/// «Название фото» (pref_file_name_pattern) as a row: under its title the name a photo taken now gets (P119 / P592: its state);
/// a tap opens FileNameDialog.
struct FileNameRow<Label: View>: View {
    let label: (String) -> Label
    @State private var editing = false
    private var store: SettingsStore { SettingsStore.shared }

    var body: some View {
        Button { editing = true } label: {
            label(FileNamePattern.format(store.string(FileNamePattern.key, FileNamePattern.defaultPattern), date: Date()) + ".jpg")
        }
        .sheet(isPresented: $editing) { FileNameDialog() }
    }
}

/// FileNameDialog (ui/settings/FileNameDialog.java, P119): the template, the name it gives now, the date / time variables to insert,
/// the ready templates; «Сбросить» (the default SCA_yyyyMMdd_HHmmss, saved with «Сохранить»), «Отмена», «Сохранить». The name is the
/// photo's original file name in the library (PhotoFileName).
struct FileNameDialog: View {
    @Environment(\.dismiss) private var dismiss
    @State private var text = SettingsStore.shared.string(FileNamePattern.key, FileNamePattern.defaultPattern)

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField(L("filename.hint"), text: $text)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("filename.preview")).font(.app(12)).foregroundStyle(.secondary)
                        Text(FileNamePattern.format(text, date: Date()) + ".jpg").font(.callout.monospaced())
                    }
                }
                Section(L("filename.variables")) { chips(FileNamePattern.variables) { text += $0 } }
                Section(L("filename.presets")) { chips(FileNamePattern.presets) { text = $0 } }
                Section {
                    Button(L("filename.reset")) { text = FileNamePattern.defaultPattern }
                }
            }
            .navigationTitle(L("filename.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("settings.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("set.modules.save")) {
                        SettingsStore.shared.set(FileNamePattern.key, FileNamePattern.clean(text))
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }

    private func chips(_ items: [String], _ tap: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(items, id: \.self) { v in
                    Button { tap(v) } label: {
                        Text(v).font(.app(14)).padding(.horizontal, 14).padding(.vertical, 9)
                            .background(Color(white: 0.23), in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}
