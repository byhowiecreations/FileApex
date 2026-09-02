import AppKit
import SwiftUI
import UniformTypeIdentifiers

private enum DropBoxMetrics {
    static let defaultWidth: CGFloat = 362
    static let defaultHeight: CGFloat = 281
    static let minWidth: CGFloat = 242
    static let minHeight: CGFloat = 188
    static let maxExpandedExtraHeight: CGFloat = 380
}

public final class DropBoxWindowManager: NSObject, NSWindowDelegate {
    public static let shared = DropBoxWindowManager()

    private var dropBoxWindow: NSPanel?
    private var targetDeviceIds: [String] = []
    private var stagedFilePaths: [String] = []
    private var baseUserFrame: NSRect?
    private var isProgrammaticResizing = false
    private var currentFileCount = 0
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
        let clampedWidth = max(DropBoxMetrics.minWidth, width)
        let clampedHeight = max(DropBoxMetrics.minHeight, height)
        baseUserFrame = NSRect(x: x, y: y, width: clampedWidth, height: clampedHeight)
    }

    public func showDropBox(for deviceIds: [String]) {
        targetDeviceIds = deviceIds
        stagedFilePaths = []
        currentFileCount = 0
        isSubmittingSend = false

        let window = ensureDropBoxWindow()
        window.contentViewController = TrayHostingController(
            rootView: DropBoxContentView(
                targetDeviceCount: deviceIds.count,
                onFilesChanged: { [weak self] paths in
                    self?.stagedFilePaths = paths
                    self?.updateWindowHeightForFiles(count: paths.count)
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

    public func relocalize() {
        dropBoxWindow?.title = AppCopy.shared.t("drop_files")
    }

    public func closeDropBox() {
        persistFrameImmediately()
        dropBoxWindow?.orderOut(nil)
        stagedFilePaths = []
        currentFileCount = 0
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
        window.title = AppCopy.shared.t("drop_files")
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
        if let baseUserFrame, baseUserFrame.width >= DropBoxMetrics.minWidth,
           baseUserFrame.height >= DropBoxMetrics.minHeight {
            window.setFrame(baseUserFrame, display: true)
            return
        }
        window.setContentSize(
            NSSize(width: DropBoxMetrics.defaultWidth, height: DropBoxMetrics.defaultHeight)
        )
        window.center()
        baseUserFrame = window.frame
    }

    private func updateWindowHeightForFiles(count: Int) {
        guard let window = dropBoxWindow else { return }
        currentFileCount = count

        let base = baseUserFrame ?? window.frame
        if baseUserFrame == nil {
            baseUserFrame = base
        }

        guard let screen = window.screen ?? NSScreen.main else { return }
        let visibleFrame = screen.visibleFrame

        let extraHeight: CGFloat
        if count == 0 {
            extraHeight = 0
        } else {
            let needed = CGFloat(count) * 28.0 + 20.0
            extraHeight = min(needed, DropBoxMetrics.maxExpandedExtraHeight)
        }

        let targetHeight = max(DropBoxMetrics.minHeight, base.height + extraHeight)
        let heightDelta = targetHeight - base.height

        let spaceAbove = visibleFrame.maxY - (base.origin.y + base.height)
        let spaceBelow = base.origin.y - visibleFrame.minY

        var targetY = base.origin.y
        if heightDelta > 0 {
            if spaceAbove >= spaceBelow {
                targetY = base.origin.y
                if targetY + targetHeight > visibleFrame.maxY - 10 {
                    targetY = max(visibleFrame.minY + 10, visibleFrame.maxY - 10 - targetHeight)
                }
            } else {
                let topY = base.origin.y + base.height
                targetY = topY - targetHeight
                if targetY < visibleFrame.minY + 10 {
                    targetY = min(visibleFrame.maxY - 10 - targetHeight, visibleFrame.minY + 10)
                }
            }
        } else {
            targetY = base.origin.y
            if targetY < visibleFrame.minY + 10 {
                targetY = visibleFrame.minY + 10
            } else if targetY + base.height > visibleFrame.maxY - 10 {
                targetY = max(visibleFrame.minY + 10, visibleFrame.maxY - 10 - base.height)
            }
        }

        let targetFrame = NSRect(x: base.origin.x, y: targetY, width: base.width, height: targetHeight)
        guard targetFrame != window.frame else { return }

        isProgrammaticResizing = true
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.22
            context.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            window.animator().setFrame(targetFrame, display: true)
        }, completionHandler: { [weak self] in
            self?.isProgrammaticResizing = false
        })
    }

    @discardableResult
    private func submitSend() -> Bool {
        guard !isSubmittingSend else { return false }
        guard !targetDeviceIds.isEmpty else { return false }
        let paths = stagedFilePaths
        guard !paths.isEmpty else {
            NSApp.showNativeToast(message: AppCopy.shared.t("drop_files_first"))
            return false
        }

        guard
            let deviceData = try? JSONEncoder().encode(targetDeviceIds),
            let pathData = try? JSONEncoder().encode(paths),
            let deviceJson = String(data: deviceData, encoding: .utf8),
            let pathJson = String(data: pathData, encoding: .utf8)
        else {
            NSApp.showNativeToast(message: AppCopy.shared.t("send_failed"))
            return false
        }

        isSubmittingSend = true
        onSend?(deviceJson, pathJson)
        return true
    }

    public func windowDidMove(_ notification: Notification) {
        guard !isProgrammaticResizing else { return }
        updateLocalSavedFrame()
        schedulePersistFrameToKotlin()
    }

    public func windowDidResize(_ notification: Notification) {
        guard !isProgrammaticResizing else { return }
        updateLocalSavedFrame()
        schedulePersistFrameToKotlin()
    }

    public func windowWillClose(_ notification: Notification) {
        persistFrameImmediately()
        onVisibilityChanged?(false)
    }

    private func updateLocalSavedFrame() {
        guard !isProgrammaticResizing else { return }
        guard let frame = dropBoxWindow?.frame else { return }
        guard frame.width >= DropBoxMetrics.minWidth, frame.height >= DropBoxMetrics.minHeight else { return }
        if currentFileCount == 0 {
            baseUserFrame = frame
        } else if let base = baseUserFrame {
            let extraHeight = max(0, frame.height - base.height)
            let baseH = max(DropBoxMetrics.minHeight, frame.height - extraHeight)
            baseUserFrame = NSRect(x: frame.origin.x, y: frame.origin.y, width: frame.width, height: baseH)
        } else {
            baseUserFrame = frame
        }
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
        guard let base = baseUserFrame else { return }
        onFrameChanged?(base.origin.x, base.origin.y, base.width, base.height)
    }
}

final class MacDropTargetView: NSView {
    var onPathsDropped: (([String]) -> Void)?
    var onDragTargeted: ((Bool) -> Void)?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        registerForDraggedTypes([.fileURL])
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        let pboard = sender.draggingPasteboard
        guard let urls = pboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL], !urls.isEmpty else {
            return []
        }
        onDragTargeted?(true)
        return .copy
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        onDragTargeted?(false)
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        onDragTargeted?(false)
        let pboard = sender.draggingPasteboard
        guard let urls = pboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL], !urls.isEmpty else {
            return false
        }
        let paths = urls.map { $0.path }
        onPathsDropped?(paths)
        return true
    }
}

