import Foundation

/// Android DeviceName (P196): the phone a photo names in its EXIF Make / Model - Make the brand («Apple»), Model the phone's market
/// name («iPhone 16 Pro», what the iPhone's own camera writes) - and the watermark's «Apple iPhone 16 Pro» (PhotoInfo.device).
/// iOS gives only the hardware identifier («iPhone17,1», utsname.machine), so its market name comes from the table below; an
/// identifier not in it (a newer iPhone) gives «iPhone».
enum DeviceName {
    static let make = "Apple"

    /// EXIF Model: the market name, else «iPhone». Read once.
    static let model: String = {
        var id = BuildInfo.machine
        if id == "arm64" || id == "x86_64" || id == "i386" {
            id = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? id
        }
        return marketName(id) ?? (id.hasPrefix("iPad") ? "iPad" : "iPhone")
    }()

    /// The watermark's device line for this phone (PhotoInfo.device(make, model)).
    static var device: String { WatermarkExif.device(make: make, model: model) ?? make + " " + model }

    /// The market name of an iPhone hardware identifier (the iPhones iOS 17 and later run on).
    static func marketName(_ id: String) -> String? {
        let names: [String: String] = [
            "iPhone11,2": "iPhone XS", "iPhone11,4": "iPhone XS Max", "iPhone11,6": "iPhone XS Max", "iPhone11,8": "iPhone XR",
            "iPhone12,1": "iPhone 11", "iPhone12,3": "iPhone 11 Pro", "iPhone12,5": "iPhone 11 Pro Max",
            "iPhone12,8": "iPhone SE (2nd generation)",
            "iPhone13,1": "iPhone 12 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro", "iPhone13,4": "iPhone 12 Pro Max",
            "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
            "iPhone14,6": "iPhone SE (3rd generation)", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus",
            "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus",
            "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
            "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus",
            "iPhone17,5": "iPhone 16e",
            "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max", "iPhone18,3": "iPhone 17", "iPhone18,4": "iPhone Air",
            "iPad13,16": "iPad Air (5th generation)", "iPad13,17": "iPad Air (5th generation)",
        ]
        return names[id]
    }
}
