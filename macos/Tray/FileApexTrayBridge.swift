import AppKit
import Foundation
import UniformTypeIdentifiers

public typealias FileApexSendCallback = @convention(c) (UnsafePointer<CChar>?, UnsafePointer<CChar>?) -> Void
public typealias FileApexBoolCallback = @convention(c) (Bool) -> Void
public typealias FileApexVoidCallback = @convention(c) () -> Void
public typealias FileApexSaveDropBoxFrameCallback = @convention(c) (Double, Double, Double, Double) -> Void
public typealias FileApexClipboardCallback = @convention(c) (UnsafePointer<CChar>?) -> Void

private var sendCallback: FileApexSendCallback?
private var popoverCallback: FileApexBoolCallback?
private var quitCallback: FileApexVoidCallback?
private var showMainWindowCallback: FileApexVoidCallback?
private var dropBoxVisibilityCallback: FileApexBoolCallback?
private var saveDropBoxFrameCallback: FileApexSaveDropBoxFrameCallback?
private var refreshDevicesCallback: FileApexVoidCallback?
private var prepareDropBoxCallback: FileApexVoidCallback?
private var backgroundActivityToken: NSObjectProtocol?
private var clipboardCallback: FileApexClipboardCallback?
private var clipboardTimer: Timer?
private var lastPasteboardChangeCount = -1

private func onMainThread(_ block: @escaping () -> Void) {
    if Thread.isMainThread {
        block()
    } else {
        DispatchQueue.main.sync(execute: block)
    }
}

private func wireTrayManagerCallbacks() {
    MacTrayManager.shared.onPopoverVisibilityChanged = { visible in
        popoverCallback?(visible)
    }
    MacTrayManager.shared.onQuitRequested = {
        quitCallback?()
    }
    MacTrayManager.shared.onShowMainWindow = {
        showMainWindowCallback?()
    }
    MacTrayManager.shared.onRefreshDevices = {
        refreshDevicesCallback?()
    }
    MacTrayManager.shared.onPrepareDropBox = {
        prepareDropBoxCallback?()
    }
}

@_cdecl("fileapex_tray_register_callbacks")
public func fileapex_tray_register_callbacks(
    send: FileApexSendCallback?,
    popoverVisible: FileApexBoolCallback?,
    quit: FileApexVoidCallback?,
    showMainWindow: FileApexVoidCallback?
) {
    sendCallback = send
    popoverCallback = popoverVisible
    quitCallback = quit
    showMainWindowCallback = showMainWindow

    DropBoxWindowManager.shared.onSend = { deviceIdsJson, filePathsJson in
        deviceIdsJson.withCString { devicePtr in
            filePathsJson.withCString { pathsPtr in
                sendCallback?(devicePtr, pathsPtr)
            }
        }
    }

    onMainThread {
        wireTrayManagerCallbacks()
    }
}

@_cdecl("fileapex_tray_set_dropbox_frame_callback")
public func fileapex_tray_set_dropbox_frame_callback(_ callback: FileApexSaveDropBoxFrameCallback?) {
    saveDropBoxFrameCallback = callback
    DropBoxWindowManager.shared.onFrameChanged = { x, y, width, height in
        saveDropBoxFrameCallback?(x, y, width, height)
    }
}

@_cdecl("fileapex_tray_set_dropbox_visibility_callback")
public func fileapex_tray_set_dropbox_visibility_callback(_ callback: FileApexBoolCallback?) {
    dropBoxVisibilityCallback = callback
    DropBoxWindowManager.shared.onVisibilityChanged = { visible in
        dropBoxVisibilityCallback?(visible)
    }
}

@_cdecl("fileapex_tray_set_refresh_devices_callback")
public func fileapex_tray_set_refresh_devices_callback(_ callback: FileApexVoidCallback?) {
    refreshDevicesCallback = callback
    onMainThread {
        MacTrayManager.shared.onRefreshDevices = {
            refreshDevicesCallback?()
        }
    }
}

