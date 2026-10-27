package com.fileapex.presentation

import com.fileapex.data.note.NoteRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesNewDividerTest {
    private fun bubble(id: String) = NotesListRow.Bubble(NoteRecord(noteId = id, sourceDeviceId = "d", sourceDeviceName = "n", content = id, epochMs = 1))
    private fun day(key: String) = NotesListRow.DayHeader(key, key)

    /** Newest first, as the reversed list shows it: index 0 is the bottom of the screen. */
    private val rows = listOf(bubble("c"), bubble("b"), day("today"), bubble("a"), day("yesterday"))

    @Test
    fun theDividerSitsAboveTheOldestUnreadPostAndBelowItsDayHeader() {
        val out = NotesNewDivider.insert(rows, setOf("b", "c"))
        assertEquals(listOf("c", "b", "NEW", "today", "a", "yesterday"), out.map(::label))
    }

    @Test
    fun anUnreadPostInAnOlderDayPutsTheDividerThere() {
        val out = NotesNewDivider.insert(rows, setOf("a", "c"))
        assertEquals(listOf("c", "b", "today", "a", "NEW", "yesterday"), out.map(::label))
    }

    @Test
    fun nothingUnreadOrNothingOnScreenLeavesTheListAlone() {
        assertEquals(rows, NotesNewDivider.insert(rows, emptySet()))
        assertEquals(rows, NotesNewDivider.insert(rows, setOf("gone")))
        assertEquals(emptyList<NotesListRow>(), NotesNewDivider.insert(emptyList(), setOf("a")))
    }

    private fun label(row: NotesListRow) = when (row) {
        is NotesListRow.Bubble -> row.note.noteId
        is NotesListRow.DayHeader -> row.dayKey
        NotesListRow.NewDivider -> "NEW"
    }
}
