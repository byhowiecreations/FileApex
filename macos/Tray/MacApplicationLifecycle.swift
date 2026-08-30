import AppKit

// Chain ahead of Skiko's delegate — Java posts Quit AppleEvent after hide; must cancel terminate.
@objc final class MacApplicationLifecycle: NSObject, NSApplicationDelegate {
    static let shared = MacApplicationLifecycle()

    private weak var trayManager: MacTrayManager?
    private weak var upstreamDelegate: NSApplicationDelegate?
    private var allowTerminate = false

    private override init() {
        super.init()
    }

    func install(trayManager: MacTrayManager) {
        self.trayManager = trayManager
        NSApp.setActivationPolicy(.regular)
        if NSApp.delegate === self {
            return
        }
        upstreamDelegate = NSApp.delegate
        NSApp.delegate = self
        NSLog(
            "FileApex MacApplicationLifecycle: delegate chained (upstream=%@)",
            String(describing: upstreamDelegate)
        )
    }

    func permitTerminateForQuit() {
        allowTerminate = true
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if allowTerminate {
            NSLog("FileApex MacApplicationLifecycle: applicationShouldTerminate -> NOW (explicit quit)")
            return .terminateNow
        }
        NSLog("FileApex MacApplicationLifecycle: applicationShouldTerminate -> CANCEL (tray mode)")
        return .terminateCancel
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        NSLog("FileApex MacApplicationLifecycle: applicationShouldTerminateAfterLastWindowClosed -> false")
        return false
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        trayManager?.handleDockReopen(hasVisibleWindows: flag) ?? false
    }

    override func responds(to aSelector: Selector!) -> Bool {
        if aSelector == #selector(NSApplicationDelegate.applicationShouldTerminate(_:)) {
            return true
        }
        if super.responds(to: aSelector) {
            return true
        }
        return upstreamDelegate?.responds(to: aSelector) ?? false
    }

    override func forwardingTarget(for aSelector: Selector!) -> Any? {
        if aSelector == #selector(NSApplicationDelegate.applicationShouldTerminate(_:)) {
            return nil
        }
        if upstreamDelegate?.responds(to: aSelector) == true {
            return upstreamDelegate
        }
        return super.forwardingTarget(for: aSelector)
    }
}
