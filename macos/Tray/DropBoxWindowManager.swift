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

final class DropBoxPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }

    override func layoutIfNeeded() {
        super.layoutIfNeeded()
        adjustTrafficLights()
    }

    override func setFrame(_ frameRect: NSRect, display flag: Bool) {
        super.setFrame(frameRect, display: flag)
        adjustTrafficLights()
    }

    override func becomeKey() {
        super.becomeKey()
        adjustTrafficLights()
    }

    func adjustTrafficLights() {
        guard let closeBtn = standardWindowButton(.closeButton) else { return }
        let targetX: CGFloat = 18.0
        let currentX = closeBtn.frame.origin.x
        if abs(currentX - targetX) > 0.5 {
            let delta = targetX - currentX
            closeBtn.frame.origin.x = targetX
            if let minBtn = standardWindowButton(.miniaturizeButton) {
                minBtn.frame.origin.x += delta
            }
            if let zoomBtn = standardWindowButton(.zoomButton) {
                zoomBtn.frame.origin.x += delta
            }
        }
    }
}

final class DropBoxState: ObservableObject {
    @Published var targetDeviceCount: Int = 0
    @Published var singleDeviceName: String? = nil
    @Published var filePaths: [String] = []
    @Published var isSending: Bool = false
    @Published var isPinned: Bool = false

    func clear() {
        filePaths = []
        isSending = false
    }
}

public final class DropBoxWindowManager: NSObject, NSWindowDelegate {
    public static let shared = DropBoxWindowManager()

    private var dropBoxWindow: DropBoxPanel?
    private let state = DropBoxState()
    private var isPinned = false
    private var pinAccessoryItem: NSTitlebarAccessoryViewController?
    private var pinButton: NSButton?
    private var targetDeviceIds: [String] = []
    private var stagedFilePaths: [String] = []
    private var baseUserFrame: NSRect?
    private var isProgrammaticResizing = false
    private var currentFileCount = 0
    private var persistWorkItem: DispatchWorkItem?
    private var isSubmittingSend = false

    public var onSend: ((_ deviceIdsJson: String, _ filePathsJson: String) -> Void)?
    public var onCancelSend: (() -> Void)?
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

        let singleName: String? = (deviceIds.count == 1)
            ? TrayDeviceBridge.shared.devices.first(where: { $0.deviceId == deviceIds[0] })?.name
            : nil

        state.targetDeviceCount = deviceIds.count
        state.singleDeviceName = singleName
        state.clear()
        state.isPinned = isPinned

