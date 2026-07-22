import AppKit
import SwiftUI
import UniformTypeIdentifiers

private enum DropBoxMetrics {
    static let defaultWidth: CGFloat = 362
    static let defaultHeight: CGFloat = 281
    static let minWidth: CGFloat = 242
    static let minHeight: CGFloat = 188
}

public final class DropBoxWindowManager: NSObject, NSWindowDelegate {
    public static let shared = DropBoxWindowManager()

    private var dropBoxWindow: NSPanel?
    private var targetDeviceIds: [String] = []
    private var stagedFilePaths: [String] = []
    private var savedFrame: NSRect?
    private var persistWorkItem: DispatchWorkItem?
    private var isSubmittingSend = false

    public var onSend: ((_ deviceIdsJson: String, _ filePathsJson: String) -> Void)?
    public var onFrameChanged: ((_ x: Double, _ y: Double, _ width: Double, _ height: Double) -> Void)?
    public var onVisibilityChanged: ((Bool) -> Void)?

    var isVisible: Bool {
        dropBoxWindow?.isVisible == true
    }

    private override init() {
        super.init()
    }

    public func seedSavedFrame(x: Double, y: Double, width: Double, height: Double) {
        let clampedWidth = max(DropBoxMetrics.minWidth, min(width, DropBoxMetrics.defaultWidth))
        let clampedHeight = max(DropBoxMetrics.minHeight, min(height, DropBoxMetrics.defaultHeight))
        savedFrame = NSRect(x: x, y: y, width: clampedWidth, height: clampedHeight)
    }

    public func showDropBox(for deviceIds: [String]) {
        targetDeviceIds = deviceIds
        stagedFilePaths = []
        isSubmittingSend = false

        let window = ensureDropBoxWindow()
        window.contentViewController = TrayHostingController(
            rootView: DropBoxContentView(
                targetDeviceCount: deviceIds.count,
                onFilesChanged: { [weak self] paths in
                    self?.stagedFilePaths = paths
                },
                onSend: { [weak self] in
                    self?.submitSend() ?? false
                }
            )
        )

        applyPreferredFrame(to: window)
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        onVisibilityChanged?(true)
    }

    public func closeDropBox() {
        persistFrameImmediately()
        dropBoxWindow?.orderOut(nil)
        stagedFilePaths = []
        isSubmittingSend = false
        onVisibilityChanged?(false)
    }