@_cdecl("fileapex_tray_set_prepare_dropbox_callback")
public func fileapex_tray_set_prepare_dropbox_callback(_ callback: FileApexVoidCallback?) {
    prepareDropBoxCallback = callback
    onMainThread {
        MacTrayManager.shared.onPrepareDropBox = {
            prepareDropBoxCallback?()
        }
    }
}

@_cdecl("fileapex_tray_close_dropbox")
public func fileapex_tray_close_dropbox() {
    DispatchQueue.main.async {
        DropBoxWindowManager.shared.closeDropBox()
    }
}

@_cdecl("fileapex_tray_hide_main_window")
public func fileapex_tray_hide_main_window() {
    DispatchQueue.main.async {
        MacTrayManager.shared.hideMainWindow()
    }
}

@_cdecl("fileapex_tray_setup")
public func fileapex_tray_setup() {
    DispatchQueue.main.async {
        MacTrayManager.shared.ensureTrayInstalled()
        wireTrayManagerCallbacks()
        LocalNetworkProbe.start()
    }
}

@_cdecl("fileapex_tray_start_local_network_probe")
public func fileapex_tray_start_local_network_probe() {
    LocalNetworkProbe.start()
}

@_cdecl("fileapex_tray_set_app_icon_path")
public func fileapex_tray_set_app_icon_path(_ path: UnsafePointer<CChar>?) {
    guard let path else { return }
    let iconPath = String(cString: path)
    DispatchQueue.main.async {
        MacTrayManager.shared.setAppIconPath(iconPath)
    }
}

@_cdecl("fileapex_tray_dropbox_seed_frame")
public func fileapex_tray_dropbox_seed_frame(_ x: Double, _ y: Double, _ width: Double, _ height: Double) {
    DispatchQueue.main.async {
        DropBoxWindowManager.shared.seedSavedFrame(x: x, y: y, width: width, height: height)
    }
}

@_cdecl("fileapex_tray_bind_main_window")
public func fileapex_tray_bind_main_window(nsWindowPtr: Int64) {
    guard nsWindowPtr != 0,
          let raw = UnsafeRawPointer(bitPattern: UInt(truncatingIfNeeded: nsWindowPtr)) else {
        return
    }
    let nsWindow = Unmanaged<NSWindow>.fromOpaque(raw).takeUnretainedValue()
    DispatchQueue.main.async {
        MacTrayManager.shared.bindMainWindow(nsWindow)
    }
}

@_cdecl("fileapex_tray_update_devices")
public func fileapex_tray_update_devices(_ json: UnsafePointer<CChar>?) {
    guard let json else { return }
    let payload = String(cString: json)
    DispatchQueue.main.async {
        TrayDeviceBridge.shared.replaceDevices(from: payload)
    }
}

@_cdecl("fileapex_tray_show_main_window")
public func fileapex_tray_show_main_window() {
    DispatchQueue.main.async {
        MacTrayManager.shared.showMainWindow()
    }
}

@_cdecl("fileapex_tray_show_toast")
public func fileapex_tray_show_toast(_ message: UnsafePointer<CChar>?) {
    guard let message else { return }
    let text = String(cString: message)
    DispatchQueue.main.async {
        NSApp.showNativeToast(message: text)
    }
}

@_cdecl("fileapex_tray_begin_background_activity")
public func fileapex_tray_begin_background_activity() {
    DispatchQueue.main.async {
        if backgroundActivityToken != nil { return }
        backgroundActivityToken = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiated, .idleSystemSleepDisabled],
            reason: "FileApex file transfer"
        )
    }
}

@_cdecl("fileapex_tray_end_background_activity")
public func fileapex_tray_end_background_activity() {
    DispatchQueue.main.async {
        if let token = backgroundActivityToken {
            ProcessInfo.processInfo.endActivity(token)
            backgroundActivityToken = nil
        }
    }
}

private var openPanelBusy = false

