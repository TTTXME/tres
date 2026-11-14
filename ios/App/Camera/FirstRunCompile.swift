import SwiftUI
import UIKit

/// FirstRunCompile (P470): after an install, an update or a new iOS (a new GPU compiler), a dialog in the middle of the screen
/// builds the SHDR merge's GPU programs (hybridPrewarmGpu of the plain Bayer block with the settings' tuning) and the post
/// pipeline's programs kept by earlier runs (ScamGL's recipes, saved when the app goes to the background), so the first shot
/// compiles none of them; Metal keeps the compiled functions in its own cache for the next launches. The stamp is written when it
/// finished; an interrupted run starts again at the next launch. Later launches build the same programs quietly in the
/// background (Android's module-open prewarm, P35).
@MainActor
@Observable
final class FirstRunCompile {
    static let shared = FirstRunCompile()
    private static let stampKey = "scam_first_run.compiled_for"
    private(set) var showing = false
    private(set) var progress = 0.0
    @ObservationIgnored private var running = false

    static var recipes: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("scamgl_programs.bin")
    }

    /// The app's version and build, its commit, the iOS version and the phone
    static var stamp: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let v = (info["CFBundleShortVersionString"] as? String ?? "") + "(" + (info["CFBundleVersion"] as? String ?? "") + ")"
        return v + "|" + (info["SCAMGitSHA"] as? String ?? "") + "|" + UIDevice.current.systemVersion + "|" + BuildInfo.machine
    }

    /// The dialog and the compile when this install / iOS has not been compiled for yet; else the quiet warm-up
    func maybeRun() {
        guard !running else { return }
        running = true
        let first = UserDefaults.standard.string(forKey: Self.stampKey) != Self.stamp
        let settings = PostSettings.json()
        let recipes = Self.recipes.path
        let stamp = Self.stamp
        if first {
            progress = 0
            withAnimation(.easeOut(duration: 0.2)) { showing = true }
        }
        let started = Date()
        Task.detached(priority: first ? .userInitiated : .background) { [weak self] in
            if !first { try? await Task.sleep(nanoseconds: 3_000_000_000) }
            let lines = SCAMWarmUp.prewarmMerge(withSettings: settings) { done, total in
                let p = total > 0 ? min(1, Double(done) / Double(total)) : 1
                Task { @MainActor in self?.advance(p * 0.5) }
            }
            lines.forEach { SessionLog.i("FirstRun", $0) }
            let built = SCAMWarmUp.warmPrograms(atPath: recipes) { done, total in
                let p = total > 0 ? min(1, Double(done) / Double(total)) : 1
                Task { @MainActor in self?.advance(0.5 + p * 0.5) }
            }
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            await MainActor.run {
                SessionLog.i("FirstRun", (first ? "first-run compile" : "warm-up") + " done in \(ms) ms, \(built) kept programs")
                if first { UserDefaults.standard.set(stamp, forKey: Self.stampKey) }
                withAnimation(.easeIn(duration: 0.2)) { self?.showing = false }
                self?.running = false
            }
        }
    }

    private func advance(_ p: Double) { progress = max(progress, p) }

    /// The app goes to the background: this run's programs join the recipes
    static func saveRecipes() {
        let path = recipes.path
        Task.detached(priority: .utility) {
            guard SCAMWarmUp.programCount() > 0 else { return }
            let n = SCAMWarmUp.savePrograms(toPath: path)
            SessionLog.i("FirstRun", "recipes kept: \(n)")
        }
    }
}

/// The dialog: «Подготовка камеры», what it does, the progress bar (no way to cancel it, as Android's)
struct FirstRunDialog: View {
    private let model = FirstRunCompile.shared

    var body: some View {
        if model.showing {
            ZStack {
                Color.black.opacity(0.55).ignoresSafeArea()
                VStack(alignment: .leading, spacing: 12) {
                    Text(L("firstrun.title")).font(.app(18, 600)).foregroundStyle(CameraStyle.text)
                    Text(L("firstrun.text")).font(.app(15)).foregroundStyle(CameraStyle.muted).multilineTextAlignment(.leading)
                    ProgressView(value: model.progress).tint(CameraStyle.accent)
                }
                .padding(24)
                .background(CameraStyle.control, in: RoundedRectangle(cornerRadius: 28))
                // an iPad: a dialog's width, not the screen's (a phone's room is narrower than this)
                .frame(maxWidth: 480)
                .padding(.horizontal, 32)
            }
            .transition(.opacity)
            .accessibilityElement(children: .combine)
        }
    }
}

/// The Home Screen quick action «Настройки» (Android's launcher shortcut, P140): the camera opens with its settings over it
@MainActor
@Observable
final class QuickActions {
    static let shared = QuickActions()
    static let settings = "com.scamera.ios.settings"
    var pending: String?
}

final class SCAMERAAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if let item = options.shortcutItem {
            let type = item.type
            Task { @MainActor in QuickActions.shared.pending = type }
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SCAMERASceneDelegate.self
        return configuration
    }
}

final class SCAMERASceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        let type = shortcutItem.type
        Task { @MainActor in QuickActions.shared.pending = type }
        completionHandler(true)
    }
}
