package com.fileapex.friends

import org.junit.Assert.*
import org.junit.Test

class FriendsInviteTest {
    private val key = "ab".repeat(32)

    @Test fun linkRoundTripsAndKeepsPinOut() {
        val link = FriendInviteLink("1".repeat(32), "2".repeat(32), "$key|https://relay.example/|1.2.3.4:5,6.7.8.9:1", "Co-Workers & Co", "Sam S")
        val uri = link.toUri()
        assertTrue(uri.startsWith("fileapex://friend?"))
        assertEquals(link, FriendInviteLink.parse("Join me: $uri thanks"))
        assertEquals(key, link.inviterKey)
    }

    @Test fun malformedLinksAreRejected() {
        assertNull(FriendInviteLink.parse("fileapex://pair?x=1"))
        assertNull(FriendInviteLink.parse("fileapex://friend?v=1&i=zz&s=${"2".repeat(32)}&a=x"))
        assertNull(FriendInviteLink.parse("https://example.com"))
        assertNull(FriendInviteLink.parse("fileapex://friend?v=2&i=${"1".repeat(32)}&s=${"2".repeat(32)}&a=$key%7C%7C"))
    }

    @Test fun verifierNeedsBothSecretAndPin() {
        val v = FriendsInviteSecrets.verifier("a".repeat(32), "123456")
        assertTrue(FriendsInviteSecrets.matches(v, "a".repeat(32), "123456"))
        assertFalse(FriendsInviteSecrets.matches(v, "a".repeat(32), "123457"))
        assertFalse(FriendsInviteSecrets.matches(v, "b".repeat(32), "123456"))
        assertTrue(Regex("\\d{6}").matches(FriendsInviteSecrets.newPin()))
    }
}
