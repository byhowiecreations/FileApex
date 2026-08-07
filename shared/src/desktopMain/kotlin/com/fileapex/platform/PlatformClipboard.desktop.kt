package com.fileapex.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI

actual object PlatformClipboard {
    actual fun getSystemClipboardText(): String? {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null)
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                contents.getTransferData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        }.getOrNull()
    }

    actual fun setSystemClipboardText(text: String) {
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(text)
            clipboard.setContents(selection, null)
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
}
