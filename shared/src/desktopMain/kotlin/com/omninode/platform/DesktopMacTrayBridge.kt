package com.omninode.platform

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import kotlin.math.roundToInt

/**
 * JNA loader for `libOmniNodeTray.dylib` (NSStatusItem + NSPopover + SwiftUI tray).
 */
object DesktopMacTrayBridge {
    @Volatile
    private var native: OmniNodeTrayNative? = null

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
        get() = native != null

    fun load(): Boolean {
        native?.let { return true }
        if (!isMacOs()) return false
        val dylib = resolveDylib() ?: run {
            println("DesktopMacTrayBridge: libOmniNodeTray.dylib not found")
            return false
        }
        return runCatching {
            native = Native.load(dylib.absolutePath, OmniNodeTrayNative::class.java)
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

        lib.omninode_tray_register_callbacks(
            sendCallback,
            popoverCallback,
            quitCallback,
            showMainWindowCallback
        )
        lib.omninode_tray_set_dropbox_frame_callback(saveDropBoxFrameCallback)
        lib.omninode_tray_set_dropbox_visibility_callback(dropBoxVisibilityCallback)
        lib.omninode_tray_set_refresh_devices_callback(refreshDevicesCallback)
        lib.omninode_tray_set_prepare_dropbox_callback(prepareDropBoxCallback)
        seedDropBoxFrame()
    }

    fun resyncDropBoxFrame() {
        seedDropBoxFrame()
    }

    private fun seedDropBoxFrame() {
        val lib = native ?: return
        val bounds = DesktopDropBoxBoundsStore.loadValidated() ?: return
        lib.omninode_tray_dropbox_seed_frame(
            bounds.x.toDouble(),
            bounds.y.toDouble(),
            bounds.width.toDouble(),
            bounds.height.toDouble()
        )
    }

    fun setup() {
        native?.omninode_tray_setup()
        resolveAppIconPath()?.let { path ->
            native?.omninode_tray_set_app_icon_path(path)
        }
    }

    fun bindMainWindow(nsWindowPtr: Long) {
        if (nsWindowPtr != 0L) {
            native?.omninode_tray_bind_main_window(nsWindowPtr)
        }
    }

    fun hideMainWindow() {
        native?.omninode_tray_hide_main_window()
    }

    fun updateDevices(json: String) {
        native?.omninode_tray_update_devices(json)
    }

    fun showMainWindow() {
        native?.omninode_tray_show_main_window()
    }

    fun showToast(message: String) {
        native?.omninode_tray_show_toast(message)
    }

    fun beginBackgroundActivity() {
        native?.omninode_tray_begin_background_activity()
    }

    fun endBackgroundActivity() {
        native?.omninode_tray_end_background_activity()
    }

    fun closeDropBox() {
        native?.omninode_tray_close_dropbox()
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    private fun resolveDylib(): File? {
        val fromBundle = resolveRunningAppBundle()?.let { bundle ->
            File(bundle, "Contents/Frameworks/libOmniNodeTray.dylib").takeIf { it.isFile }
        }
        if (fromBundle != null) return fromBundle

        val devTree = File(System.getProperty("user.dir"), "macos/build/Tray/libOmniNodeTray.dylib")
        if (devTree.isFile) return devTree

        val parentDev = File(System.getProperty("user.dir")).parentFile
            ?.resolve("macos/build/Tray/libOmniNodeTray.dylib")
            ?.takeIf { it.isFile }
        return parentDev
    }

    private fun resolveAppIconPath(): String? =
        resolveRunningAppBundle()?.let { bundle ->
            File(bundle, "Contents/Resources/OmniNode.icns")
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

    private interface OmniNodeTrayNative : Library {
        fun omninode_tray_setup()
        fun omninode_tray_set_app_icon_path(path: String)
        fun omninode_tray_bind_main_window(nsWindowPtr: Long)
        fun omninode_tray_register_callbacks(
            send: SendCallback?,
            popoverVisible: PopoverCallback?,
            quit: QuitCallback?,
            showMainWindow: ShowMainWindowCallback?
        )
        fun omninode_tray_set_dropbox_frame_callback(saveDropBoxFrame: SaveDropBoxFrameCallback?)
        fun omninode_tray_set_dropbox_visibility_callback(visible: PopoverCallback?)
        fun omninode_tray_set_refresh_devices_callback(refreshDevices: VoidTrayCallback?)
        fun omninode_tray_set_prepare_dropbox_callback(prepareDropBox: VoidTrayCallback?)
        fun omninode_tray_hide_main_window()
        fun omninode_tray_close_dropbox()
        fun omninode_tray_dropbox_seed_frame(x: Double, y: Double, width: Double, height: Double)
        fun omninode_tray_update_devices(json: String)
        fun omninode_tray_show_main_window()
        fun omninode_tray_show_toast(message: String)
        fun omninode_tray_begin_background_activity()
        fun omninode_tray_end_background_activity()
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
