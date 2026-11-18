package com.fileapex.platform

import com.fileapex.domain.clipboard.SensitiveClipPolicy
import com.fileapex.util.cancellableCatching
import com.fileapex.util.logFailureOnce
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.TimeUnit

/** Reads the marker names the OS clipboard carries and asks [SensitiveClipPolicy] whether they mean "sensitive". */
internal object DesktopClipboardMarkers {
    fun isMarked(): Boolean = when {
        DesktopPlatformPaths.isMacOs() -> SensitiveClipPolicy.hasSensitiveMarker(MacClipboardTypes.read())
        DesktopPlatformPaths.isWindows() -> WindowsClipboardMarkers.isMarked()
        DesktopPlatformPaths.isLinux() -> SensitiveClipPolicy.hasSensitiveMarker(LinuxClipboardTargets.read())
        else -> false
    }
}

private object MacClipboardTypes {
    fun read(): List<String> {
        if (DesktopMacTrayBridge.native == null) DesktopMacTrayBridge.load()
        val lib = DesktopMacTrayBridge.native ?: return emptyList()
        val out = PointerByReference()
        val found = cancellableCatching("MacClipboardTypes read") { lib.fileapex_clipboard_read_types(out) }.getOrDefault(0)
        val pointer = out.value
        if (found == 0 || pointer == null) return emptyList()
        return try {
            pointer.getString(0).lines()
        } finally {
            cancellableCatching("MacClipboardTypes free") { lib.fileapex_lan_http_free(pointer) }
        }
    }
}

private interface ClipboardUser32 : StdCallLibrary {
    fun OpenClipboard(owner: Pointer?): Boolean
    fun CloseClipboard(): Boolean
    fun RegisterClipboardFormatW(name: WString): Int
    fun IsClipboardFormatAvailable(format: Int): Boolean
    fun GetClipboardData(format: Int): Pointer?
}

private interface ClipboardKernel32 : StdCallLibrary {
    fun GlobalLock(handle: Pointer?): Pointer?
    fun GlobalUnlock(handle: Pointer?): Boolean
}

private object WindowsClipboardMarkers {
    private const val HISTORY_FLAG = "CanIncludeInClipboardHistory"
    private const val OPEN_ATTEMPTS = 5
    private const val OPEN_RETRY_MS = 10L
    private val markerFormats = listOf("ExcludeClipboardContentFromMonitorProcessing", "Clipboard Viewer Ignore")

    /** Another app can hold the clipboard for a few milliseconds; a read that never opens is logged and treated as not marked. */
    private fun openClipboardWithRetry(user32: ClipboardUser32): Boolean {
        repeat(OPEN_ATTEMPTS) {
            if (user32.OpenClipboard(null)) return true
            Thread.sleep(OPEN_RETRY_MS)
        }
        logFailureOnce("WindowsClipboardMarkers open", IllegalStateException("clipboard busy"))
        return false
    }

    fun isMarked(): Boolean = cancellableCatching("WindowsClipboardMarkers read") {
        val user32 = Native.load("user32", ClipboardUser32::class.java)
        val kernel32 = Native.load("kernel32", ClipboardKernel32::class.java)
        if (!openClipboardWithRetry(user32)) return@cancellableCatching false
        try {
            val present = markerFormats.filter { user32.IsClipboardFormatAvailable(user32.RegisterClipboardFormatW(WString(it))) }
            if (SensitiveClipPolicy.hasSensitiveMarker(present)) return@cancellableCatching true
            val historyFormat = user32.RegisterClipboardFormatW(WString(HISTORY_FLAG))
            if (!user32.IsClipboardFormatAvailable(historyFormat)) return@cancellableCatching false
            val handle = user32.GetClipboardData(historyFormat) ?: return@cancellableCatching false
            val data = kernel32.GlobalLock(handle) ?: return@cancellableCatching false
            try {
                SensitiveClipPolicy.windowsHistoryExcluded(data.getInt(0))
            } finally {
                kernel32.GlobalUnlock(handle)
            }
        } finally {
            user32.CloseClipboard()
        }
    }.getOrDefault(false)
}

private object LinuxClipboardTargets {
    private const val TIMEOUT_MS = 400L

    fun read(): List<String> {
        val command = if (System.getenv("WAYLAND_DISPLAY").isNullOrBlank()) {
            listOf("xclip", "-selection", "clipboard", "-t", "TARGETS", "-o")
        } else {
            listOf("wl-paste", "--list-types")
        }
        return cancellableCatching("LinuxClipboardTargets read") {
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return@cancellableCatching emptyList<String>()
            }
            process.inputStream.bufferedReader().readLines()
        }.getOrDefault(emptyList())
    }
}
