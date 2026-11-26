import Foundation
import UIKit

/// App information, lifecycle events and URL handling. Incoming URLs are posted to it as
/// ``urlOpenNotification``.
final class AppPlugin: WefterPlugin {

    /// Posted with `url`, `sourceApplication` and `isLaunch` in its user info when the app is opened with a
    /// URL.
    static let urlOpenNotification = Notification.Name("dev.wefter.plugins.app.openURL")

    private var lifecycleState = "active"
    private var launchUrl: String?

    /// Observes lifecycle, memory warning and URL notifications.
    public required init(dispatcher: BridgeDispatcher, viewController: UIViewController?) {
        super.init(dispatcher: dispatcher, viewController: viewController)

        let center = NotificationCenter.default
        center.addObserver(
            self, selector: #selector(handleDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification, object: nil)
        center.addObserver(
            self, selector: #selector(handleWillResignActive),
            name: UIApplication.willResignActiveNotification, object: nil)
        center.addObserver(
            self, selector: #selector(handleDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
        center.addObserver(
            self, selector: #selector(handleMemoryWarning),
            name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
        center.addObserver(
            self, selector: #selector(handleUrlOpen(_:)),
            name: AppPlugin.urlOpenNotification, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    /// Name, bundle id, version, build, debug flag and install times of the app.
    // @WefterMethod
    func getInfo(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        let info = Bundle.main.infoDictionary ?? [:]
        let name =
            (info["CFBundleDisplayName"] as? String)
            ?? (info["CFBundleName"] as? String)
            ?? ""
        let id = Bundle.main.bundleIdentifier ?? (info["CFBundleIdentifier"] as? String) ?? ""
        let version = info["CFBundleShortVersionString"] as? String ?? ""
        let build = info["CFBundleVersion"] as? String ?? ""

        var debug = false
        #if DEBUG
        debug = true
        #endif

        resolve(callback, data: [
            "name": name,
            "id": id,
            "version": version,
            "build": build,
            "debug": debug,
            "firstInstallTime": 0,
            "lastUpdateTime": 0,
        ])
    }

    /// The current lifecycle state.
    // @WefterMethod
    func getState(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        resolve(callback, data: ["isActive": lifecycleState == "active", "state": lifecycleState])
    }

    /// The URL the app was launched with, if any.
    // @WefterMethod
    func getLaunchUrl(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        resolve(callback, data: ["url": launchUrl ?? NSNull()])
    }

    /// Not allowed on iOS; rejects with `UNIMPLEMENTED`.
    // @WefterMethod
    func minimizeApp(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        reject(
            callback, code: "UNIMPLEMENTED",
            message: "minimizeApp() is not supported on iOS; Apple prohibits programmatic suspension.")
    }

    /// Terminates the app right after answering.
    // @WefterMethod
    func exitApp(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        resolve(callback, data: ["success": true])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { exit(0) }
    }

    /// Not possible on iOS; rejects with `UNIMPLEMENTED`.
    // @WefterMethod
    func relaunchApp(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        reject(
            callback, code: "UNIMPLEMENTED",
            message: "iOS cannot relaunch itself; the user must reopen the app.")
    }

    /// Opens a URL with the system.
    // @WefterMethod
    func openUrl(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        guard let raw = payload["url"] as? String, let url = URL(string: raw) else {
            reject(callback, code: "INVALID_ARGUMENT", message: "A valid 'url' is required.")
            return
        }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { ok in
                if ok {
                    self.resolve(callback, data: ["success": true])
                } else {
                    self.reject(callback, code: "NO_HANDLER", message: "No app can open: \(raw)")
                }
            }
        }
    }

    /// Whether some installed app can open a URL. Custom schemes must be listed in
    /// `LSApplicationQueriesSchemes`.
    // @WefterMethod
    func canOpenUrl(payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void) throws {
        guard let raw = payload["url"] as? String, let url = URL(string: raw) else {
            reject(callback, code: "INVALID_ARGUMENT", message: "A valid 'url' is required.")
            return
        }
        DispatchQueue.main.async {
            self.resolve(callback, data: ["value": UIApplication.shared.canOpenURL(url)])
        }
    }

    /// iOS has no back button; accepted and ignored.
    // @WefterMethod
    func setBackButtonInterceptEnabled(
        payload: [String: Any], callback: @escaping (Result<Any, Error>) -> Void
    ) throws {
        resolve(callback, data: ["success": true])
    }

    /// Reports a memory warning as `app:lowMemory`.
    /// Lifecycle notifications, reported as `app:stateChange`.
    @objc private func handleDidBecomeActive() { setState("active") }
    @objc private func handleWillResignActive() { setState("inactive") }
    @objc private func handleDidEnterBackground() { setState("background") }
    @objc private func handleMemoryWarning() { emit("app:lowMemory", ["level": -1]) }

    /// Reports an incoming URL as `app:urlOpen` and remembers it if it launched the app.
    @objc private func handleUrlOpen(_ note: Notification) {
        guard let url = note.userInfo?["url"] as? String, !url.isEmpty else { return }

        if (note.userInfo?["isLaunch"] as? Bool) == true {
            launchUrl = url
        }

        var data: [String: Any] = ["url": url]
        if let source = note.userInfo?["sourceApplication"] as? String, !source.isEmpty {
            data["iosSourceApplication"] = source
        }
        emit("app:urlOpen", data)
    }

    /// Records the lifecycle state and emits `app:stateChange` when it changes.
    private func setState(_ state: String) {
        guard lifecycleState != state else { return }
        lifecycleState = state
        emit("app:stateChange", ["isActive": state == "active", "state": state])
    }
}
