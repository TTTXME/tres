import SwiftUI

/// An Android string resource of the catalog with its Java format arguments (%s, %1$s, %d) filled in
enum AndroidText {
    static func get(_ name: String, _ args: String...) -> String {
        var text = SettingsCatalog.shared.text(SettingsNode.Text(s: name, t: nil)) ?? name
        for (i, a) in args.enumerated() {
            for token in ["%\(i + 1)$s", "%\(i + 1)$d"] { text = text.replacingOccurrences(of: token, with: a) }
            if let r = text.range(of: "%s") ?? text.range(of: "%d") { text.replaceSubrange(r, with: a) }
        }
        return text
    }
}

/// ModuleProfiles (settings/ModuleProfiles.java, P189 «Свои настройки для каждого модуля», pref_save_per_lens_settings): with it on
/// every module keeps its own photo settings - a module switch puts the old module's values aside and takes the new one's (a module
/// without a profile yet starts from the settings as they are). Settings shared by every lens (sounds, grid, the photo format and its
/// options, the watermark, the theme, ...) never take part (isLocal).
enum ModuleProfiles {
    static let switchKey = "pref_save_per_lens_settings"
    static let globalKeys: Set<String> = [
        "pref_camera_sounds_key", "pref_timer_sound_key", "pref_show_grid_key", "pref_save_raw_key", "pref_ultrahdr_key",
        "pref_show_watermark_key", "pref_keep_raw_burst", "pref_root_enabled", "pref_camera_package_spoof_enabled",
        "pref_oplus_spoof_package_key", "pref_generic_spoof_package_key", "pref_binder_spoof_package_key", "pref_face_detect_mode",
        "pref_tracking_af_mode", "pref_hide_gallery_icon_key", "pref_antibanding_hz_key", "pref_file_name_pattern", "pref_merge_route",
        "pref_motion_mode", "pref_low_light_boost", "pref_scam_lab_keep",
    ]
    static let globalPrefixes = ["pref_photo_", "pref_jpeg_", "pref_heic_", "pref_webp_", "pref_avif_", "pref_watermark_", "pref_theme"]

    static func isGlobal(_ key: String) -> Bool { globalKeys.contains(key) || globalPrefixes.contains { key.hasPrefix($0) } }

    static func isLocal(_ key: String) -> Bool {
        if key == "pref_zsl_buffer_count_key" || isGlobal(key) || key == "camera_id" || key == switchKey { return false }
        if key.hasPrefix("lens_") || key.hasPrefix("module_") || key.hasPrefix("pref_sensorconfig_") || key.hasPrefix("settings_")
            || key.contains("debug") || key.contains("folder") || key.contains("config_file") { return false }
        if key == "user_camera_ids" || key == "hidden_camera_ids" || key == "pref_camera_mode_key" { return false }
        return key.hasPrefix("pref_") || key.hasPrefix("scamera_")
    }

    static var on: Bool { SettingsStore.shared.bool(switchKey, false) }

    private static func storeKey(_ id: String) -> String { "scam.module_profile." + id }
    static func profile(_ id: String) -> [String: Any]? { UserDefaults.standard.dictionary(forKey: storeKey(id)) }
    static func hasOwn(_ id: String) -> Bool { profile(id) != nil }

    static func save(_ id: String) {
        let local = SettingsStore.shared.values.filter { isLocal($0.key) }
        UserDefaults.standard.set(local, forKey: storeKey(id))
    }

    /// The camera switched modules: the old module's values aside, the new one's in (with per-lens settings on)
    static func switched(from old: String?, to new: String) {
        guard on, old != new else { return }
        if let old { save(old) }
        guard let own = profile(new) else {
            save(new)
            return
        }
        var values = SettingsStore.shared.values.filter { !isLocal($0.key) }
        for (k, v) in own where isLocal(k) { values[k] = v }
        SettingsStore.shared.replaceAll(values)
        SessionLog.i("Settings", "module \(new): its own settings (\(own.count) values)")
    }

    /// ModuleActions.perLensSwitch: on - every module starts from the settings as they are now; off - the profiles are forgotten
    static func set(_ enabled: Bool, active: String?) {
        SettingsStore.shared.set(switchKey, enabled)
        for key in UserDefaults.standard.dictionaryRepresentation().keys where key.hasPrefix("scam.module_profile.") {
            UserDefaults.standard.removeObject(forKey: key)
        }
        if enabled, let active { save(active) }
    }
}