        let window = ensureDropBoxWindow()
        setupTitlebarAccessory(on: window)
        window.contentViewController = TrayHostingController(
            rootView: DropBoxContentView(
                state: state,
                onFilesChanged: { [weak self] paths in
                    self?.stagedFilePaths = paths
                    self?.updateWindowHeightForFiles(count: paths.count)
                },
                onSend: { [weak self] in
                    self?.submitSend() ?? false
                },
                onCancelSend: { [weak self] in
                    self?.onCancelSend?()
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
        if let btn = pinButton {
            updatePinButton(btn)
        }
    }

    public func closeDropBox() {
        if isPinned {
            clearStagedFilesAndReset()
            return
        }
        persistFrameImmediately()
        dropBoxWindow?.orderOut(nil)
        clearStagedFilesAndReset()
        onVisibilityChanged?(false)
    }

    private func clearStagedFilesAndReset() {
        stagedFilePaths = []
        currentFileCount = 0
        isSubmittingSend = false
        state.clear()
        updateWindowHeightForFiles(count: 0)
    }

    private func ensureDropBoxWindow() -> DropBoxPanel {
        if let dropBoxWindow {
            return dropBoxWindow
        }

        let initialFrame = NSRect(
            x: 0,
            y: 0,
            width: DropBoxMetrics.defaultWidth,
            height: DropBoxMetrics.defaultHeight
        )
        let window = DropBoxPanel(
            contentRect: initialFrame,
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.appearance = NSAppearance(named: .darkAqua)
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.backgroundColor = NSColor(deviceRed: 0.105, green: 0.135, blue: 0.19, alpha: 1)
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

    private func setupTitlebarAccessory(on window: NSWindow) {
        if pinAccessoryItem != nil { return }
        let accessory = NSTitlebarAccessoryViewController()
        accessory.layoutAttribute = .trailing

        let btn = NSButton(frame: NSRect(x: 0, y: 0, width: 28, height: 22))
        btn.bezelStyle = .texturedRounded
        btn.isBordered = false
        btn.target = self
        btn.action = #selector(togglePin)
        btn.focusRingType = .none
        btn.imageScaling = .scaleProportionallyDown
        updatePinButton(btn)

        accessory.view = btn
        window.addTitlebarAccessoryViewController(accessory)
        pinAccessoryItem = accessory
        pinButton = btn
    }

    @objc private func togglePin() {
        isPinned.toggle()
        state.isPinned = isPinned
        if let btn = pinButton {
            updatePinButton(btn)
        }
    }

    private func updatePinButton(_ btn: NSButton) {
        let symbolName = isPinned ? "pin.fill" : "pin"
        let config = NSImage.SymbolConfiguration(pointSize: 12, weight: .medium)
        let desc = isPinned ? AppCopy.shared.t("unpin_drop_box") : AppCopy.shared.t("pin_drop_box")
        btn.image = NSImage(systemSymbolName: symbolName, accessibilityDescription: desc)?.withSymbolConfiguration(config)
        if isPinned {
            btn.contentTintColor = .controlAccentColor
        } else {
            btn.contentTintColor = .secondaryLabelColor
        }
        btn.toolTip = desc
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

    public func windowShouldClose(_ sender: NSWindow) -> Bool {
        if isPinned {
            NSApp.showNativeToast(message: AppCopy.shared.t("unpin_to_close"))
            return false
        }
        return true
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
    @ObservedObject var state: DropBoxState
    let onFilesChanged: ([String]) -> Void
    let onSend: () -> Bool
    let onCancelSend: () -> Void

    @ObservedObject private var copy = AppCopy.shared
    @State private var isTargeted = false

    private let accent = Color(red: 0.0, green: 0.9, blue: 0.46)

    var body: some View {
        VStack(spacing: 14) {
            header
            dropZone
            footer
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            LinearGradient(
                colors: [Color(red: 0.105, green: 0.135, blue: 0.19), Color(red: 0.075, green: 0.09, blue: 0.12)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .environment(\.colorScheme, .dark)
    }

    private var destinationText: String {
        if state.targetDeviceCount == 1, let name = state.singleDeviceName, !name.isEmpty {
            return name
        }
        return copy.plural("n_destinations", count: state.targetDeviceCount)
    }

    private var header: some View {
        HStack {
            HStack(spacing: 8) {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(accent)
                Text(destinationText)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(accent.opacity(0.14)))
            .help(copy.t("dropbox_destination_tip"))
            Spacer()
        }
    }

    private var dropZone: some View {
        ZStack {
            DropTargetRepresentable(
                onPathsDropped: { paths in
                    appendFiles(paths)
                },
                onDragTargeted: { targeted in
                    isTargeted = targeted
                }
            )
            if state.filePaths.isEmpty {
                emptyState
            } else {
                fileList
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 18)
                .fill(isTargeted ? accent.opacity(0.10) : Color.white.opacity(0.04))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .strokeBorder(
                    isTargeted ? accent : Color.white.opacity(0.28),
                    style: StrokeStyle(lineWidth: 2, dash: [8, 6])
                )
        )
        .help(copy.t("dropbox_zone_tip"))
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "arrow.down.doc.fill")
                .font(.system(size: 32))
                .foregroundStyle(accent)
                .frame(width: 72, height: 72)
                .background(Circle().fill(accent.opacity(isTargeted ? 0.25 : 0.14)))
            Text(copy.t("drag_drop_files_here"))
                .font(.system(size: 15, weight: .bold))
                .multilineTextAlignment(.center)
        }
        .padding(14)
    }

    private var fileList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(copy.plural("n_files_ready", count: state.filePaths.count))
                .font(.system(size: 13, weight: .semibold))
                .padding(.leading, 4)
            ScrollView {
                VStack(spacing: 6) {
                    ForEach(state.filePaths, id: \.self) { path in
                        fileRow(path)
                    }
                }
            }
            if state.isSending {
                ProgressView()
                    .progressViewStyle(.linear)
                    .tint(accent)
            }
        }
        .padding(12)
    }

    private func fileRow(_ path: String) -> some View {
        let folder = isDirectory(path)
        return HStack(spacing: 10) {
            Image(systemName: folder ? "folder.fill" : "doc.fill")
                .font(.system(size: 14))
                .foregroundStyle(folder ? Color.blue : Color.secondary)
                .frame(width: 30, height: 30)
                .background(RoundedRectangle(cornerRadius: 8).fill(folder ? Color.blue.opacity(0.18) : Color.white.opacity(0.08)))
            Text(URL(fileURLWithPath: path).lastPathComponent)
                .font(.system(size: 13))
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            Button {
                removeFile(path)
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 26, height: 26)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .help(copy.t("dropbox_remove_tip"))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.06)))
    }

    private var footer: some View {
        HStack(spacing: 10) {
            Button {
                if state.isSending {
                    onCancelSend()
                } else {
                    clearFiles()
                }
            } label: {
                Text(copy.t("cancel"))
                    .font(.system(size: 13))
                    .frame(width: 96, height: 38)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.2)))
                    .contentShape(RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
            .help(copy.t("dropbox_cancel_tip"))

            if !state.filePaths.isEmpty {
                Button {
                    guard !state.isSending else { return }
                    state.isSending = true
                    if !onSend() {
                        state.isSending = false
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "paperplane.fill")
                            .font(.system(size: 13))
                        Text(state.isSending ? copy.t("sending_short") : copy.t("send"))
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 38)
                    .foregroundStyle(.white)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(red: 0.06, green: 0.46, blue: 0.43)))
                    .opacity(state.isSending ? 0.6 : 1)
                    .contentShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(state.isSending)
                .help(copy.t("dropbox_send_tip"))
            }
        }
    }

    private func isDirectory(_ path: String) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: path, isDirectory: &isDir) && isDir.boolValue
    }

    private func appendFiles(_ paths: [String]) {
        let valid = paths.filter { !$0.isEmpty && FileManager.default.fileExists(atPath: $0) }
        guard !valid.isEmpty else { return }
        var updated = state.filePaths
        for p in valid {
            if !updated.contains(p) {
                updated.append(p)
            }
        }
        state.filePaths = updated
        onFilesChanged(updated)
    }

    private func removeFile(_ path: String) {
        state.filePaths.removeAll { $0 == path }
        onFilesChanged(state.filePaths)
    }

    private func clearFiles() {
        state.filePaths.removeAll()
        onFilesChanged([])
    }
}
