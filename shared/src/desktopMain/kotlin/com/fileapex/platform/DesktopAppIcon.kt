package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
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

    fun loadTrayPainter(): Painter? {
        val bytes = DesktopAppIcon::class.java.getResourceAsStream(TRAY_RESOURCE)?.use { it.readBytes() }
            ?: return null
        val bitmap: ImageBitmap = decodeImageBytes(bytes) ?: return null
        return BitmapPainter(bitmap)
    }
}
