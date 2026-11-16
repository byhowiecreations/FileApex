package com.fileapex.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.Reader
import java.net.URI
import java.net.URL
import com.fileapex.domain.clipboard.ClipboardCopySignals
import com.fileapex.domain.clipboard.ClipboardImageEnvelope
import java.awt.Graphics2D
import java.awt.Image
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

actual object PlatformClipboard {
    actual fun getSystemClipboardText(): String? {
        val onEdt = java.awt.EventQueue.isDispatchThread()
        // Never run pbpaste/osascript on the AWT EDT, that froze clicks for ~1s after first paint.
        val appKit = if (!onEdt) {
            DesktopMacTrayBridge.readClipboardText()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val pasted = if (!onEdt) {
            appKit ?: MacPasteboard.readPlainText()?.takeIf { it.isNotBlank() }
        } else {
            appKit
        }
        val text = pasted ?: readAwtClipboardText()
        return ClipboardCopySignals.boundedRaw(text)
    }

    actual fun getSystemClipboardTimestamp(): Long? = null

    actual fun getSystemClipboardImageBytes(): ByteArray? {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null) ?: return@runCatching null
            if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return@runCatching null
            val rawImage = contents.getTransferData(DataFlavor.imageFlavor) as? Image ?: return@runCatching null
            encodeBoundedClipboardImage(toBufferedImage(rawImage))
        }.getOrNull()
    }

    actual fun setSystemClipboardText(text: String) {
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(text)
            clipboard.setContents(selection, null)
        }
    }

    actual fun applyRemoteText(text: String, sourceDeviceName: String) {
        ClipboardShareSuppressor.isApplyingRemote = true
        try {
            setSystemClipboardText(text)
            DesktopMacTrayBridge.noteClipboardApplied()
        } finally {
            ClipboardShareSuppressor.isApplyingRemote = false
        }
    }

    actual fun applyRemoteImage(bytes: ByteArray, mimeType: String, sourceDeviceName: String) {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return
        ClipboardShareSuppressor.isApplyingRemote = true
        try {
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
            DesktopMacTrayBridge.noteClipboardApplied()
        } finally {
            ClipboardShareSuppressor.isApplyingRemote = false
        }
    }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = buffered.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return buffered
    }

    private fun encodeBoundedClipboardImage(source: BufferedImage): ByteArray? {
        val maxDim = 1280
        val scale = minOf(maxDim.toDouble() / source.width, maxDim.toDouble() / source.height, 1.0)
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        val opaque = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = opaque.createGraphics()
        try {
            g.fillRect(0, 0, targetW, targetH)
            g.drawImage(source, 0, 0, targetW, targetH, null)
        } finally {
            g.dispose()
        }
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return null
        val writer = writers.next()
        return try {
            for (quality in listOf(0.84f, 0.70f, 0.55f)) {
                val out = ByteArrayOutputStream()
                ImageIO.createImageOutputStream(out).use { ios ->
                    writer.output = ios
                    val param = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                    writer.write(null, IIOImage(opaque, null, null), param)
                }
                val bytes = out.toByteArray()
                if (bytes.size <= ClipboardImageEnvelope.MAX_RAW_IMAGE_BYTES) return bytes
            }
            null
        } finally {
            writer.dispose()
        }
    }

    actual fun openUrlInDefaultBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI(RichHandoff.canonicalVideoUrl(url)))
                }
            }
        }
    }

    actual fun sharePlainText(text: String, title: String) {
        setSystemClipboardText(text)
        openUrlInDefaultBrowser("https://github.com/byhowiecreations/FileApex/releases")
    }

    private fun readAwtClipboardText(): String? {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null) ?: return@runCatching null
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                (contents.getTransferData(DataFlavor.stringFlavor) as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return@runCatching it }
            }
            val urlFlavor = DataFlavor("application/x-java-url;class=java.net.URL")
            if (contents.isDataFlavorSupported(urlFlavor)) {
                when (val data = contents.getTransferData(urlFlavor)) {
                    is URL -> data.toString().takeIf { it.isNotBlank() }?.let { return@runCatching it }
                    is URI -> data.toString().takeIf { it.isNotBlank() }?.let { return@runCatching it }
                    is String -> data.takeIf { it.isNotBlank() }?.let { return@runCatching it }
                }
            }
            contents.transferDataFlavors.forEach { flavor ->
                if (!flavor.isFlavorTextType) return@forEach
                when (val data = runCatching { contents.getTransferData(flavor) }.getOrNull()) {
                    is String -> data.takeIf { it.isNotBlank() }?.let { return@runCatching it }
                    is Reader -> data.readText().takeIf { it.isNotBlank() }?.let { return@runCatching it }
                    is CharSequence -> data.toString().takeIf { it.isNotBlank() }?.let { return@runCatching it }
                }
            }
            null
        }.getOrNull()
    }
}
