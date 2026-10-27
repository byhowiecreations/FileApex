import AppKit

extension NSApplication {
    /// Lightweight transient toast without notification permissions.
    func showNativeToast(message: String) {
        DispatchQueue.main.async {
            let horizontalPadding: CGFloat = 16
            let verticalPadding: CGFloat = 12
            let screenWidth = NSScreen.main?.visibleFrame.width ?? 600
            let maxTextWidth = min(360, screenWidth - 2 * horizontalPadding - 40)

            let label = NSTextField(wrappingLabelWithString: message)
            label.font = NSFont.systemFont(ofSize: 13, weight: .medium)
            label.alignment = .center
            label.maximumNumberOfLines = 3
            label.lineBreakMode = .byTruncatingTail
            let measured = label.cell?.cellSize(
                forBounds: NSRect(x: 0, y: 0, width: maxTextWidth, height: CGFloat.greatestFiniteMagnitude)
            ) ?? NSSize(width: maxTextWidth, height: 24)
            let textWidth = min(ceil(measured.width), maxTextWidth)
            let textHeight = ceil(measured.height)

            let panel = NSPanel(
                contentRect: NSRect(
                    x: 0, y: 0,
                    width: textWidth + 2 * horizontalPadding,
                    height: textHeight + 2 * verticalPadding
                ),
                styleMask: [.borderless, .nonactivatingPanel],
                backing: .buffered,
                defer: false
            )
            panel.isFloatingPanel = true
            panel.level = .statusBar
            panel.backgroundColor = NSColor.windowBackgroundColor.withAlphaComponent(0.92)
            panel.hasShadow = true
            panel.isOpaque = false

            label.frame = NSRect(x: horizontalPadding, y: verticalPadding, width: textWidth, height: textHeight)
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
