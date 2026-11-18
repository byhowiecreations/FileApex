package com.fileapex.i18n

import com.fileapex.platform.DesktopMacTrayBridge
import com.fileapex.platform.DesktopPlatformPaths
import com.fileapex.util.cancellableCatching
import java.io.File

/** Writes the active catalog for native Mac chrome and rebuilds Windows tray labels. */
internal object DesktopI18nRuntime {
    fun sync() {
        val json = AppI18n.runtimeOverlayJson()
        if (DesktopPlatformPaths.isMacOs()) {
            val dir = File(
                System.getProperty("user.home"),
                "Library/Application Support/com.fileapex"
            )
            cancellableCatching("DesktopI18nRuntime sync") {
                dir.mkdirs()
                File(dir, "i18n_runtime.json").writeText(json)
            }
            DesktopMacTrayBridge.setCopyJson(json)
        }
    }
}
