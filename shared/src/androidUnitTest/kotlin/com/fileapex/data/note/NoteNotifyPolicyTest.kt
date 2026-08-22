package com.fileapex.data.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteNotifyPolicyTest {

    @Test
    fun incomingPreviewPrefersCaptionThenFileName() {
        assertEquals("hello", NoteNotifyPolicy.incomingPreview("hello", "file.apk"))
        assertEquals("file.apk", NoteNotifyPolicy.incomingPreview("  ", "file.apk"))
        assertEquals("", NoteNotifyPolicy.incomingPreview("", null))
    }

    @Test
    fun incomingNoteUsesBulletinChannelRules() {
        assertTrue(
            NoteNotifyPolicy.shouldNotifyIncomingNote(
                isMine = false,
                alreadyNotified = false,
                preview = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyIncomingNote(
                isMine = true,
                alreadyNotified = false,
                preview = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyIncomingNote(
                isMine = false,
                alreadyNotified = true,
                preview = "file.apk"
            )
        )
    }

    @Test
    fun attachmentReadyUsesTransferChannelRules() {
        assertTrue(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = false,
                alreadyHadLocalFile = false,
                fileName = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = false,
                alreadyHadLocalFile = true,
                fileName = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = true,
                alreadyHadLocalFile = false,
                fileName = "file.apk"
            )
        )
        assertFalse(
            NoteNotifyPolicy.shouldNotifyAttachmentReady(
                isMine = false,
                alreadyHadLocalFile = false,
                fileName = ""
            )
        )
    }

    @Test
    fun notificationTitleUsesSenderDeviceName() {
        assertEquals(
            "Bulletin Board · MacBook Pro",
            NoteNotifyPolicy.notificationTitle("MacBook Pro")
        )
        assertEquals(
            "Bulletin Board · Paired Device",
            NoteNotifyPolicy.notificationTitle("")
        )
        assertEquals(
            "Bulletin Board · Paired Device",
            NoteNotifyPolicy.notificationTitle("Paired Device")
        )
    }
}
