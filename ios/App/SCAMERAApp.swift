import SwiftUI

@main
struct SCAMERAApp: App {
    @UIApplicationDelegateAdaptor(SCAMERAAppDelegate.self) private var appDelegate
    @State private var camera = CameraModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CameraView(camera: camera)
                .statusBarHidden(true)
                .preferredColorScheme(.dark)
                .task {
                    DeviceTurn.shared.start()
                    await camera.start()
                    // P470: the GPU programs before the first shot (a dialog after an install or an update)
                    FirstRunCompile.shared.maybeRun()
                }
        }
        .onChange(of: scenePhase) { _, phase in
            SessionLog.i("App", "scene \(phase)")
            if phase == .background { FirstRunCompile.saveRecipes() }
        }
    }
}
