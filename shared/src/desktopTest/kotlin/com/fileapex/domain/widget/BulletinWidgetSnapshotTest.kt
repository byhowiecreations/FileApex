package com.fileapex.domain.widget

import com.fileapex.data.bulletin.BulletinContentType
import com.fileapex.data.note.NoteChecklistItem
import com.fileapex.data.note.NoteRecord
import com.fileapex.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinWidgetSnapshotTest {

    @Test
    fun emptyNotesProducesEmptySnapshot() {
        val snapshot = BulletinWidgetSnapshotBuilder.build(emptyList())
        assertTrue(snapshot.isEmpty)
        assertEquals(0, snapshot.totalCount)
        assertTrue(snapshot.items.isEmpty())
    }

    @Test
    fun ordersNewestFirstLimitsToFiveAndPreservesChecklistItems() {
        val base = 1_700_000_000_000L
        val notes = (1..8).map { index ->
            if (index == 7) {
                NoteRecord(
                    noteId = "note-$index",
                    sourceDeviceId = "peer-1",
                    sourceDeviceName = "Pixel",
                    content = "Groceries",
                    epochMs = base + index * 1_000L,
                    isMine = false,
                    contentType = BulletinContentType.CHECKLIST,
                    checklist = listOf(
                        NoteChecklistItem(id = "c1", text = "Milk", done = false),
                        NoteChecklistItem(id = "c2", text = "Eggs", done = true)
                    )
                )
            } else {
                NoteRecord(
                    noteId = "note-$index",
                    sourceDeviceId = "self",
                    sourceDeviceName = "Phone",
                    content = if (index == 8) "" else "Message #$index " + "x".repeat(120),
                    attachmentFileName = if (index == 8) "report.pdf" else null,
                    epochMs = base + index * 1_000L,
                    isMine = true,
                    contentType = if (index == 8) BulletinContentType.FILE_METADATA else BulletinContentType.TEXT
                )
            }
        }

        val snapshot = BulletinWidgetSnapshotBuilder.build(notes, maxItems = 5, zoneId = TimeUtils.DEFAULT_ZONE_ID)
        assertFalse(snapshot.isEmpty)
        assertEquals(8, snapshot.totalCount)
        assertEquals(listOf("note-8", "note-7", "note-6", "note-5", "note-4"), snapshot.items.map { it.noteId })

        val fileItem = snapshot.items[0]
        assertFalse(fileItem.isChecklist)
        assertTrue(fileItem.previewText.contains("report.pdf"))

        val checklistItem = snapshot.items[1]
        assertTrue(checklistItem.isChecklist)
        assertEquals("Groceries", checklistItem.checklistTitle)
        assertEquals(2, checklistItem.checklistItems.size)
        assertEquals("c1", checklistItem.checklistItems[0].id)
        assertFalse(checklistItem.checklistItems[0].done)
        assertTrue(checklistItem.checklistItems[1].done)

        val truncated = snapshot.items[2]
        assertTrue(truncated.previewText.length <= BulletinWidgetSnapshotBuilder.MAX_PREVIEW_CHARS)
    }
}
