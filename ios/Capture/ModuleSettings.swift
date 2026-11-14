import Foundation

/// ModuleRegistry's user choices for the iPhone's lens modules (P21 / P148 ModuleActions: the same keys - module_name_, module_zoom_,
/// module_visible_, module_crop_ - under the iPhone's module ids, which stay on this phone: Profiles.deviceOnly):
/// - the module's own name for its button (empty: its automatic label);
/// - its zoom ratio (empty: the measured one) - the ratio the ruler and the pinch switch modules by;
/// - its button in the viewfinder (a hidden module leaves the module row and the zoom's lenses);
/// - copies with a crop: a second button on the same lens whose frame is cropped by the factor (its zoom the source's times it),
///   the camera's photo through the lens's zoom factor, the SHDR merge through SabreCrop's window.
/// Read with StoredPrefs (the capture actor); the camera takes them when it reloads its modules (the settings closing).
enum ModuleSettings {
    static let copiesKey = "module_copies"

    static func name(_ id: String) -> String? {
        let v = StoredPrefs.string("module_name_" + id, "").trimmingCharacters(in: .whitespaces)
        return v.isEmpty ? nil : v
    }

    static func zoom(_ id: String) -> Double? {
        let v = StoredPrefs.string("module_zoom_" + id, "").trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let z = Double(v), z.isFinite else { return nil }
        return Swift.max(0.1, z)
    }

    static func visible(_ id: String) -> Bool { StoredPrefs.bool("module_visible_" + id, true) }

    /// A copy's crop (1...20; 1 for every other module)
    static func crop(_ id: String) -> Double {
        let v = Double(StoredPrefs.string("module_crop_" + id, "").replacingOccurrences(of: ",", with: ".")) ?? 1
        return v >= 1 && v <= 20 ? v : 1
    }

    /// The copies: (copy id, source id)
    static func copies() -> [(id: String, source: String)] {
        StoredPrefs.string(copiesKey, "").split(separator: ",").compactMap { item in
            let parts = item.split(separator: ":").map(String.init)
            return parts.count == 2 ? (parts[0], parts[1]) : nil
        }
    }

    static func copiesText(_ list: [(id: String, source: String)]) -> String { list.map { $0.id + ":" + $0.source }.joined(separator: ",") }

    /// A new copy's id (copy1, copy2, ...)
    static func freeCopyID() -> String {
        let used = Set(copies().map { $0.id })
        var n = 1
        while used.contains("copy\(n)") { n += 1 }
        return "copy\(n)"
    }

    /// The lens modules with the copies added and the user's zoom ratios; `all`: the hidden ones too (the settings' list)
    static func apply(_ lenses: [LensModule], all: Bool = false) -> [LensModule] {
        var out = lenses
        for c in copies() {
            guard let src = lenses.first(where: { $0.id == c.source }) else { continue }
            let k = crop(c.id)
            out.append(LensModule(id: c.id, deviceID: src.deviceID, isFront: src.isFront, kind: src.kind, zoom: src.zoom * k,
                                  deviceZoom: src.deviceZoom * k, deviceName: src.deviceName, crop: k))
        }
        out = out.map { m in
            guard let z = zoom(m.id) else { return m }
            var c = m
            c.zoom = z
            return c
        }
        if !all { out = out.filter { visible($0.id) } }
        // the back modules by their ratio, then the front ones (DeviceLookup.modules' order)
        return out.enumerated().sorted { a, b in
            if a.element.isFront != b.element.isFront { return !a.element.isFront }
            if a.element.zoom != b.element.zoom { return a.element.zoom < b.element.zoom }
            return a.offset < b.offset
        }.map { $0.element }
    }
}
