package com.fileapex.data.bulletin

import com.fileapex.data.note.NoteRecord
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.textContainsWebUrl

enum class BulletinDeleteContentKind {
    MESSAGE,
    LINK,
    FILE,
    IMAGE;

    val dialogTitle: String
        get() = when (this) {
            MESSAGE -> AppI18n.t("delete_message")
            LINK -> AppI18n.t("delete_web_link")
            FILE -> AppI18n.t("delete_file")
            IMAGE -> AppI18n.t("delete_image")
        }

    val entryLabel: String
        get() = when (this) {
            MESSAGE -> AppI18n.t("entry_message")
            LINK -> AppI18n.t("entry_web_link")
            FILE -> AppI18n.t("entry_file")
            IMAGE -> AppI18n.t("entry_image")
        }
}

fun NoteRecord.bulletinDeleteContentKind(): BulletinDeleteContentKind {
    if (attachmentPreviewBase64 != null) return BulletinDeleteContentKind.IMAGE
    if (!attachmentFileName.isNullOrBlank()) return BulletinDeleteContentKind.FILE
    if (textContainsWebUrl(content)) return BulletinDeleteContentKind.LINK
    return BulletinDeleteContentKind.MESSAGE
}

fun NoteRecord.hasBulletinBinaryAttachment(): Boolean = !attachmentFileName.isNullOrBlank()
