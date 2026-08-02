import AppKit

extension NSApplication {
    /// Lightweight transient toast without notification permissions.
    func showNativeToast(message: String) {
        DispatchQueue.main.async {
            let panel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 220, height: 44),
                styleMask: [.borderless, .nonactivatingPanel],
                backing: .buffered,
                defer: false
            )
            panel.isFloatingPanel = true
            panel.level = .statusBar
            panel.backgroundColor = NSColor.windowBackgroundColor.withAlphaComponent(0.92)
            panel.hasShadow = true
            panel.isOpaque = false

            let label = NSTextField(labelWithString: message)
            label.font = NSFont.systemFont(ofSize: 13, weight: .medium)
            label.alignment = .center
            label.frame = NSRect(x: 12, y: 10, width: 196, height: 24)
            panel.contentView?.addSubview(label)

            if let screen = NSScreen.main {
                let frame = panel.frame
                let x = screen.visibleFrame.midX - frame.width / 2
                let y = screen.visibleFrame.minY + 48
                panel.setFrameOrigin(NSPoint(x: x, y: y))
            }

            panel.orderFrontRegardless()
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                panel.orderOut(nil)
            }
        }
    }
}
