import Foundation

/// Interface text from Localizable.strings (en, ru) in the system language.
func L(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}
