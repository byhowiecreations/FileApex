package com.fileapex.ui.motion

import com.fileapex.platform.DesktopLifecycleLog
import com.fileapex.platform.DesktopPlatformPaths
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val SPI_GETCLIENTAREAANIMATION = 0x1042

private interface User32 : Library {
    fun SystemParametersInfoW(action: Int, param: Int, value: IntByReference, winIni: Int): Boolean
}

actual fun systemReducedMotion(): Boolean = when {
    DesktopPlatformPaths.isMacOs() -> macReduceMotion()
    DesktopPlatformPaths.isWindows() -> windowsReduceMotion()
    else -> linuxReduceMotion()
}

private fun macReduceMotion(): Boolean = try {
    val objc = NativeLibrary.getInstance("objc")
    val msgSend = objc.getFunction("objc_msgSend")
    val register = objc.getFunction("sel_registerName")
    val workspaceClass = objc.getFunction("objc_getClass").invokePointer(arrayOf("NSWorkspace"))
    val workspace = msgSend.invokePointer(arrayOf<Any>(workspaceClass, register.invokePointer(arrayOf("sharedWorkspace"))))
    msgSend.invokeInt(arrayOf<Any>(workspace, register.invokePointer(arrayOf("accessibilityDisplayShouldReduceMotion")))).toByte() != 0.toByte()
} catch (e: UnsatisfiedLinkError) {
    DesktopLifecycleLog.log("ReducedMotion: macOS query unavailable: ${e.message}")
    false
} catch (e: IllegalArgumentException) {
    DesktopLifecycleLog.log("ReducedMotion: macOS query failed: ${e.message}")
    false
}

private fun windowsReduceMotion(): Boolean = try {
    val user32 = Native.load("user32", User32::class.java)
    val enabled = IntByReference(1)
    user32.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0) && enabled.value == 0
} catch (e: UnsatisfiedLinkError) {
    DesktopLifecycleLog.log("ReducedMotion: Windows query unavailable: ${e.message}")
    false
}

private fun linuxReduceMotion(): Boolean = try {
    val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "enable-animations").redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(1, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        LinuxAnimationsSetting.parse(output) == false
    }
} catch (e: IOException) {
    false
}
