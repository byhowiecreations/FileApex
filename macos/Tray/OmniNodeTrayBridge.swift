import AppKit
import Foundation

public typealias OmniNodeSendCallback = @convention(c) (UnsafePointer<CChar>?, UnsafePointer<CChar>?) -> Void
public typealias OmniNodeBoolCallback = @convention(c) (Bool) -> Void
public typealias OmniNodeVoidCallback = @convention(c) () -> Void
public typealias OmniNodeSaveDropBoxFrameCallback = @convention(c) (Double, Double, Double, Double) -> Void

private var sendCallback: OmniNodeSendCallback?
private var popoverCallback: OmniNodeBoolCallback?
private var quitCallback: OmniNodeVoidCallback?
private var showMainWindowCallback: OmniNodeVoidCallback?
private var dropBoxVisibilityCallback: OmniNodeBoolCallback?
private var saveDropBoxFrameCallback: OmniNodeSaveDropBoxFrameCallback?
private var refreshDevicesCallback: OmniNodeVoidCallback?
private var prepareDropBoxCallback: OmniNodeVoidCallback?
private var backgroundActivityToken: NSObjectProtocol?

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

@_cdecl("omninode_tray_register_callbacks")
public func omninode_tray_register_callbacks(
    send: OmniNodeSendCallback?,
    popoverVisible: OmniNodeBoolCallback?,
    quit: OmniNodeVoidCallback?,
    showMainWindow: OmniNodeVoidCallback?
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

@_cdecl("omninode_tray_set_dropbox_frame_callback")
public func omninode_tray_set_dropbox_frame_callback(_ callback: OmniNodeSaveDropBoxFrameCallback?) {
    saveDropBoxFrameCallback = callback
    DropBoxWindowManager.shared.onFrameChanged = { x, y, width, height in
        saveDropBoxFrameCallback?(x, y, width, height)
    }
}

@_cdecl("omninode_tray_set_dropbox_visibility_callback")
public func omninode_tray_set_dropbox_visibility_callback(_ callback: OmniNodeBoolCallback?) {
    dropBoxVisibilityCallback = callback
    DropBoxWindowManager.shared.onVisibilityChanged = { visible in
        dropBoxVisibilityCallback?(visible)
    }
}

@_cdecl("omninode_tray_set_refresh_devices_callback")
public func omninode_tray_set_refresh_devices_callback(_ callback: OmniNodeVoidCallback?) {
    refreshDevicesCallback = callback
    onMainThread {
        MacTrayManager.shared.onRefreshDevices = {
            refreshDevicesCallback?()
        }
    }
}

@_cdecl("omninode_tray_set_prepare_dropbox_callback")
public func omninode_tray_set_prepare_dropbox_callback(_ callback: OmniNodeVoidCallback?) {
    prepareDropBoxCallback = callback
    onMainThread {
        MacTrayManager.shared.onPrepareDropBox = {
            prepareDropBoxCallback?()
        }
    }
}

@_cdecl("omninode_tray_close_dropbox")
public func omninode_tray_close_dropbox() {
    DispatchQueue.main.async {
        DropBoxWindowManager.shared.closeDropBox()
    }
}

@_cdecl("omninode_tray_hide_main_window")
public func omninode_tray_hide_main_window() {
    DispatchQueue.main.async {
        MacTrayManager.shared.hideMainWindow()
    }
}

@_cdecl("omninode_tray_setup")
public func omninode_tray_setup() {
    DispatchQueue.main.async {
        MacTrayManager.shared.ensureTrayInstalled()
        wireTrayManagerCallbacks()
    }
}

@_cdecl("omninode_tray_set_app_icon_path")
public func omninode_tray_set_app_icon_path(_ path: UnsafePointer<CChar>?) {
    guard let path else { return }
    let iconPath = String(cString: path)
    DispatchQueue.main.async {
        MacTrayManager.shared.setAppIconPath(iconPath)
    }
}

@_cdecl("omninode_tray_dropbox_seed_frame")
public func omninode_tray_dropbox_seed_frame(_ x: Double, _ y: Double, _ width: Double, _ height: Double) {
    DispatchQueue.main.async {
        DropBoxWindowManager.shared.seedSavedFrame(x: x, y: y, width: width, height: height)
    }
}

@_cdecl("omninode_tray_bind_main_window")
public func omninode_tray_bind_main_window(nsWindowPtr: Int64) {
    guard nsWindowPtr != 0,
          let raw = UnsafeRawPointer(bitPattern: UInt(truncatingIfNeeded: nsWindowPtr)) else {
        return
    }
    let nsWindow = Unmanaged<NSWindow>.fromOpaque(raw).takeUnretainedValue()
    DispatchQueue.main.async {
        MacTrayManager.shared.bindMainWindow(nsWindow)
    }
}

@_cdecl("omninode_tray_update_devices")
public func omninode_tray_update_devices(_ json: UnsafePointer<CChar>?) {
    guard let json else { return }
    let payload = String(cString: json)
    DispatchQueue.main.async {
        TrayDeviceBridge.shared.replaceDevices(from: payload)
    }
}

@_cdecl("omninode_tray_show_main_window")
public func omninode_tray_show_main_window() {
    DispatchQueue.main.async {
        MacTrayManager.shared.showMainWindow()
    }
}

@_cdecl("omninode_tray_show_toast")
public func omninode_tray_show_toast(_ message: UnsafePointer<CChar>?) {
    guard let message else { return }
    let text = String(cString: message)
    DispatchQueue.main.async {
        NSApp.showNativeToast(message: text)
    }
}

@_cdecl("omninode_tray_begin_background_activity")
public func omninode_tray_begin_background_activity() {
    DispatchQueue.main.async {
        if backgroundActivityToken != nil { return }
        backgroundActivityToken = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiated, .idleSystemSleepDisabled],
            reason: "OmniNode file transfer"
        )
    }
}

@_cdecl("omninode_tray_end_background_activity")
public func omninode_tray_end_background_activity() {
    DispatchQueue.main.async {
        if let token = backgroundActivityToken {
            ProcessInfo.processInfo.endActivity(token)
            backgroundActivityToken = nil
        }
    }
}
