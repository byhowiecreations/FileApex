package com.fileapex.ui.notes

import com.fileapex.domain.clipboard.ClipboardCopySignals
import com.fileapex.platform.PlatformClipboard
import com.fileapex.util.cancellableCatching
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Desktop clipboard helpers supporting copied files, pasted screenshots/bitmaps, and image copy-back. */
internal object DesktopBulletinClipboard {
    fun extractPastePayload(
        transferable: Transferable?,
        cacheDir: File = defaultPasteCacheDir()
    ): BulletinPastePayload {
        if (transferable != null) {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val paths = cancellableCatching("BulletinClipboardSupport extractPastePayload") {
                    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                        ?.mapNotNull { entry -> (entry as? File)?.absolutePath }
                        ?.filter { File(it).isFile }
                        .orEmpty()
                }.getOrDefault(emptyList())
                if (paths.isNotEmpty()) return BulletinPastePayload.Files(paths)
            }
            if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val pngFile = cancellableCatching("BulletinClipboardSupport extractPastePayload 2") {
                    val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image
                        ?: return@cancellableCatching null
                    writeBufferedPng(toBufferedImage(image), cacheDir)
                }.getOrNull()
                if (pngFile != null) return BulletinPastePayload.Files(listOf(pngFile.absolutePath))
            }
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = cancellableCatching("BulletinClipboardSupport extractPastePayload 3") {
                    transferable.getTransferData(DataFlavor.stringFlavor) as? String
                }.getOrNull()
                val bounded = ClipboardCopySignals.boundedRaw(text)
                if (!bounded.isNullOrBlank()) return BulletinPastePayload.Text(bounded)
            }
        }
        val fallbackText = PlatformClipboard.getSystemClipboardText()
        return if (!fallbackText.isNullOrBlank()) {
            BulletinPastePayload.Text(fallbackText)
        } else {
            BulletinPastePayload.Empty
        }
    }

    fun copyImageFile(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val image = cancellableCatching("BulletinClipboardSupport copyImageFile") { ImageIO.read(file) }.getOrNull() ?: return false
        return cancellableCatching("BulletinClipboardSupport copyImageFile 2") {
            val selection = object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                    flavor == DataFlavor.imageFlavor

                override fun getTransferData(flavor: DataFlavor): Any {
                    if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
                    return image
                }
            }
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            true
        }.getOrDefault(false)
    }

    private fun defaultPasteCacheDir(): File =
        File(System.getProperty("java.io.tmpdir"), "fileapex-clipboard-paste").apply { mkdirs() }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = buffered.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return buffered
    }

    private fun writeBufferedPng(image: BufferedImage, cacheDir: File): File? {
        cacheDir.mkdirs()
        // Keep only the most recent temporary pasted screenshots so temp cache stays bounded.
        cacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        val target = File(cacheDir, "clipboard-${System.currentTimeMillis()}.png")
        return if (ImageIO.write(image, "png", target)) target else null
    }
}

internal actual fun readBulletinPastePayload(): BulletinPastePayload {
    val transferable = cancellableCatching("BulletinClipboardSupport readBulletinPastePayload") {
        Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    }.getOrNull()
    return DesktopBulletinClipboard.extractPastePayload(transferable)
}

internal actual fun copyImageFileToClipboard(path: String): Boolean =
    DesktopBulletinClipboard.copyImageFile(path)
