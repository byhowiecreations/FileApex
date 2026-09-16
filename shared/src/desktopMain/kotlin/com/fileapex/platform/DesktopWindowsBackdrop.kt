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
            println("DesktopWindowsBackdrop: attr $attribute failed - ${error.message}")
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

/**
 * Windows CLI console attach for the GUI-subsystem [FileApex.exe] only.
 *
 * Preferred path: [fileapex.exe] is built as CONSOLE subsystem (see prepareWindowsCliLauncher)
 * so Java already owns a real console — same model as macOS tty. In that case this is a no-op
 * aside from enabling VT.
 *
 * Fallback: GUI exe launched with args via AttachConsole(parent) + rebind FileDescriptor streams
 * (the approach that previously made plain `fileapex help` work). Never open exclusive CONIN$.
 */
object DesktopWindowsConsole {
    private const val ATTACH_PARENT_PROCESS = -1
    private const val STD_OUTPUT_HANDLE = -11
    private const val ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
    private const val CP_UTF8 = 65001

    private interface WinKernel32 : Library {
        fun AttachConsole(dwProcessId: Int): Boolean
        fun GetStdHandle(nStdHandle: Int): Pointer?
        fun GetConsoleMode(hConsoleHandle: Pointer, lpMode: IntArray): Boolean
        fun SetConsoleMode(hConsoleHandle: Pointer, dwMode: Int): Boolean
        fun SetConsoleCP(wCodePageID: Int): Boolean
        fun SetConsoleOutputCP(wCodePageID: Int): Boolean
    }

    private val kernel32: WinKernel32? by lazy {
        runCatching { Native.load("kernel32", WinKernel32::class.java) }.getOrNull()
    }

    fun attachConsole() {
        if (!DesktopPlatformPaths.isWindows()) return
        runCatching {
            val k32 = kernel32 ?: return

            if (hasLiveConsole(k32)) {
                runCatching { k32.SetConsoleCP(CP_UTF8) }
                runCatching { k32.SetConsoleOutputCP(CP_UTF8) }
                enableVirtualTerminal(k32)
                return
            }

            if (!k32.AttachConsole(ATTACH_PARENT_PROCESS)) return

            runCatching { k32.SetConsoleCP(CP_UTF8) }
            runCatching { k32.SetConsoleOutputCP(CP_UTF8) }

            System.setIn(java.io.FileInputStream(java.io.FileDescriptor.`in`))
            System.setOut(
                java.io.PrintStream(
                    java.io.FileOutputStream(java.io.FileDescriptor.out),
                    true,
                    java.nio.charset.StandardCharsets.UTF_8
                )
            )
            System.setErr(
                java.io.PrintStream(
                    java.io.FileOutputStream(java.io.FileDescriptor.err),
                    true,
                    java.nio.charset.StandardCharsets.UTF_8
                )
            )
            enableVirtualTerminal(k32)
        }
    }

    private fun hasLiveConsole(k32: WinKernel32): Boolean {
        val hOut = k32.GetStdHandle(STD_OUTPUT_HANDLE) ?: return false
        if (isInvalidHandle(hOut)) return false
        return k32.GetConsoleMode(hOut, IntArray(1))
    }

    private fun enableVirtualTerminal(k32: WinKernel32) {
        val hOut = k32.GetStdHandle(STD_OUTPUT_HANDLE) ?: return
        if (isInvalidHandle(hOut)) return
        val mode = IntArray(1)
        if (!k32.GetConsoleMode(hOut, mode)) return
        k32.SetConsoleMode(hOut, mode[0] or ENABLE_VIRTUAL_TERMINAL_PROCESSING)
    }

    private fun isInvalidHandle(handle: Pointer): Boolean {
        val value = Pointer.nativeValue(handle)
        return value == 0L || value == -1L
    }
}
