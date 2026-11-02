package com.fileapex.friends.ui

import com.fileapex.friends.FriendInviteLink
import org.junit.Assert.*
import org.junit.Test

class FriendInviteFormTest {
    private val uri = FriendInviteLink("ab".repeat(16), "cd".repeat(16), "${"ef".repeat(32)}||", "Friends", "Alex").toUri()

    @Test fun scanningAValidInvitePrefillsTheLinkAndClearsAnyPreviousPin() {
        val form = FriendInviteForm()
        form.pinChanged("123456")
        assertTrue(form.acceptScan(uri))
        assertEquals(uri, form.link)
        assertEquals("", form.pin)
        assertFalse(form.canJoin)
    }

    @Test fun invalidQrDoesNotOverwriteTheCurrentInvite() {
        val form = FriendInviteForm(uri)
        assertFalse(form.acceptScan("https://example.com/not-a-friend"))
        assertEquals(uri, form.link)
    }

    @Test fun joiningRequiresAValidInviteSixAsciiDigitsAndAName() {
        val form = FriendInviteForm(uri)
        form.name = "Me"
        form.pinChanged("a0123456")
        assertEquals("012345", form.pin)
        assertTrue(form.canJoin)
        form.link = "invalid"
        assertFalse(form.canJoin)
        form.link = uri
        form.name = "  "
        assertFalse(form.canJoin)
    }

    @Test fun aNewPrefillDoesNotReuseThePreviousInvitesCredentials() {
        val previous = FriendInviteForm(uri)
        previous.pinChanged("123456")
        previous.name = "Me"
        val next = FriendInviteForm(uri.replace("Alex", "Blair"))
        assertEquals("", next.pin)
        assertEquals("", next.name)
        assertFalse(next.canJoin)
    }
}
