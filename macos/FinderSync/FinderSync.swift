import Cocoa
import FinderSync
import SwiftUI

/// Top-level Finder context menu entry ("OmniNode...") — Blip-style FIFinderSync.
class FinderSync: FIFinderSync {
    private let pickerController = FinderDevicePickerController()

    override init() {
        super.init()
        let home = FileManager.default.homeDirectoryForCurrentUser
        FIFinderSyncController.default().directoryURLs = [home]
        NSLog("OmniNode FinderSync loaded — watching \(home.path)")
    }

    // MARK: - Menu

    override func menu(for menuKind: FIMenuKind) -> NSMenu {
        let menu = NSMenu(title: "OmniNode")
        // Top-level item (same tier as "Blip..." in the Finder context menu).
        let item = NSMenuItem(
            title: "OmniNode...",
            action: #selector(sendWithOmniNode(_:)),
            keyEquivalent: ""
        )
        item.target = self
        menu.addItem(item)
        return menu
    }

    @objc private func sendWithOmniNode(_ sender: Any?) {
        let controller = FIFinderSyncController.default()
        var urls = Array(controller.selectedItemURLs() ?? [])
        if urls.isEmpty, let targeted = controller.targetedURL() {
            urls = [targeted]
        }
        guard !urls.isEmpty else {
            NSLog("OmniNode FinderSync: no selection")
            return
        }
        // Security-scoped access for sandbox reads.
        urls.forEach { _ = $0.startAccessingSecurityScopedResource() }
        pickerController.present(fileURLs: urls) {
            urls.forEach { $0.stopAccessingSecurityScopedResource() }
        }
    }
}

/// Hosts the shared SwiftUI DevicePicker in a small floating panel.
final class FinderDevicePickerController {
    private var window: NSWindow?

    func present(fileURLs: [URL], onDismiss: @escaping () -> Void) {
        DispatchQueue.main.async {
            let model = DevicePickerModel(fileURLs: fileURLs)
            let root = DevicePickerView(model: model, title: "OmniNode...") { [weak self] _ in
                self?.window?.close()
                self?.window = nil
                onDismiss()
            }
            let hosting = NSHostingController(rootView: root)
            let panel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 400, height: 380),
                styleMask: [.titled, .closable, .utilityWindow],
                backing: .buffered,
                defer: false
            )
            panel.title = "OmniNode"
            panel.isFloatingPanel = true
            panel.level = .floating
            panel.contentViewController = hosting
            panel.center()
            panel.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            self.window = panel
        }
    }
}
