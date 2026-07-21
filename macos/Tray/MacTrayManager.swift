import AppKit
import SwiftUI

@objc public final class MacTrayManager: NSObject, NSWindowDelegate, NSPopoverDelegate {
    public static let shared = MacTrayManager()

    private var statusItem: NSStatusItem?
    private var popover: NSPopover?
    private weak var mainWindow: NSWindow?
    private var mainWindowBound = false
    private var trayInstalled = false
    private var globalClickMonitor: Any?
    private var appIconPath: String?

    public var onPopoverVisibilityChanged: ((Bool) -> Void)?
    public var onQuitRequested: (() -> Void)?
    public var onShowMainWindow: (() -> Void)?
    public var onRefreshDevices: (() -> Void)?
    public var onPrepareDropBox: (() -> Void)?

    private override init() {
        super.init()
    }

    public func ensureTrayInstalled() {
        guard !trayInstalled else { return }
        trayInstalled = true

        NSApp.setActivationPolicy(.regular)

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem?.button {
            if let icon = loadStatusBarIcon() {
                button.image = icon
            } else {
                button.title = "ON"
            }
            button.action = #selector(handleTrayClick(_:))
            button.target = self
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }

        popover = NSPopover()
        popover?.behavior = .transient
        popover?.animates = true
        popover?.delegate = self

        let trayView = TrayMenuView(
            onLaunchApp: { [weak self] in
                self?.closePopover()
                self?.showMainWindow()
            },
            onQuitApp: { [weak self] in
                self?.closePopover()
                self?.requestQuit()
            },
            onOpenDropBox: { [weak self] deviceIds in
                self?.closePopover()
                self?.onPrepareDropBox?()
                DropBoxWindowManager.shared.showDropBox(for: deviceIds)
            },
            onRefreshDevices: { [weak self] in
                self?.onRefreshDevices?()
            }
        )
        popover?.contentViewController = TrayHostingController(rootView: trayView)
        NSLog("OmniNode MacTrayManager: status item installed")
    }

    public func bindMainWindow(_ candidate: NSWindow) {
        guard isValidMainWindow(candidate) else {
            NSLog("OmniNode MacTrayManager: bind rejected — pointer is not an NSWindow")
            return
        }
        mainWindow = candidate
        mainWindowBound = true
        candidate.delegate = self
        NSLog("OmniNode MacTrayManager: bound main NSWindow")
    }

    public var hasBoundMainWindow: Bool {
        mainWindowBound
    }

    public func hideMainWindow() {
        guard let window = resolvedMainWindow(), isValidMainWindow(window) else {
            return
        }
        window.orderOut(nil)
    }

    public func setAppIconPath(_ path: String) {
        appIconPath = path
        if trayInstalled, let button = statusItem?.button, let icon = loadStatusBarIcon() {
            button.image = icon
        }
    }

