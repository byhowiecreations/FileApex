package com.fileapex.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Applies Windows 11 Mica to the main window title bar via DWM.
 *
 * Compose Desktop uses a decorated frame; do not call [DwmExtendFrameIntoClientArea] or set a
 * transparent AWT background — both break decorated windows ("The frame is decorated").
 */
object DesktopWindowsBackdrop {
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMSBT_DISABLE = 1
    private const val DWMSBT_MAINWINDOW = 2 // Mica

    private class HandleRef(peer: Pointer?) : PointerType(peer)

    private val dwm: Dwmapi? by lazy {
        runCatching {
            Native.load("dwmapi", Dwmapi::class.java)
        }.getOrNull()
    }

    fun applyMica(window: Window, enabled: Boolean) {
        if (!DesktopPlatformPaths.isWindows()) return
        SwingUtilities.invokeLater {
            if (!window.isDisplayable) return@invokeLater
            val api = dwm ?: return@invokeLater
            val hwnd = hwndFor(window) ?: return@invokeLater
            val backdrop = if (enabled) DWMSBT_MAINWINDOW else DWMSBT_DISABLE
            setIntAttribute(api, hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdrop)
            setIntAttribute(api, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 0)
        }
    }

    private fun hwndFor(window: Window): HandleRef? {
        return runCatching {
            val ptr = Native.getComponentPointer(window) ?: return null
            HandleRef(ptr)
        }.getOrNull()
    }

    private fun setIntAttribute(api: Dwmapi, hwnd: HandleRef, attribute: Int, value: Int) {
        val buf = Memory(4).apply { setInt(0, value) }
        runCatching {
            api.DwmSetWindowAttribute(hwnd, attribute, buf, 4)
        }.onFailure { error ->
            println("DesktopWindowsBackdrop: attr $attribute failed — ${error.message}")
        }
    }

    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(
            hwnd: HandleRef,
            dwAttribute: Int,
            pvAttribute: Pointer,
            cbAttribute: Int
        ): Int
    }
}