struct DropTargetRepresentable: NSViewRepresentable {
    let onPathsDropped: ([String]) -> Void
    let onDragTargeted: (Bool) -> Void

    func makeNSView(context: Context) -> MacDropTargetView {
        let view = MacDropTargetView()
        view.onPathsDropped = onPathsDropped
        view.onDragTargeted = onDragTargeted
        return view
    }

    func updateNSView(_ nsView: MacDropTargetView, context: Context) {
        nsView.onPathsDropped = onPathsDropped
        nsView.onDragTargeted = onDragTargeted
    }
}

struct DropBoxContentView: View {
    let targetDeviceCount: Int
    let onFilesChanged: ([String]) -> Void
    let onSend: () -> Bool

    @ObservedObject private var copy = AppCopy.shared
    @State private var filePaths: [String] = []
    @State private var isTargeted = false
    @State private var isSending = false

    var body: some View {
        ZStack {
            DropTargetRepresentable(
                onPathsDropped: { paths in
                    appendFiles(paths)
                },
                onDragTargeted: { targeted in
                    isTargeted = targeted
                }
            )

            VStack(spacing: 12) {
                Image(systemName: "arrow.down.doc.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.accentColor)

                if filePaths.isEmpty {
                    Text(copy.t("drag_drop_files_here"))
                        .font(.subheadline)
                        .bold()
                        .multilineTextAlignment(.center)
                } else {
                    Text(copy.plural("n_files_ready", count: filePaths.count))
                        .font(.subheadline)
                        .bold()
                        .multilineTextAlignment(.center)

                    ScrollView {
                        VStack(alignment: .leading, spacing: 4) {
                            ForEach(filePaths, id: \.self) { path in
                                HStack(spacing: 6) {
                                    Image(systemName: isDirectory(path) ? "folder.fill" : "doc.fill")
                                        .font(.caption)
                                        .foregroundStyle(isDirectory(path) ? Color.blue : Color.secondary)
                                    Text(URL(fileURLWithPath: path).lastPathComponent)
                                        .font(.caption)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                    Spacer()
                                    Button {
                                        removeFile(path)
                                    } label: {
                                        Image(systemName: "xmark.circle.fill")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                    .buttonStyle(.plain)
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(RoundedRectangle(cornerRadius: 4).fill(Color.primary.opacity(0.04)))
                            }
                        }
                        .padding(.horizontal, 2)
                    }
                    .frame(maxWidth: .infinity)
                }

                Text(copy.plural("n_destinations", count: targetDeviceCount))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if !filePaths.isEmpty {
                    HStack(spacing: 10) {
                        Button(role: .cancel) {
                            clearFiles()
                        } label: {
                            Text(copy.t("cancel"))
                                .frame(minWidth: 60)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.regular)
                        .disabled(isSending)

                        Button {
                            guard !isSending else { return }
                            isSending = true
                            if !onSend() {
                                isSending = false
                            }
                        } label: {
                            Text(isSending ? copy.t("sending_short") : copy.t("send"))
                                .frame(minWidth: 80)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                        .disabled(isSending)
                    }
                    .padding(.top, 2)
                }
            }
            .padding(14)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(isTargeted ? Color.accentColor : Color.clear, lineWidth: 2)
                .background(isTargeted ? Color.accentColor.opacity(0.12) : Color.clear)
        )
    }

    private func isDirectory(_ path: String) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: path, isDirectory: &isDir) && isDir.boolValue
    }

    private func appendFiles(_ paths: [String]) {
        let valid = paths.filter { !$0.isEmpty && FileManager.default.fileExists(atPath: $0) }
        guard !valid.isEmpty else { return }
        var updated = filePaths
        for p in valid {
            if !updated.contains(p) {
                updated.append(p)
            }
        }
        filePaths = updated
        onFilesChanged(updated)
    }

    private func removeFile(_ path: String) {
        filePaths.removeAll { $0 == path }
        onFilesChanged(filePaths)
    }

    private func clearFiles() {
        filePaths.removeAll()
        onFilesChanged([])
    }
}
