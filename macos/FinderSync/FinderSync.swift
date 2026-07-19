import Cocoa
import FinderSync
import SwiftUI

/// Top-level Finder context menu entry ("OmniNode...") — Blip-style FIFinderSync.
/// Principal class is OmniNodeFinderSync.FinderSync (see Info.plist).
@objc(FinderSync)
final class FinderSync: FIFinderSync {
    private let pickerController = FinderDevicePickerController()

    override init() {
        super.init()
        // Defer directory registration — Finder Sync can ignore roots set too early in init.
        DispatchQueue.main.async { [weak self] in
            self?.configureWatchedDirectories()
        }
    }

    private func configureWatchedDirectories() {
        let home = OmniNodePaths.realUserHomeDirectory
        let roots: Set<URL> = [
            home,
            home.appendingPathComponent("Desktop", isDirectory: true),
            home.appendingPathComponent("Downloads", isDirectory: true),
            home.appendingPathComponent("Documents", isDirectory: true),
            URL(fileURLWithPath: "/Volumes", isDirectory: true),
            URL(fileURLWithPath: "/", isDirectory: true)
        ]
        FIFinderSyncController.default().directoryURLs = roots
        NSLog("OmniNode FinderSync loaded — roots=\(roots.map(\.path))")
    }

    // MARK: - Menu

    override func menu(for menuKind: FIMenuKind) -> NSMenu {
        // Only inject into item/container context menus (not toolbar).
        switch menuKind {
        case .contextualMenuForItems, .contextualMenuForContainer, .contextualMenuForSidebar:
            break
        @unknown default:
            return NSMenu(title: "")
        }

        let menu = NSMenu(title: "OmniNode")
        // Do NOT set item.target — Finder Sync invokes the action on the principal class.
        menu.addItem(
            withTitle: "OmniNode...",
            action: #selector(sendWithOmniNode(_:)),
            keyEquivalent: ""
        )
        return menu
    }

    @IBAction func sendWithOmniNode(_ sender: AnyObject?) {
        NSLog("OmniNode FinderSync: menu action fired")
        // Capture immediately — still near the Finder selection / context-menu click.
        let anchor = NSEvent.mouseLocation
        let controller = FIFinderSyncController.default()
        var urls = Array(controller.selectedItemURLs() ?? [])
        if urls.isEmpty, let targeted = controller.targetedURL() {
            urls = [targeted]
        }
        guard !urls.isEmpty else {
            NSLog("OmniNode FinderSync: no selection")
            return
        }
        NSLog("OmniNode FinderSync: presenting picker for \(urls.count) item(s) near \(anchor)")
        urls.forEach { _ = $0.startAccessingSecurityScopedResource() }
        pickerController.present(fileURLs: urls, near: anchor) {
            urls.forEach { $0.stopAccessingSecurityScopedResource() }
        }
    }
}

/// Hosts the shared SwiftUI DevicePicker in a small floating panel.
final class FinderDevicePickerController {
    private var window: NSWindow?
    private let panelSize = NSSize(width: 400, height: 380)

    func present(fileURLs: [URL], near anchor: NSPoint, onDismiss: @escaping () -> Void) {
        DispatchQueue.main.async {
            NSApp.setActivationPolicy(.accessory)
            NSApp.activate(ignoringOtherApps: true)

            let model = DevicePickerModel(fileURLs: fileURLs)
            let root = DevicePickerView(model: model, title: "OmniNode...") { [weak self] _ in
                self?.window?.close()
                self?.window = nil
                onDismiss()
            }
            let hosting = NSHostingController(rootView: root)
            let panel = NSPanel(
                contentRect: NSRect(origin: .zero, size: self.panelSize),
                styleMask: [.titled, .closable, .utilityWindow],
                backing: .buffered,
                defer: false
            )
            panel.title = "OmniNode"
            panel.isFloatingPanel = true
            panel.level = .floating
            panel.hidesOnDeactivate = false
            panel.contentViewController = hosting
            panel.setFrameOrigin(Self.origin(for: self.panelSize, near: anchor))
            panel.makeKeyAndOrderFront(nil)
            panel.orderFrontRegardless()
            NSApp.activate(ignoringOtherApps: true)
            self.window = panel
            NSLog("OmniNode FinderSync: panel at \(panel.frame.origin)")
        }
    }

    /// Places the panel just below/right of the click, clamped to the screen that contains the anchor.
    private static func origin(for size: NSSize, near anchor: NSPoint) -> NSPoint {
        let screen = NSScreen.screens.first { NSMouseInRect(anchor, $0.frame, false) }
            ?? NSScreen.main
            ?? NSScreen.screens.first
        let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1280, height: 800)

        // Offset slightly so the panel doesn't cover the clicked icon.
        var origin = NSPoint(x: anchor.x + 12, y: anchor.y - size.height - 12)

        if origin.x + size.width > visible.maxX {
            origin.x = visible.maxX - size.width - 8
        }
        if origin.x < visible.minX {
            origin.x = visible.minX + 8
        }
        if origin.y < visible.minY {
            // Flip above the click if it would go off the bottom.
            origin.y = min(anchor.y + 12, visible.maxY - size.height - 8)
        }
        if origin.y + size.height > visible.maxY {
            origin.y = visible.maxY - size.height - 8
        }
        return origin
    }
}
