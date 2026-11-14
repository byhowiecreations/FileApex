package com.fileapex.presentation

import com.fileapex.data.bulletin.BulletinContentType
import com.fileapex.data.note.NoteRecord
import com.fileapex.platform.isWebUrl
import com.fileapex.platform.textContainsWebUrl

/** Filtering and keyword matching helpers for the Bulletin Board feed. */
object BulletinSearchPolicy {
    fun shouldShowSearchField(searchOpen: Boolean): Boolean = searchOpen

    fun shouldShowActiveQueryChip(searchOpen: Boolean, query: String): Boolean =
        !searchOpen && query.isNotBlank()

    /** Returns true when [note] matches [query] across body text, attachments, device name, or checklist lines. */
    fun matches(note: NoteRecord, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        if (note.content.lowercase().contains(needle)) return true
        if (note.attachmentFileName?.lowercase()?.contains(needle) == true) return true
        if (note.sourceDeviceName.lowercase().contains(needle)) return true
        return note.checklist.any { it.text.lowercase().contains(needle) }
    }

    /** Applies the active [BulletinFilter] category and optional text [query] together. */
    fun filterNotes(
        notes: List<NoteRecord>,
        filter: BulletinFilter,
        query: String
    ): List<NoteRecord> {
        val byCategory = when (filter) {
            BulletinFilter.All -> notes
            BulletinFilter.Links -> notes.filter { isWebUrl(it.content) || textContainsWebUrl(it.content) }
            BulletinFilter.Images -> notes.filter { isBulletinImage(it.attachmentFileName) }
            BulletinFilter.Documents -> notes.filter { isBulletinDoc(it.attachmentFileName) }
            BulletinFilter.Snippets -> notes.filter { isBulletinSnippet(it) }
            BulletinFilter.Files -> notes.filter { isBulletinOtherFile(it.attachmentFileName) }
            BulletinFilter.Pinned -> notes.filter { it.attachmentPinned }
            BulletinFilter.Todo -> notes.filter { it.contentType == BulletinContentType.CHECKLIST }
            is BulletinFilter.Tag -> {
                val target = filter.tag.lowercase()
                notes.filter { target in extractBulletinHashtags(it.content) }
            }
        }
        return if (query.isBlank()) byCategory else byCategory.filter { matches(it, query) }
    }
}
