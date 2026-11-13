import SwiftUI

@main
struct SCAMERAApp: App {
    @State private var camera = CameraModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CameraView(camera: camera)
                .statusBarHidden(true)
                .preferredColorScheme(.dark)
                .task { await camera.start() }
        }
        .onChange(of: scenePhase) { _, phase in
            SessionLog.i("App", "scene \(phase)")
        }
    }
}
