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
