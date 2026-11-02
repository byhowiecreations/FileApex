package com.fileapex.domain.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRefusalBookTest {
    private val book = PeerRefusalBook(windowMs = 10_000L, minPeers = 2)

    @Test
    fun oneRefusingPeerIsNotAStaleRoster() {
        assertFalse(book.refused("a", 1_000L))
        assertFalse(book.refused("a", 2_000L))
    }

    @Test
    fun twoDifferentRefusingPeersWithNoAcceptedContactMakeTheRosterStale() {
        assertFalse(book.refused("a", 1_000L))
        assertTrue(book.refused("b", 2_000L))
    }

    @Test
    fun peersThatStillAcceptUsKeepTheRosterTrustedWhileTheyAreTheMajority() {
        book.accepted("c", 500L)
        book.accepted("d", 600L)
        book.accepted("e", 700L)
        assertFalse(book.refused("a", 1_000L))
        assertFalse(book.refused("b", 2_000L))
    }

    @Test
    fun moreRefusingPeersThanAcceptingPeersMakeTheRosterStaleEvenWhileSomePeersLag() {
        book.accepted("c", 500L)
        book.accepted("d", 600L)
        assertFalse(book.refused("a", 1_000L))
        assertFalse("an even split is not a majority", book.refused("b", 2_000L))
        assertTrue(book.refused("e", 3_000L))
    }

    @Test
    fun aPeerThatRefusedAndThenAcceptsUsCountsAsAcceptingOnly() {
        book.refused("a", 1_000L)
        book.accepted("a", 1_500L)
        assertFalse(book.refused("b", 2_000L))
    }

    @Test
    fun aPeerThatAcceptedAndThenRefusesUsCountsAsRefusingOnly() {
        book.accepted("a", 500L)
        book.refused("a", 1_000L)
        assertTrue(book.refused("b", 2_000L))
    }

    @Test
    fun refusalsOlderThanTheWindowDoNotCount() {
        book.refused("a", 1_000L)
        assertFalse(book.refused("b", 1_000L + 10_001L))
    }

    @Test
    fun aPeerCountsAsRefusingUntilItAcceptsUsOrTheWindowEnds() {
        book.refused("a", 1_000L)
        assertTrue(book.refusedRecently("a", 5_000L))
        assertFalse(book.refusedRecently("a", 12_000L))
        book.refused("a", 13_000L)
        book.accepted("a", 14_000L)
        assertFalse(book.refusedRecently("a", 14_500L))
        assertFalse(book.refusedRecently("never", 1_000L))
    }

    @Test
    fun clearingForgetsEverything() {
        book.refused("a", 1_000L)
        book.clear()
        assertFalse(book.refused("b", 2_000L))
        assertFalse(book.refusedRecently("a", 2_000L))
    }

    private val hosts = mapOf("192.168.1.10" to "a", "192.168.1.11" to "b", "192.168.1.12" to "c", "192.168.1.13" to "d")
    private var stale = 0
    private var clock = 1_000L

    private fun wireTheObject() {
        PeerRefusals.book.clear()
        stale = 0
        clock = 1_000L
        PeerRefusals.resolve = { host, _ -> hosts[host] }
        PeerRefusals.onStale = { stale++ }
        PeerRefusals.now = { clock }
    }

    @Test
    fun twoRefusingHostsTriggerTheStaleRosterHandlerOnceAndStartOver() {
        wireTheObject()
        PeerRefusals.onRefusedHost("192.168.1.10", 8080)
        assertEquals(0, stale)
        PeerRefusals.onRefusedHost("192.168.1.11", 8080)
        assertEquals(1, stale)
        PeerRefusals.onRefusedHost("192.168.1.12", 8080)
        assertEquals(1, stale)
    }

    @Test
    fun anAcceptedHostKeepsTheRosterTrustedAndAnUnknownHostCountsForNothing() {
        wireTheObject()
        PeerRefusals.onAcceptedHost("192.168.1.12", 8080)
        PeerRefusals.onAcceptedHost("192.168.1.13", 8080)
        PeerRefusals.onRefusedHost("192.168.1.10", 8080)
        PeerRefusals.onRefusedHost("192.168.1.11", 8080)
        PeerRefusals.onRefusedHost("10.0.0.9", 8080)
        assertEquals(0, stale)
        assertTrue(PeerRefusals.book.refusedRecently("a", clock))
    }

    @Test
    fun aRevokedAnswerCountsAsARefusalAndAnyOtherAnswerAsAcceptance() {
        wireTheObject()
        PeerRefusals.onAnswer("192.168.1.10", 8080, 401, "revoked")
        PeerRefusals.onAnswer("192.168.1.11", 8080, 401, "Revoked: device removed")
        assertEquals(1, stale)
        wireTheObject()
        PeerRefusals.onAnswer("192.168.1.10", 8080, 200, "ok")
        PeerRefusals.onAnswer("192.168.1.11", 8080, 401, "pin_required")
        PeerRefusals.onAnswer("192.168.1.12", 8080, 403, "")
        assertEquals(0, stale)
        assertFalse(PeerRefusals.book.refusedRecently("a", clock))
    }

    @Test
    fun aPeerWithoutAPinYetIsFoundByItsRosterAddress() {
        wireTheObject()
        PeerRefusals.routeLookup = { _, _ -> null }
        PeerRefusals.resolve = PeerRefusals.defaultResolve
        PeerRefusals.updateRoster(mapOf(" 192.168.1.10 " to "a", "192.168.1.11" to "b"))
        PeerRefusals.onAnswer("192.168.1.10", 8080, 401, "revoked")
        PeerRefusals.onAnswer("192.168.1.11", 8080, 401, "revoked")
        PeerRefusals.onAnswer("10.9.9.9", 8080, 401, "revoked")
        assertEquals(1, stale)
        PeerRefusals.routeLookup = { host, _ -> if (host == "192.168.1.12") "c" else null }
        assertEquals("c", PeerRefusals.defaultResolve("192.168.1.12", 8080))
        assertEquals("a", PeerRefusals.defaultResolve("192.168.1.10", 8080))
    }

    @Test
    fun theDefaultHandlerOfAStaleRosterNeverMakesTheDeviceForgetItsCluster() {
        var forgets = 0
        val saved = com.fileapex.domain.pairing.KickedByPeer.forgetCluster
        com.fileapex.domain.pairing.KickedByPeer.forgetCluster = { forgets++; 1 }
        try {
            wireTheObject()
            PeerRefusals.onStale = PeerRefusals.defaultOnStale
            PeerRefusals.onRefusedHost("192.168.1.10", 8080)
            PeerRefusals.onRefusedHost("192.168.1.11", 8080)
            Thread.sleep(150)
            assertEquals(0, forgets)
        } finally {
            com.fileapex.domain.pairing.KickedByPeer.forgetCluster = saved
        }
    }
}