/// «Модули камеры» (ModuleRows' root_modules, ModuleActions): one card per module of this iPhone (a physical lens, an in-sensor zoom
/// of one, a copy with a crop) - its name, its zoom ratio, its viewfinder button, a copy with a crop (a copy: its crop, deleting it),
/// then adding a copy and «Свои настройки для каждого модуля». The Camera ID, the sensor mode and the sensor crop are Android's
/// camera ids and vendor sensor modes: AVFoundation names its lenses itself and offers no sensor modes.
struct ModulesCard: View {
    @Environment(\.settingsCamera) private var camera
    @State private var lenses: [LensModule] = []
    @State private var open: String?
    @State private var renaming: LensModule?
    @State private var zooming: LensModule?
    @State private var cropping: LensModule?
    @State private var copying: LensModule?
    @State private var deleting: LensModule?
    @State private var choosingSource = false
    @State private var askingPerLens = false
    @State private var typed = ""
    private var store: SettingsStore { SettingsStore.shared }
    static let crops: [Double] = [1, 1.2, 1.3, 1.5, 2]

    static func cropText(_ v: Double) -> String {
        var t = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), v)
        while t.hasSuffix("0") { t.removeLast() }
        if t.hasSuffix(".") { t.removeLast() }
        return t
    }

    private var modules: [LensModule] {
        _ = store.values   // drawn again when a module key changes
        return ModuleSettings.apply(lenses, all: true)
    }

    private func source(_ m: LensModule) -> LensModule? {
        guard let s = ModuleSettings.copies().first(where: { $0.id == m.id })?.source else { return nil }
        return modules.first { $0.id == s }
    }

    private func kind(_ m: LensModule) -> String {
        if let s = source(m) { return AndroidText.get("module_kind_copy", s.label) }
        if m.isFront { return AndroidText.get("module_kind_front") }
        let lens = m.zoom / max(m.crop, 1)
        return AndroidText.get(lens < 0.9 ? "module_kind_ultrawide" : lens < 1.6 ? "module_kind_main" : "module_kind_tele")
            + (m.kind == .isz ? " · " + L("set.modules.isz") : "")
    }

    private func state(_ m: LensModule) -> String {
        var parts: [String] = []
        if m.id == camera?.activeModuleID { parts.append(AndroidText.get("module_state_active")) }
        if m.crop > 1.0001 { parts.append(AndroidText.get("module_state_crop", Self.cropText(m.crop))) }
        if !ModuleSettings.visible(m.id) { parts.append(AndroidText.get("module_state_hidden")) }
        parts.append(m.deviceName)
        return parts.joined(separator: " · ")
    }

    var body: some View {
        Group {
            if modules.isEmpty {
                Text(L("set.modules.none")).font(.app(13)).foregroundStyle(.secondary)
            }
            ForEach(modules) { m in
                DisclosureGroup(isExpanded: Binding(get: { open == m.id }, set: { open = $0 ? m.id : nil })) {
                    rows(m)
                } label: {
                    HStack(spacing: 12) {
                        Text(m.label).font(.app(17, 600).monospacedDigit()).frame(minWidth: 52, alignment: .leading)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(kind(m))
                            Text(state(m)).font(.app(12)).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Button { choosingSource = true } label: {
                Label(AndroidText.get("module_row_add"), systemImage: "plus")
            }
            Toggle(isOn: Binding(get: { ModuleProfiles.on }, set: { on in
                if on { askingPerLens = true } else { ModuleProfiles.set(false, active: camera?.activeModuleID) }
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(AndroidText.get("module_row_per_lens"))
                    Text(AndroidText.get(ModuleProfiles.on ? "module_row_per_lens_on" : "module_row_per_lens_off").replacingOccurrences(of: " / 200 MP", with: ""))
                        .font(.app(12)).foregroundStyle(.secondary)
                }
            }
            Text(L("set.modules.note")).font(.app(12)).foregroundStyle(.secondary)
        }
        .task {
            lenses = await Task.detached(priority: .userInitiated) { DeviceLookup.modules() }.value
        }
        .alert(AndroidText.get("module_row_name"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(renaming.map { LensMath.label($0.zoom) } ?? "", text: $typed)
            Button(L("set.modules.save")) {
                if let m = renaming { store.set("module_name_" + m.id, typed.trimmingCharacters(in: .whitespaces)) }
            }
            Button(L("settings.cancel"), role: .cancel) {}
        }
        .alert(AndroidText.get("module_row_zoom"), isPresented: Binding(get: { zooming != nil }, set: { if !$0 { zooming = nil } })) {
            TextField(L("set.modules.zoom_hint"), text: $typed).keyboardType(.decimalPad)
            Button(L("set.modules.save")) {
                guard let m = zooming else { return }
                if let z = Double(typed.replacingOccurrences(of: ",", with: ".")), z.isFinite, z > 0 {
                    store.set("module_zoom_" + m.id, Self.cropText(max(0.1, z)))
                }
            }
            Button(L("set.modules.auto")) { if let m = zooming { store.set("module_zoom_" + m.id, nil) } }
            Button(L("settings.cancel"), role: .cancel) {}
        } message: {
            Text(L("set.modules.sensor_crop_na"))
        }
        .confirmationDialog(L("set.modules.copy_which"), isPresented: $choosingSource, titleVisibility: .visible) {
            ForEach(modules.filter { source($0) == nil }) { m in
                Button(m.label + " · " + kind(m)) { copying = m }
            }
        }
        .confirmationDialog(cropTitle, isPresented: Binding(get: { cropping != nil || copying != nil },
                                                            set: { if !$0 { cropping = nil; copying = nil } }), titleVisibility: .visible) {
            let base = (copying ?? cropping.flatMap(source))?.zoom ?? 1
            ForEach(Self.crops.filter { copying == nil || $0 > 1 }, id: \.self) { k in
                Button(k == 1 ? String(format: L("set.modules.crop_none"), Self.cropText(base))
                       : String(format: L("set.modules.crop_option"), Self.cropText(k), Self.cropText(base * k))) { applyCrop(k) }
            }
        }
        .alert(deleting.map { String(format: L("set.modules.delete_title"), $0.label) } ?? "",
               isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button(L("set.modules.delete"), role: .destructive) { if let m = deleting { deleteCopy(m) } }
            Button(L("settings.cancel"), role: .cancel) {}
        } message: {
            Text(deleting.flatMap(source).map { AndroidText.get("module_row_delete_summary", $0.label) } ?? "")
        }
        .alert(AndroidText.get("module_row_per_lens"), isPresented: $askingPerLens) {
            Button(L("set.modules.turn_on")) { ModuleProfiles.set(true, active: camera?.activeModuleID) }
            Button(L("settings.cancel"), role: .cancel) {}
        } message: {
            Text(L("set.modules.per_lens_ask"))
        }
    }

    private var cropTitle: String {
        if let m = copying { return String(format: L("set.modules.copy_crop_of"), m.label) }
        return AndroidText.get("module_row_crop")
    }

    @ViewBuilder private func rows(_ m: LensModule) -> some View {
        Button {
            typed = ModuleSettings.name(m.id) ?? ""
            renaming = m
        } label: { row(AndroidText.get("module_row_name"), m.label) }
        Button {
            typed = ModuleSettings.zoom(m.id).map(Self.cropText) ?? ""
            zooming = m
        } label: { row(AndroidText.get("module_row_zoom"), LensMath.label(m.zoom)) }
        Toggle(isOn: Binding(get: { ModuleSettings.visible(m.id) }, set: { store.set("module_visible_" + m.id, $0) })) {
            row(AndroidText.get("module_row_button"),
                AndroidText.get(ModuleSettings.visible(m.id) ? "module_row_button_on" : "module_row_button_off"))
        }
        if let s = source(m) {
            Button { cropping = m } label: {
                row(AndroidText.get("module_row_crop"),
                    AndroidText.get("module_row_crop_of", Self.cropText(m.crop), s.label, Self.cropText(m.zoom)))
            }
            Button(role: .destructive) { deleting = m } label: { row(AndroidText.get("module_row_delete"), nil) }
        } else {
            Button { copying = m } label: { row(AndroidText.get("module_row_copy"), nil) }
        }
        row(AndroidText.get("module_row_profile"), AndroidText.get(ModuleProfiles.on && ModuleProfiles.hasOwn(m.id)
                                                                   ? "module_row_profile_value_on" : "module_row_profile_value_off"))
    }

    private func row(_ title: String, _ value: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(.primary)
            if let value, !value.isEmpty { Text(value).font(.app(12)).foregroundStyle(.secondary) }
        }
    }

    private func applyCrop(_ k: Double) {
        if let src = copying {
            let id = ModuleSettings.freeCopyID()
            var list = ModuleSettings.copies()
            list.append((id, src.id))
            store.set("module_crop_" + id, Self.cropText(k))
            store.set(ModuleSettings.copiesKey, ModuleSettings.copiesText(list))
            SettingsTips.show(String(format: L("set.modules.created"), LensMath.label(src.zoom * k)))
            open = id
        } else if let m = cropping {
            store.set("module_crop_" + m.id, Self.cropText(k))
            store.set("module_zoom_" + m.id, nil)
        }
        copying = nil
        cropping = nil
    }

    private func deleteCopy(_ m: LensModule) {
        store.set(ModuleSettings.copiesKey, ModuleSettings.copiesText(ModuleSettings.copies().filter { $0.id != m.id }))
        for key in ["module_crop_", "module_name_", "module_zoom_", "module_visible_"] { store.set(key + m.id, nil) }
        UserDefaults.standard.removeObject(forKey: "scam.module_profile." + m.id)
        deleting = nil
    }
}
