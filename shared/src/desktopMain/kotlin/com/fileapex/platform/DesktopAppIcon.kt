package com.fileapex.platform

import java.awt.Image
import javax.imageio.ImageIO

/**
 * Loads the same launcher art as Android [composeApp ic_launcher] for desktop tray/window use.
 */
object DesktopAppIcon {
    private const val TRAY_RESOURCE = "/icons/fileapex-tray.png"

    fun loadTrayImage(): Image? =
        runCatching {
            val stream = DesktopAppIcon::class.java.getResourceAsStream(TRAY_RESOURCE)
                ?: return null
            stream.use { input -> ImageIO.read(input) }
        }.getOrNull()
}
