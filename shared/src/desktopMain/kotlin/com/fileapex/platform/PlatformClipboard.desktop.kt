package com.fileapex.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.Reader
import java.net.URI
import java.net.URL

actual object PlatformClipboard {
    actual fun getSystemClipboardText(): String? {
        if (!java.awt.EventQueue.isDispatchThread()) {
            val appKit = DesktopMacTrayBridge.readClipboardText()
            if (!appKit.isNullOrBlank()) return appKit
        }
        val pasted = MacPasteboard.readPlainText()
        if (!pasted.isNullOrBlank()) return pasted
        return readAwtClipboardText()
    }

    actual fun setSystemClipboardText(text: String) {
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(text)
            clipboard.setContents(selection, null)
        }
    }

    actual fun applyRemoteText(text: String) {
        ClipboardShareSuppressor.isApplyingRemote = true
        try {
            setSystemClipboardText(text)
            DesktopMacTrayBridge.noteClipboardApplied()
        } finally {
            ClipboardShareSuppressor.isApplyingRemote = false
        }
    }

    actual fun openUrlInDefaultBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI(url))
                }
            }
        }
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