    private func isValidMainWindow(_ window: NSWindow) -> Bool {
        window.isKind(of: NSWindow.self) && window.responds(to: #selector(NSWindow.orderOut(_:)))
    }

    private func loadStatusBarIcon() -> NSImage? {
        if let appIconPath, let image = NSImage(contentsOfFile: appIconPath) {
            return resizedStatusBarIcon(image)
        }
        if let url = resolveAppIconURL(), let image = NSImage(contentsOf: url) {
            return resizedStatusBarIcon(image)
        }
        if let appIcon = NSApp.applicationIconImage {
            return resizedStatusBarIcon(appIcon)
        }
        return nil
    }

    private func resolveAppIconURL() -> URL? {
        let bundle = Bundle.main
        if let url = bundle.url(forResource: "OmniNode", withExtension: "icns") {
            return url
        }
        let resourceCandidate = bundle.resourceURL?.appendingPathComponent("OmniNode.icns")
        if let resourceCandidate, FileManager.default.fileExists(atPath: resourceCandidate.path) {
            return resourceCandidate
        }
        if let executable = bundle.executableURL?
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Resources/OmniNode.icns"),
           FileManager.default.fileExists(atPath: executable.path) {
            return executable
        }
        return nil
    }

    private func resizedStatusBarIcon(_ image: NSImage) -> NSImage {
        let side = max(16, NSStatusBar.system.thickness - 4)
        let sized = NSImage(size: NSSize(width: side, height: side))
        sized.lockFocus()
        image.draw(
            in: NSRect(x: 0, y: 0, width: side, height: side),
            from: NSRect(origin: .zero, size: image.size),
            operation: .sourceOver,
            fraction: 1.0
        )
        sized.unlockFocus()
        sized.isTemplate = false
        return sized
    }

    @objc private func handleTrayClick(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else { return }

        if event.type == .rightMouseUp {
            closePopover()
            showRightClickMenu(around: sender)
        } else if popover?.isShown == true {
            closePopover()
        } else {
            showPopover(relativeTo: sender)
        }
    }

    private func showPopover(relativeTo sender: NSStatusBarButton) {
        guard let popover else { return }
        NSApp.activate(ignoringOtherApps: true)
        popover.show(relativeTo: sender.bounds, of: sender, preferredEdge: .minY)
        popover.contentViewController?.view.window?.makeKey()
    }

    private func closePopover() {
        guard popover?.isShown == true else { return }
        popover?.performClose(nil)
    }

    private func showRightClickMenu(around button: NSStatusBarButton) {
        let menu = NSMenu()
        let showItem = NSMenuItem(
            title: "Show OmniNode",
            action: #selector(showMainWindowFromMenu(_:)),
            keyEquivalent: ""
        )
        showItem.target = self
        menu.addItem(showItem)
        menu.addItem(NSMenuItem.separator())
        let quitItem = NSMenuItem(title: "Quit", action: #selector(quitAppFromMenu(_:)), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem?.menu = menu
        button.performClick(nil)
        statusItem?.menu = nil
    }

    @objc public func showMainWindow() {
        if let window = resolvedMainWindow(), isValidMainWindow(window) {
            window.makeKeyAndOrderFront(nil)
        }
        NSApp.activate(ignoringOtherApps: true)
        onShowMainWindow?()
    }

    @objc private func showMainWindowFromMenu(_ sender: NSMenuItem) {
        showMainWindow()
    }

    @objc private func quitAppFromMenu(_ sender: NSMenuItem) {
        requestQuit()
    }

    private func requestQuit() {
        onQuitRequested?()
    }

    private func resolvedMainWindow() -> NSWindow? {
        if let mainWindow, isValidMainWindow(mainWindow) {
            return mainWindow
        }
        return NSApp.windows.first { window in
            window.title == "OmniNode" && window.canBecomeKey && isValidMainWindow(window)
        }
    }

    public func windowShouldClose(_ sender: NSWindow) -> Bool {
        guard isValidMainWindow(sender) else {
            return true
        }
        sender.orderOut(nil)
        return false
    }

    public func popoverWillShow(_ notification: Notification) {
        onPopoverVisibilityChanged?(true)
        installOutsideClickMonitor()
    }

    public func popoverDidClose(_ notification: Notification) {
        removeOutsideClickMonitor()
        onPopoverVisibilityChanged?(false)
    }

    private func installOutsideClickMonitor() {
        removeOutsideClickMonitor()
        globalClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
            DispatchQueue.main.async {
                self?.closePopover()
            }
        }
    }

    private func removeOutsideClickMonitor() {
        if let globalClickMonitor {
            NSEvent.removeMonitor(globalClickMonitor)
            self.globalClickMonitor = nil
        }
    }
}

/// Popover hosting that activates the app so SwiftUI tap targets receive events under the JVM.
final class TrayHostingController<Content: View>: NSHostingController<Content> {
    override func loadView() {
        super.loadView()
        view.wantsLayer = true
    }

    override func mouseDown(with event: NSEvent) {
        NSApp.activate(ignoringOtherApps: true)
        view.window?.makeKey()
        super.mouseDown(with: event)
    }
}
