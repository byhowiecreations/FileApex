package com.fileapex.platform

import java.awt.Desktop
import java.io.File

actual fun openLocalFile(absolutePath: String, displayName: String) {
    val file = File(absolutePath)
    if (!file.isFile) return
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return
    runCatching { desktop.open(file) }
}

actual fun revealInFolder(absolutePath: String) {
    val file = File(absolutePath)
    if (!file.exists()) return
    val command = when {
        DesktopPlatformPaths.isMacOs() -> listOf("open", "-R", file.absolutePath)
        DesktopPlatformPaths.isWindows() -> listOf("explorer.exe", "/select,", file.absolutePath)
        else -> null
    }
    if (command != null) {
        runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
            .onFailure { error -> println("revealInFolder: ${error.message}") }
        return
    }
    val folder = file.parentFile ?: return
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return
    runCatching { desktop.open(folder) }
}
