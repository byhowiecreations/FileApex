package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KickedByPeerTest {
    private var signOuts = 0
    private var handled = 0L
    private var linked = true
    private val roster = listOf(
        peer("a", "192.168.1.10", "pin"), peer("b", "192.168.1.11", "pin"), peer("c", "192.168.1.12", ""),
        peer("d", "192.168.1.13", "pin", removed = true)
    )

    private fun peer(id: String, ip: String, pin: String, removed: Boolean = false) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = ip, port = 8080, publicKeyHash = "h$id", publicKey = "k$id",
        rootPath = "/", clusterVersion = 1L, tlsPin = pin, isRemoved = removed
    )

    @Before
    fun setUp() {
        signOuts = 0; handled = 0L; linked = true
        KickedByPeer.signOut = { signOuts++ }
        KickedByPeer.googleLinked = { linked }
        KickedByPeer.handledVersion = { handled }
        KickedByPeer.markHandled = { handled = it }
        KickedByPeer.announce = {}
        KickedByPeer.dropClusterKey = {}
    }

    private fun settle() = Thread.sleep(150)

    @Test
    fun aDisabledFirebaseUserSignsOutAtOnceWithoutPeerEvidence() {
        KickedByPeer.onFirebaseUserDisabled()
        settle()
        assertEquals(1, signOuts)
    }

    @Test
    fun aDisabledFirebaseUserDoesNothingWhenGoogleIsNotLinked() {
        linked = false
        KickedByPeer.onFirebaseUserDisabled()
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun oneSightingIsNotEnough() {
        KickedByPeer.onRevoked("192.168.1.10", 500L, roster, nowMs = 1_000L)
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun twoDifferentPeersConfirmTheRemoval() {
        KickedByPeer.onRevoked("192.168.1.10", 510L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.11", 510L, roster, nowMs = 1_500L)
        settle()
        assertEquals(1, signOuts)
        assertEquals(510L, handled)
    }

    @Test
    fun onePeerTwiceFarEnoughApartConfirms() {
        KickedByPeer.onRevoked("192.168.1.10", 520L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.10", 520L, roster, nowMs = 1_000L + KickEvidence.CONFIRM_GAP_MS)
        settle()
        assertEquals(1, signOuts)
    }

    @Test
    fun anAnswerFromAPeerWithoutAPinOrAStrangerOrARemovedPeerCountsForNothing() {
        KickedByPeer.onRevoked("192.168.1.12", 530L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.13", 530L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("10.9.9.9", 530L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.12", 530L, roster, nowMs = 1_000L + KickEvidence.CONFIRM_GAP_MS)
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun anAnswerWithoutAStampDoesNothing() {
        KickedByPeer.onRevoked("192.168.1.10", 0L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.11", 0L, roster, nowMs = 1_000L)
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun aStampIsActedOnOnlyOnce() {
        KickedByPeer.onRevoked("192.168.1.10", 540L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.11", 540L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.10", 540L, roster, nowMs = 9_000_000L)
        settle()
        assertEquals(1, signOuts)
    }

    @Test
    fun aDeviceNotLinkedToGoogleOnlyRecordsTheStamp() {
        linked = false
        KickedByPeer.onRevoked("192.168.1.10", 550L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("192.168.1.11", 550L, roster, nowMs = 1_000L)
        settle()
        assertEquals(0, signOuts)
        assertEquals(550L, handled)
    }

    @Test
    fun evidenceKeepsStampsApart() {
        val evidence = KickEvidence()
        assertFalse(evidence.confirmed(1L, "a", 0L))
        assertFalse(evidence.confirmed(2L, "b", 0L))
        assertTrue(evidence.confirmed(1L, "b", 0L))
    }

    private fun record(stamp: Long) = RemovedDeviceRecord(
        deviceId = "me", publicKeyHash = "h", lastKnownIp = "", port = 8080, clusterVersion = stamp, removedAt = stamp,
        membershipProtocol = com.fileapex.domain.peer.ClusterClock.MEMBERSHIP_PROTOCOL
    )

    @Test
    fun aRemovalListNamingThisDeviceCountsLikeARevokedAnswer() {
        KickedByPeer.onTombstone(roster[0], record(600L), selfStamp = 100L, nowMs = 1_000L)
        KickedByPeer.onTombstone(roster[1], record(600L), selfStamp = 100L, nowMs = 1_000L)
        settle()
        assertEquals(1, signOuts)
        assertEquals(600L, handled)
    }

    @Test
    fun aRemovalOlderThanOurOwnPairingStampIsIgnored() {
        KickedByPeer.onTombstone(roster[0], record(90L), selfStamp = 100L, nowMs = 1_000L)
        KickedByPeer.onTombstone(roster[1], record(90L), selfStamp = 100L, nowMs = 1_000L)
        settle()
        assertEquals(0, signOuts)
    }
}
