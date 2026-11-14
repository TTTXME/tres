import Foundation

/// The app's preferences for the ported pipeline in the Android app's form (the lab json: {"prefs": {key: [type, value]},
/// "dev": {key: number}}): what is not stored takes the Android defaults.
enum PostSettings {
    static let prefsKey = "scam.prefs"
    static let devKey = "scam.dev"

    static func json() -> String {
        let defaults = UserDefaults.standard
        var prefs: [String: Any] = [:]
        for (key, value) in defaults.dictionary(forKey: prefsKey) ?? [:] {
            switch value {
            case let n as NSNumber where CFGetTypeID(n) == CFBooleanGetTypeID(): prefs[key] = ["b", n.boolValue]
            case let s as String: prefs[key] = ["s", s]
            case let n as NSNumber: prefs[key] = [CFNumberIsFloatType(n) ? "f" : "i", n]
            case let set as [String]: prefs[key] = ["set", set]
            default: break
            }
        }
        // the app's own developer values, then Documents/scam_dev.txt over them (ScamDevFile: Android's file, its lines win)
        var dev = defaults.dictionary(forKey: devKey) as? [String: Double] ?? [:]
        for (key, value) in ScamDevFile.values() { dev[key] = value }
        let object: [String: Any] = ["prefs": prefs, "dev": dev]
        guard let data = try? JSONSerialization.data(withJSONObject: object), let text = String(data: data, encoding: .utf8) else {
            return "{\"prefs\":{},\"dev\":{}}"
        }
        return text
    }

    static func set(_ key: String, _ value: Any?) {
        var prefs = UserDefaults.standard.dictionary(forKey: prefsKey) ?? [:]
        prefs[key] = value
        UserDefaults.standard.set(prefs, forKey: prefsKey)
    }
}