@_cdecl("fileapex_pick_open_file")
public func fileapex_pick_open_file(
    _ title: UnsafePointer<CChar>?,
    _ initialDir: UnsafePointer<CChar>?,
    _ outPath: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>
) -> Int32 {
    var picked: String?
    var skipped = false
    onMainThread {
        if openPanelBusy {
            skipped = true
            return
        }
        openPanelBusy = true
        defer { openPanelBusy = false }
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.resolvesAliases = true
        panel.canCreateDirectories = false
        panel.allowedContentTypes = [.item]
        panel.prompt = "Open"
        if let title {
            panel.title = String(cString: title)
        }
        if let initialDir {
            let dir = URL(fileURLWithPath: String(cString: initialDir), isDirectory: true)
            if FileManager.default.fileExists(atPath: dir.path) {
                panel.directoryURL = dir
            }
        }
        let result = panel.runModal()
        if result == .OK {
            picked = panel.url?.path
        }
        let drainUntil = Date().addingTimeInterval(0.08)
        while NSApp.nextEvent(
            matching: [.leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp],
            until: drainUntil,
            inMode: .default,
            dequeue: true
        ) != nil {}
    }
    if skipped {
        outPath.pointee = nil
        return 0
    }
    guard let path = picked else {
        outPath.pointee = nil
        return 0
    }
    let count = path.utf8.count + 1
    let buf = UnsafeMutablePointer<CChar>.allocate(capacity: count)
    path.withCString { src in
        buf.initialize(from: src, count: count)
    }
    outPath.pointee = buf
    return 1
}

private func currentPasteboardText() -> String? {
    let pasteboard = NSPasteboard.general
    if let text = pasteboard.string(forType: .string)?.trimmingCharacters(in: .whitespacesAndNewlines),
       !text.isEmpty {
        return text
    }
    if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL],
       let first = urls.first {
        return first.absoluteString
    }
    return nil
}

private func strdupClipboard(_ text: String) -> UnsafeMutablePointer<CChar> {
    let count = text.utf8.count + 1
    let buf = UnsafeMutablePointer<CChar>.allocate(capacity: count)
    text.withCString { src in
        buf.initialize(from: src, count: count)
    }
    return buf
}

@_cdecl("fileapex_clipboard_start_watch")
public func fileapex_clipboard_start_watch(_ callback: FileApexClipboardCallback?) {
    clipboardCallback = callback
    DispatchQueue.main.async {
        clipboardTimer?.invalidate()
        lastPasteboardChangeCount = NSPasteboard.general.changeCount
        let timer = Timer(timeInterval: 0.45, repeats: true) { _ in
            let count = NSPasteboard.general.changeCount
            guard count != lastPasteboardChangeCount else { return }
            lastPasteboardChangeCount = count
            guard let text = currentPasteboardText(), !text.isEmpty else { return }
            // JNA must not run on the AppKit/AWT main thread — that SIGABRTs the JVM.
            DispatchQueue.global(qos: .userInitiated).async {
                guard let callback = clipboardCallback else { return }
                text.withCString { ptr in
                    callback(ptr)
                }
            }
        }
        clipboardTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }
}

@_cdecl("fileapex_clipboard_note_applied")
public func fileapex_clipboard_note_applied() {
    onMainThread {
        lastPasteboardChangeCount = NSPasteboard.general.changeCount
    }
}

@_cdecl("fileapex_clipboard_stop_watch")
public func fileapex_clipboard_stop_watch() {
    DispatchQueue.main.async {
        clipboardTimer?.invalidate()
        clipboardTimer = nil
    }
    clipboardCallback = nil
}

@_cdecl("fileapex_clipboard_read_text")
public func fileapex_clipboard_read_text(
    _ outText: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>
) -> Int32 {
    var result: String?
    onMainThread {
        result = currentPasteboardText()
    }
    guard let text = result, !text.isEmpty else {
        outText.pointee = nil
        return 0
    }
    outText.pointee = strdupClipboard(text)
    return 1
}
