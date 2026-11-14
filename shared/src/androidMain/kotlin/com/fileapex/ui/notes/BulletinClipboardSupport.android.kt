package com.fileapex.ui.notes

import com.fileapex.platform.PlatformClipboard

internal actual fun readBulletinPastePayload(): BulletinPastePayload {
    val text = PlatformClipboard.getSystemClipboardText()
    return if (!text.isNullOrBlank()) BulletinPastePayload.Text(text) else BulletinPastePayload.Empty
}

internal actual fun copyImageFileToClipboard(path: String): Boolean = false
