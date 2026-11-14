package com.fileapex.ui.notes

/** Structured result of inspecting the local system clipboard for Bulletin Board paste. */
sealed interface BulletinPastePayload {
    data class Files(val paths: List<String>) : BulletinPastePayload
    data class Text(val text: String) : BulletinPastePayload
    data object Empty : BulletinPastePayload
}

/** Reads files, images (saved to a temp PNG), or plain text from the platform clipboard for composer paste. */
internal expect fun readBulletinPastePayload(): BulletinPastePayload

/** Copies a local image file onto the platform system clipboard when supported. */
internal expect fun copyImageFileToClipboard(path: String): Boolean
