package com.fileapex.platform

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Entry points of `libFileApexTray.dylib` and the callback shapes it calls back with; used by [DesktopMacTrayBridge]. */
internal interface FileApexTrayNative : Library {
    fun fileapex_tray_install_app_lifecycle()
    fun fileapex_tray_request_app_terminate()
    fun fileapex_tray_setup()
    fun fileapex_tray_start_local_network_probe()
    fun fileapex_lan_set_peer_callback(callback: LanPeerCallback?)
    fun fileapex_lan_tls_set_routes(json: String): Int
    fun fileapex_lan_tls_take_mismatch(host: String, port: Int, out: Pointer, outLen: Int): Int
    fun fileapex_lan_tls_set_identity(p12: Pointer, p12Len: Int, password: String): Int
    fun fileapex_lan_http_execute_headers(
        method: String, url: String, contentType: String?, body: Pointer?, bodyLen: Int, timeoutMs: Int,
        outStatus: IntByReference, outBody: PointerByReference, outBodyLen: IntByReference,
        outHeaders: PointerByReference, outHeadersLen: IntByReference
    ): Int
    fun fileapex_lan_http_upload_file(
        url: String,
        contentType: String?,
        filePath: String,
        offsetBytes: Long,
        timeoutMs: Int,
        progress: UploadProgressCallback?,
        outStatus: IntByReference,
        outBody: PointerByReference,
        outBodyLen: IntByReference
    ): Int
    fun fileapex_lan_http_upload_file_range(
        url: String,
        contentType: String?,
        filePath: String,
        offsetBytes: Long,
        lengthBytes: Long,
        timeoutMs: Int,
        progress: UploadProgressCallback?,
        outStatus: IntByReference,
        outBody: PointerByReference,
        outBodyLen: IntByReference
    ): Int
    fun fileapex_lan_http_download_file(
        url: String,
        destinationPath: String,
        timeoutMs: Int,
        outStatus: IntByReference
    ): Int
    fun fileapex_lan_http_free(pointer: Pointer?)
    fun fileapex_lan_http_cancel_transfers()
    fun fileapex_tray_set_app_icon_path(path: String)
    fun fileapex_tray_bind_main_window(nsWindowPtr: Long)
    fun fileapex_tray_register_callbacks(
        send: SendCallback?,
        popoverVisible: PopoverCallback?,
        quit: QuitCallback?,
        showMainWindow: ShowMainWindowCallback?
    )
    fun fileapex_tray_set_dropbox_frame_callback(saveDropBoxFrame: SaveDropBoxFrameCallback?)
    fun fileapex_tray_set_dropbox_visibility_callback(visible: PopoverCallback?)
    fun fileapex_tray_set_refresh_devices_callback(refreshDevices: VoidTrayCallback?)
    fun fileapex_tray_set_prepare_dropbox_callback(prepareDropBox: VoidTrayCallback?)
    fun fileapex_tray_set_cancel_send_callback(cancelSend: VoidTrayCallback?)
    fun fileapex_tray_set_send_clipboard_callback(sendClipboard: TextTrayCallback?)
    fun fileapex_tray_set_check_batteries_callback(checkBatteries: VoidTrayCallback?)
    fun fileapex_tray_set_browse_device_callback(browseDevice: TextTrayCallback?)
    fun fileapex_tray_set_post_note_callback(postNote: TextTrayCallback?)
    fun fileapex_tray_set_checking_batteries(checking: Boolean)
    fun fileapex_tray_hide_main_window()
    fun fileapex_tray_close_dropbox()
    fun fileapex_tray_dropbox_seed_frame(x: Double, y: Double, width: Double, height: Double)
    fun fileapex_tray_update_devices(json: String)
    fun fileapex_tray_set_copy(json: String)
    fun fileapex_tray_show_main_window()
    fun fileapex_tray_show_toast(message: String)
    fun fileapex_tray_begin_background_activity()
    fun fileapex_tray_end_background_activity()
    fun fileapex_pick_open_file(
        title: String?,
        initialDir: String?,
        outPath: PointerByReference
    ): Int
    fun fileapex_clipboard_start_watch(callback: ClipboardTextCallback?)
    fun fileapex_clipboard_stop_watch()
    fun fileapex_clipboard_note_applied()
    fun fileapex_clipboard_read_text(outText: PointerByReference): Int
}

internal fun interface UploadProgressCallback : Callback {
    fun invoke(sentBytes: Long, totalBytes: Long)
}

internal fun interface SendCallback : Callback {
    fun invoke(deviceIdsJson: Pointer?, filePathsJson: Pointer?)
}

internal fun interface PopoverCallback : Callback {
    fun invoke(visible: Boolean)
}

internal fun interface QuitCallback : Callback {
    fun invoke()
}

internal fun interface ShowMainWindowCallback : Callback {
    fun invoke()
}

internal fun interface SaveDropBoxFrameCallback : Callback {
    fun invoke(x: Double, y: Double, width: Double, height: Double)
}

internal fun interface VoidTrayCallback : Callback {
    fun invoke()
}

internal fun interface TextTrayCallback : Callback {
    fun invoke(deviceId: Pointer?)
}

internal fun interface LanPeerCallback : Callback {
    fun invoke(host: Pointer?, port: Int, serviceName: Pointer?)
}

internal fun interface ClipboardTextCallback : Callback {
    fun invoke(text: Pointer?)
}
