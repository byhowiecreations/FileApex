package com.fileapex.data.bulletin

import com.fileapex.data.note.NoteRecord
import com.fileapex.platform.textContainsWebUrl

enum class BulletinDeleteContentKind {
    MESSAGE,
    LINK,
    FILE,
    IMAGE;

    val dialogTitle: String
        get() = when (this) {
            MESSAGE -> "Delete Message"
            LINK -> "Delete Web Link"
            FILE -> "Delete File"
            IMAGE -> "Delete Image"
        }

    val entryLabel: String
        get() = when (this) {
            MESSAGE -> "message"
            LINK -> "web link"
            FILE -> "file"
            IMAGE -> "image"
        }
}

fun NoteRecord.bulletinDeleteContentKind(): BulletinDeleteContentKind {
    if (attachmentPreviewBase64 != null) return BulletinDeleteContentKind.IMAGE
    if (!attachmentFileName.isNullOrBlank()) return BulletinDeleteContentKind.FILE
    if (textContainsWebUrl(content)) return BulletinDeleteContentKind.LINK
    return BulletinDeleteContentKind.MESSAGE
}

fun NoteRecord.hasBulletinBinaryAttachment(): Boolean = !attachmentFileName.isNullOrBlank()
