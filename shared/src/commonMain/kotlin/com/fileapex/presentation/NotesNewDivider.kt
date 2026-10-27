package com.fileapex.presentation

object NotesNewDivider {
    /** [rows] run newest first (index 0 is the bottom of the screen); the divider goes just above the oldest unread post. */
    fun insert(rows: List<NotesListRow>, unreadIds: Set<String>): List<NotesListRow> {
        if (unreadIds.isEmpty()) return rows
        val oldestUnread = rows.indexOfLast { it is NotesListRow.Bubble && it.note.noteId in unreadIds }
        if (oldestUnread < 0) return rows
        return rows.toMutableList().apply { add(oldestUnread + 1, NotesListRow.NewDivider) }
    }
}
