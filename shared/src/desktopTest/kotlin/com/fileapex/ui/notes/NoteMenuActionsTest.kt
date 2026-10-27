package com.fileapex.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMenuActionsTest {
    @Test
    fun theMenuOffersTheSameActionsAsTheSwipe() {
        assertEquals(
            listOf(NoteMenuAction.Copy, NoteMenuAction.Lock, NoteMenuAction.Delete),
            NoteMenuActions.forNote(hasCopyable = true, locked = false)
        )
    }

    @Test
    fun aLockedNoteOffersUnlockAndANoteWithNothingToCopyHasNoCopy() {
        assertEquals(
            listOf(NoteMenuAction.Unlock, NoteMenuAction.Delete),
            NoteMenuActions.forNote(hasCopyable = false, locked = true)
        )
    }
}
