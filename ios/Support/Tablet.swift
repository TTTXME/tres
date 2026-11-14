import SwiftUI
import UIKit

/// The iPad (a regular-width window). Android adapts to a tablet in three ways, mirrored here: UiFixedScale keeps a large
/// screen's own density (sw >= 600 dp: the controls keep their dp size, the screens fill the width), values-sw500dp gives the
/// focus ring 100 dp instead of 76 dp, and the gallery multiplies its columns by GalleryUi.widthFactor (one set per 420 dp).
/// The camera stays portrait (CameraActivity locks it on every device). Where Android has nothing of its own (the settings'
/// width, the editor's panel, the camera's floating sheets), the iPad gets a readable column in the phone's look.
enum Tablet {
    /// GalleryUi.widthFactor: round(width / 420), at least 1 (an iPhone: 1; an iPad in portrait: 2)
    static func widthFactor(_ width: CGFloat) -> Int {
        guard width > 0 else { return 1 }
        return Swift.max(1, Int((width / 420).rounded()))
    }

    /// The window's width when a view has no geometry of its own (the app runs full screen, so the screen's)
    @MainActor static var screenWidth: CGFloat { UIScreen.main.bounds.width }

    /// The readable column of the settings' pages and the editor's tools on an iPad
    static let readableWidth: CGFloat = 680

    /// values-sw500dp's focus_circle_size over values' (100 dp / 76 dp)
    static let focusRingScale: CGFloat = 100.0 / 76.0
}

/// A regular-width window keeps `content` in a centred column at most `maxWidth` wide; a phone gets `content` as it is.
struct TabletColumn: ViewModifier {
    var maxWidth: CGFloat = Tablet.readableWidth
    @Environment(\.horizontalSizeClass) private var sizeClass

    func body(content: Content) -> some View {
        if sizeClass == .regular {
            content.frame(maxWidth: maxWidth).frame(maxWidth: .infinity)
        } else {
            content
        }
    }
}

/// A settings list on an iPad: its rows in the readable column, the grouped background on both sides of it (the page is no
/// stretched phone list); a phone's list as it is.
struct TabletReadableList: ViewModifier {
    @Environment(\.horizontalSizeClass) private var sizeClass

    func body(content: Content) -> some View {
        if sizeClass == .regular {
            content
                .frame(maxWidth: Tablet.readableWidth)
                .frame(maxWidth: .infinity)
                .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        } else {
            content
        }
    }
}

extension View {
    /// TabletColumn
    func tabletColumn(_ maxWidth: CGFloat = Tablet.readableWidth) -> some View { modifier(TabletColumn(maxWidth: maxWidth)) }
    /// TabletReadableList
    func tabletReadableList() -> some View { modifier(TabletReadableList()) }
}
