package com.fileapex.platform

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import kotlin.math.roundToInt

/**
 * JNA loader for `libFileApexTray.dylib` (NSStatusItem + NSPopover + SwiftUI tray).
 * Loads and invokes native code **only on macOS**; all entry points no-op elsewhere.
 */
object DesktopMacTrayBridge {
    @Volatile
    private var native: FileApexTrayNative? = null

    // Strong refs — JNA discards unretained callback proxies and native calls become no-ops.
    @Volatile
    private var sendCallback: SendCallback? = null
    @Volatile
    private var popoverCallback: PopoverCallback? = null
    @Volatile
    private var quitCallback: QuitCallback? = null
    @Volatile
    private var showMainWindowCallback: ShowMainWindowCallback? = null
    @Volatile
    private var saveDropBoxFrameCallback: SaveDropBoxFrameCallback? = null
    @Volatile
    private var dropBoxVisibilityCallback: PopoverCallback? = null
    @Volatile
    private var refreshDevicesCallback: VoidTrayCallback? = null
    @Volatile
    private var prepareDropBoxCallback: VoidTrayCallback? = null

    val isLoaded: Boolean
        get() = DesktopPlatformPaths.isMacOs() && native != null

    fun load(): Boolean {
        if (!DesktopPlatformPaths.isMacOs()) return false
        native?.let { return true }
        val dylib = resolveDylib() ?: run {
            println("DesktopMacTrayBridge: libFileApexTray.dylib not found")
            return false
        }
        return runCatching {
            native = Native.load(dylib.absolutePath, FileApexTrayNative::class.java)
            println("DesktopMacTrayBridge: loaded ${dylib.absolutePath}")
            true
        }.getOrElse { error ->
            println("DesktopMacTrayBridge: load failed :: ${error.message}")
            false
        }
    }

    fun registerCallbacks(
        onSend: (deviceIdsJson: String, filePathsJson: String) -> Unit,
        onPopoverVisible: (Boolean) -> Unit,
        onDropBoxVisible: (Boolean) -> Unit,
        onRefreshDevices: () -> Unit,
        onPrepareDropBox: () -> Unit,
        onQuit: () -> Unit,
        onShowMainWindow: () -> Unit
    ) {
        if (!DesktopPlatformPaths.isMacOs()) return
        val lib = native ?: return
        sendCallback = SendCallback { deviceIdsJson, filePathsJson ->
            val deviceIds = deviceIdsJson?.getString(0).orEmpty()
            val filePaths = filePathsJson?.getString(0).orEmpty()
            if (deviceIds.isNotBlank() && filePaths.isNotBlank()) {
                onSend(deviceIds, filePaths)
            }
        }
        popoverCallback = PopoverCallback { visible -> onPopoverVisible(visible) }
        quitCallback = QuitCallback { onQuit() }
        showMainWindowCallback = ShowMainWindowCallback { onShowMainWindow() }
        saveDropBoxFrameCallback = SaveDropBoxFrameCallback { x, y, width, height ->
            DesktopDropBoxBoundsStore.persistPixels(
                x = x.roundToInt(),
                y = y.roundToInt(),
                width = width.roundToInt(),
                height = height.roundToInt()
            )
        }
        dropBoxVisibilityCallback = PopoverCallback { visible -> onDropBoxVisible(visible) }
        refreshDevicesCallback = VoidTrayCallback { onRefreshDevices() }
        prepareDropBoxCallback = VoidTrayCallback { onPrepareDropBox() }

        lib.fileapex_tray_register_callbacks(
            sendCallback,
            popoverCallback,
            quitCallback,
            showMainWindowCallback
        )
        lib.fileapex_tray_set_dropbox_frame_callback(saveDropBoxFrameCallback)
        lib.fileapex_tray_set_dropbox_visibility_callback(dropBoxVisibilityCallback)
        lib.fileapex_tray_set_refresh_devices_callback(refreshDevicesCallback)
        lib.fileapex_tray_set_prepare_dropbox_callback(prepareDropBoxCallback)
        seedDropBoxFrame()
    }

