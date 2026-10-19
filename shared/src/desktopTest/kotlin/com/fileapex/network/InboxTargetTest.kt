package com.fileapex.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTargetTest {
    private val inbox = "/Users/me/Downloads/FileApex"

    @Test
    fun theReceiverPlacesAFileInItsOwnReceiveFolder() {
        assertEquals("$inbox/photo.jpg", InboxTarget.resolve("@inbox/photo.jpg", inbox))
    }

    @Test
    fun aSubfolderOfATransferIsKept() {
        assertEquals("$inbox/trip/day1/a.png", InboxTarget.resolve("@inbox/trip/day1/a.png", inbox))
    }

    @Test
    fun backslashesFromAWindowsSenderAreAccepted() {
        assertEquals("$inbox/trip/a.png", InboxTarget.resolve("@inbox\\trip\\a.png", inbox))
    }

    @Test
    fun theFolderItselfCanBeNamed() {
        assertEquals(inbox, InboxTarget.resolve("@inbox", inbox))
        assertEquals(inbox, InboxTarget.resolve("@inbox/", inbox))
    }

    @Test
    fun climbingOutOfTheReceiveFolderIsRefused() {
        assertNull(InboxTarget.resolve("@inbox/../secret.txt", inbox))
        assertNull(InboxTarget.resolve("@inbox/a/../../secret.txt", inbox))
        assertNull(InboxTarget.resolve("@inbox\\..\\secret.txt", inbox))
    }

    @Test
    fun anOrdinaryPathPassesThroughForBrowsedFolders() {
        assertEquals("/storage/emulated/0/Documents/a.txt", InboxTarget.resolve("/storage/emulated/0/Documents/a.txt", inbox))
        assertFalse(InboxTarget.isInbox("/storage/emulated/0/@inbox/a.txt"))
        assertFalse(InboxTarget.isInbox("@inboxes/a.txt"))
        assertTrue(InboxTarget.isInbox("@inbox/a.txt"))
    }
}
