package com.fileapex.domain.widget

import com.fileapex.data.bulletin.BulletinContentType
import com.fileapex.data.note.NoteRecord
import com.fileapex.util.TimeUtils

data class BulletinWidgetChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean
)

data class BulletinWidgetItem(
    val noteId: String,
    val authorLabel: String,
    val previewText: String,
    val timeLabel: String,
    val isChecklist: Boolean,
    val checklistTitle: String,
    val checklistItems: List<BulletinWidgetChecklistItem>
)

data class BulletinWidgetSnapshot(
    val items: List<BulletinWidgetItem>,
    val totalCount: Int,
    val isEmpty: Boolean
)

object BulletinWidgetSnapshotBuilder {
    const val DEFAULT_MAX_ITEMS = 5
    const val MAX_PREVIEW_CHARS = 96

    fun build(
        notes: List<NoteRecord>,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        zoneId: String = TimeUtils.DEFAULT_ZONE_ID
    ): BulletinWidgetSnapshot {
        if (notes.isEmpty()) {
            return BulletinWidgetSnapshot(items = emptyList(), totalCount = 0, isEmpty = true)
        }
        val latest = notes
            .sortedByDescending { it.epochMs }
            .take(maxItems.coerceAtLeast(1))
            .map { note -> toWidgetItem(note, zoneId) }
        return BulletinWidgetSnapshot(
            items = latest,
            totalCount = notes.size,
            isEmpty = latest.isEmpty()
        )
    }

    private fun toWidgetItem(note: NoteRecord, zoneId: String): BulletinWidgetItem {
        val stamp = TimeUtils.noteListStamp(note.epochMs, zoneId)
        val isChecklist = note.contentType == BulletinContentType.CHECKLIST
        val checklistTitle = note.content.trim().lineSequence().firstOrNull().orEmpty().take(MAX_PREVIEW_CHARS)
        val rawPreview = when {
            isChecklist -> checklistTitle
            note.content.isNotBlank() -> note.content.trim()
            !note.attachmentFileName.isNullOrBlank() -> note.attachmentFileName.trim()
            else -> ""
        }
        val preview = truncatePreview(rawPreview)
        return BulletinWidgetItem(
            noteId = note.noteId,
            authorLabel = note.sourceDeviceName.trim(),
            previewText = preview,
            timeLabel = stamp.timeLabel,
            isChecklist = isChecklist,
            checklistTitle = checklistTitle,
            checklistItems = if (isChecklist) {
                note.checklist.map { BulletinWidgetChecklistItem(id = it.id, text = it.text, done = it.done) }
            } else {
                emptyList()
            }
        )
    }

    private fun truncatePreview(text: String): String {
        val singleLine = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (singleLine.length <= MAX_PREVIEW_CHARS) return singleLine
        return singleLine.take(MAX_PREVIEW_CHARS - 3).trimEnd() + "..."
    }
}
