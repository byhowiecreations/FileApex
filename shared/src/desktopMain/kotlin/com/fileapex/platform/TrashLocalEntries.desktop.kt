package com.fileapex.platform

import com.fileapex.i18n.AppI18n
import java.awt.Desktop
import java.io.File

actual fun trashLocalEntries(absolutePaths: List<String>): Boolean {
    if (absolutePaths.isEmpty()) return true
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
        error(AppI18n.t("trash_failed"))
    }
    for (path in absolutePaths) {
        val file = File(path)
        if (!file.exists()) continue
        if (!desktop.moveToTrash(file)) error(AppI18n.t("trash_failed"))
    }
    return true
}

actual fun trashLocalEntriesQuietly(absolutePaths: List<String>) {
    trashLocalEntries(absolutePaths)
}
