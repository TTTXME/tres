import SwiftUI

@main
struct SCAMERAApp: App {
    @State private var camera = CameraModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        AppFont.applyToUIKit() // Android Fonts: Roboto Flex everywhere, UIKit's bars too
    }

    var body: some Scene {
        WindowGroup {
            CameraView(camera: camera)
                .statusBarHidden(true)
                .preferredColorScheme(.dark)
                .font(.app(17))
                .task { await camera.start() }
        }
        .onChange(of: scenePhase) { _, phase in
            SessionLog.i("App", "scene \(phase)")
        }
    }
}