    private func ensureDropBoxWindow() -> NSPanel {
        if let dropBoxWindow {
            return dropBoxWindow
        }

        let initialFrame = NSRect(
            x: 0,
            y: 0,
            width: DropBoxMetrics.defaultWidth,
            height: DropBoxMetrics.defaultHeight
        )
        let window = NSPanel(
            contentRect: initialFrame,
            styleMask: [.titled, .closable, .resizable, .utilityWindow],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.title = "Drop Files"
        window.isFloatingPanel = true
        window.level = .floating
        window.hidesOnDeactivate = false
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        window.minSize = NSSize(width: DropBoxMetrics.minWidth, height: DropBoxMetrics.minHeight)
        window.delegate = self
        dropBoxWindow = window
        return window
    }

    private func applyPreferredFrame(to window: NSPanel) {
        if let savedFrame, savedFrame.width >= DropBoxMetrics.minWidth,
           savedFrame.height >= DropBoxMetrics.minHeight {
            window.setFrame(savedFrame, display: true)
            return
        }
        window.setContentSize(
            NSSize(width: DropBoxMetrics.defaultWidth, height: DropBoxMetrics.defaultHeight)
        )
        window.center()
    }

    @discardableResult
    private func submitSend() -> Bool {
        guard !isSubmittingSend else { return false }
        guard !targetDeviceIds.isEmpty else { return false }
        let paths = stagedFilePaths
        guard !paths.isEmpty else {
            NSApp.showNativeToast(message: "Drop one or more files first")
            return false
        }

        guard
            let deviceData = try? JSONEncoder().encode(targetDeviceIds),
            let pathData = try? JSONEncoder().encode(paths),
            let deviceJson = String(data: deviceData, encoding: .utf8),
            let pathJson = String(data: pathData, encoding: .utf8)
        else {
            NSApp.showNativeToast(message: "Send failed")
            return false
        }

        isSubmittingSend = true
        onSend?(deviceJson, pathJson)
        return true
    }

    public func windowDidMove(_ notification: Notification) {
        updateLocalSavedFrame()
        schedulePersistFrameToKotlin()
    }

    public func windowDidResize(_ notification: Notification) {
        updateLocalSavedFrame()
        schedulePersistFrameToKotlin()
    }

    public func windowWillClose(_ notification: Notification) {
        persistFrameImmediately()
        onVisibilityChanged?(false)
    }

    private func updateLocalSavedFrame() {
        guard let frame = dropBoxWindow?.frame else { return }
        guard frame.width >= DropBoxMetrics.minWidth, frame.height >= DropBoxMetrics.minHeight else { return }
        savedFrame = NSRect(
            x: frame.origin.x,
            y: frame.origin.y,
            width: min(frame.width, DropBoxMetrics.defaultWidth),
            height: min(frame.height, DropBoxMetrics.defaultHeight)
        )
    }

    private func schedulePersistFrameToKotlin() {
        persistWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.persistFrameImmediately()
        }
        persistWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: work)
    }

    private func persistFrameImmediately() {
        updateLocalSavedFrame()
        guard let savedFrame else { return }
        onFrameChanged?(savedFrame.origin.x, savedFrame.origin.y, savedFrame.width, savedFrame.height)
    }
}

struct DropBoxContentView: View {
    let targetDeviceCount: Int
    let onFilesChanged: ([String]) -> Void
    let onSend: () -> Bool

    @State private var filePaths: [String] = []
    @State private var isTargeted = false
    @State private var isSending = false

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "arrow.down.doc.fill")
                .font(.system(size: 32))
                .foregroundStyle(Color.accentColor)
            Text(filePaths.isEmpty ? "Drag & drop files here" : "\(filePaths.count) file(s) ready")
                .font(.subheadline)
                .bold()
                .multilineTextAlignment(.center)
            Text("\(targetDeviceCount) destination(s)")
                .font(.caption)
                .foregroundStyle(.secondary)

            if !filePaths.isEmpty {
                Button {
                    guard !isSending else { return }
                    isSending = true
                    if !onSend() {
                        isSending = false
                    }
                } label: {
                    Text(isSending ? "Sending" : "Send")
                        .frame(minWidth: 120)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(isSending)
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(16)
        .background(isTargeted ? Color.accentColor.opacity(0.08) : Color.clear)
        .contentShape(Rectangle())
        .onDrop(of: [.fileURL], isTargeted: $isTargeted) { providers in
            ingestProviders(providers)
            return true
        }
    }

    private func appendFiles(_ paths: [String]) {
        let files = paths.filter { !$0.isEmpty && FileManager.default.fileExists(atPath: $0) }
        guard !files.isEmpty else { return }
        filePaths = files
        onFilesChanged(files)
    }

    private func ingestProviders(_ providers: [NSItemProvider]) {
        for provider in providers {
            if provider.canLoadObject(ofClass: URL.self) {
                _ = provider.loadObject(ofClass: URL.self) { item, _ in
                    guard let url = item else { return }
                    DispatchQueue.main.async {
                        appendFiles([url.path])
                    }
                }
                continue
            }
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                let path: String?
                if let url = item as? URL {
                    path = url.path
                } else if let url = item as? NSURL {
                    path = url.path
                } else if let data = item as? Data,
                          let url = URL(dataRepresentation: data, relativeTo: nil) {
                    path = url.path
                } else {
                    path = nil
                }
                guard let path, !path.isEmpty else { return }
                DispatchQueue.main.async {
                    appendFiles([path])
                }
            }
        }
    }
}
