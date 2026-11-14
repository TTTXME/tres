import CoreText
import SwiftUI
import UIKit

/// Android Fonts / UiFonts (P582): the interface's font is Roboto Flex at the concept's weights (400 regular, 500 medium,
/// 600 semibold, 700 bold, 800 heavy), the static instances of circularbarlib's res/font (bundled by project.yml from there), so
/// neither the phone's system font nor its text size changes the interface. The five files share one PostScript name
/// («RobotoFlex-Regular»), so they cannot all be registered by name (UIAppFonts registers the 400 one only): each weight is made
/// from its own file (CGFont, not registered) at a fixed size. A missing file falls back to the system font as Android does.
enum AppFont {
    static let regular = 400, medium = 500, semibold = 600, bold = 700, heavy = 800
    private static let weights = [400, 500, 600, 700, 800]
    private static let lock = NSLock()
    private static var graphics: [Int: CGFont] = [:]
    private static var missing: Set<Int> = []
    private static var cache: [String: CTFont] = [:]

    /// UiFonts.of: the bundled instance nearest `weight` (Math.round((weight - 400) / 100), 400 .. 800).
    static func index(_ weight: Int) -> Int {
        max(0, min(4, Int((Double(weight - 400) / 100).rounded())))
    }

    /// The Roboto Flex face of `weight` at `size` points, nil when its file is not in the bundle.
    static func ctFont(size: CGFloat, weight: Int) -> CTFont? {
        let i = index(weight)
        let key = "\(i)/\(size)"
        lock.lock()
        defer { lock.unlock() }
        if let f = cache[key] { return f }
        if missing.contains(i) { return nil }
        var g = graphics[i]
        if g == nil {
            if let url = Bundle.main.url(forResource: "roboto_flex_\(weights[i])", withExtension: "ttf"),
               let provider = CGDataProvider(url: url as CFURL), let font = CGFont(provider) {
                g = font
                graphics[i] = font
            } else {
                missing.insert(i)
                return nil
            }
        }
        guard let g else { return nil }
        let made = CTFontCreateWithGraphicsFont(g, size, nil, nil)
        if cache.count > 256 { cache.removeAll() }
        cache[key] = made
        return made
    }

    /// The same as a UIFont (UIFont and CTFont are toll-free bridged); the system font when the file is missing.
    static func uiFont(size: CGFloat, weight: Int) -> UIFont {
        if let f = ctFont(size: size, weight: weight) { return unsafeBitCast(f, to: UIFont.self) }
        return UIFont.systemFont(ofSize: size, weight: weight >= bold ? .bold : .regular)
    }

    /// UIKit's own bars and controls in the app's font (navigation titles, segmented controls, alerts' buttons stay the system's).
    @MainActor static func applyToUIKit() {
        let title = uiFont(size: 17, weight: semibold), large = uiFont(size: 34, weight: bold)
        let bar = UINavigationBar.appearance()
        bar.titleTextAttributes = [.font: title]
        bar.largeTitleTextAttributes = [.font: large]
        UIBarButtonItem.appearance().setTitleTextAttributes([.font: uiFont(size: 17, weight: regular)], for: .normal)
        UISegmentedControl.appearance().setTitleTextAttributes([.font: uiFont(size: 13, weight: medium)], for: .normal)
        UISegmentedControl.appearance().setTitleTextAttributes([.font: uiFont(size: 13, weight: semibold)], for: .selected)
    }
}

extension Font {
    /// Fonts.of(weight) at `size` points (fixed: the phone's text size does not scale it, as P582).
    static func app(_ size: CGFloat, _ weight: Int = AppFont.regular) -> Font {
        if let f = AppFont.ctFont(size: size, weight: weight) { return Font(f) }
        return .system(size: size, weight: weight >= AppFont.bold ? .bold : weight >= AppFont.semibold ? .semibold
                       : weight >= AppFont.medium ? .medium : .regular)
    }
}