    fun resyncDropBoxFrame() {
        if (!DesktopPlatformPaths.isMacOs()) return
        seedDropBoxFrame()
    }

    private fun seedDropBoxFrame() {
        val lib = native ?: return
        val bounds = DesktopDropBoxBoundsStore.loadValidated() ?: return
        lib.fileapex_tray_dropbox_seed_frame(
            bounds.x.toDouble(),
            bounds.y.toDouble(),
            bounds.width.toDouble(),
            bounds.height.toDouble()
        )
    }

    fun setup() {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_setup()
        resolveAppIconPath()?.let { path ->
            native?.fileapex_tray_set_app_icon_path(path)
        }
    }

    fun bindMainWindow(nsWindowPtr: Long) {
        if (!DesktopPlatformPaths.isMacOs() || nsWindowPtr == 0L) return
        native?.fileapex_tray_bind_main_window(nsWindowPtr)
    }

    fun hideMainWindow() {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_hide_main_window()
    }

    fun updateDevices(json: String) {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_update_devices(json)
    }

    fun showMainWindow() {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_show_main_window()
    }

    fun showToast(message: String) {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_show_toast(message)
    }

    fun beginBackgroundActivity() {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_begin_background_activity()
    }

    fun endBackgroundActivity() {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_end_background_activity()
    }

    fun closeDropBox() {
        if (!DesktopPlatformPaths.isMacOs()) return
        native?.fileapex_tray_close_dropbox()
    }

    private fun resolveDylib(): File? {
        val fromBundle = resolveRunningAppBundle()?.let { bundle ->
            File(bundle, "Contents/Frameworks/libFileApexTray.dylib").takeIf { it.isFile }
        }
        if (fromBundle != null) return fromBundle

        val devTree = File(System.getProperty("user.dir"), "macos/build/Tray/libFileApexTray.dylib")
        if (devTree.isFile) return devTree

        val parentDev = File(System.getProperty("user.dir")).parentFile
            ?.resolve("macos/build/Tray/libFileApexTray.dylib")
            ?.takeIf { it.isFile }
        return parentDev
    }

    private fun resolveAppIconPath(): String? =
        resolveRunningAppBundle()?.let { bundle ->
            File(bundle, "Contents/Resources/FileApex.icns")
                .takeIf { it.isFile }
                ?.absolutePath
        }

    private fun resolveRunningAppBundle(): File? {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (!resourcesDir.isNullOrBlank()) {
            val fromResources = File(resourcesDir).parentFile?.parentFile
            if (fromResources != null && fromResources.name.endsWith(".app")) {
                return fromResources
            }
        }
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        var cursor: File? = File(command).canonicalFile.parentFile
        repeat(6) {
            val current = cursor ?: return null
            if (current.name.endsWith(".app")) return current
            cursor = current.parentFile
        }
        return null
    }

    private interface FileApexTrayNative : Library {
        fun fileapex_tray_setup()
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
        fun fileapex_tray_hide_main_window()
        fun fileapex_tray_close_dropbox()
        fun fileapex_tray_dropbox_seed_frame(x: Double, y: Double, width: Double, height: Double)
        fun fileapex_tray_update_devices(json: String)
        fun fileapex_tray_show_main_window()
        fun fileapex_tray_show_toast(message: String)
        fun fileapex_tray_begin_background_activity()
        fun fileapex_tray_end_background_activity()
    }

    private fun interface SendCallback : Callback {
        fun invoke(deviceIdsJson: Pointer?, filePathsJson: Pointer?)
    }

    private fun interface PopoverCallback : Callback {
        fun invoke(visible: Boolean)
    }

    private fun interface QuitCallback : Callback {
        fun invoke()
    }

    private fun interface ShowMainWindowCallback : Callback {
        fun invoke()
    }

    private fun interface SaveDropBoxFrameCallback : Callback {
        fun invoke(x: Double, y: Double, width: Double, height: Double)
    }

    private fun interface VoidTrayCallback : Callback {
        fun invoke()
    }
}
