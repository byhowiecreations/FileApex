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
        KickedByPeer.forgetCluster = { 0 }
        KickedByPeer.renewTlsIdentity = {}
        KickedByPeer.announceForgot = {}
    }

    private fun settle() = Thread.sleep(150)

    private var forgets = 0

    @Test
    fun aConfirmedRemovalSetsTheOldClusterAsideEvenWhenGoogleIsNotLinked() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        linked = false
        KickedByPeer.onRevoked("a", 560L, roster, nowMs = 1_000L, reason = "user_removed")
        settle()
        assertEquals(1, forgets)
    }

    @Test
    fun aRemovedDeviceTakesANewTlsKeyOnlyAfterItHasLetGoOfTheOldCluster() {
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        KickedByPeer.forgetCluster = { order += "forget"; 0 }
        KickedByPeer.renewTlsIdentity = { order += "renew" }
        linked = false
        KickedByPeer.onUserRemoved("a", 580L)
        settle()
        assertEquals(listOf("forget", "renew"), order)
    }

    @Test
    fun aNewTlsKeyThatCannotBeMadeDoesNotStopTheDeviceFromLettingGo() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        KickedByPeer.renewTlsIdentity = { error("vault locked") }
        linked = false
        KickedByPeer.onUserRemoved("a", 590L)
        settle()
        assertEquals(1, forgets)
    }

    @Test
    fun oneSightingDoesNotSetTheClusterAside() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        KickedByPeer.onRevoked("a", 570L, roster, nowMs = 1_000L)
        settle()
        assertEquals(0, forgets)
    }

    @Test
    fun aDisabledFirebaseUserSetsTheOldClusterAside() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        KickedByPeer.onFirebaseUserDisabled()
        settle()
        assertEquals(1, forgets)
    }

    @Test
    fun aUserRemovalNoticeSetsTheClusterAsideSignsOutOfGoogleAndIsActedOnceForItsStamp() {
        forgets = 0
        var told = 0
        KickedByPeer.forgetCluster = { forgets++; 5 }
        KickedByPeer.announce = { told++ }
        KickedByPeer.onUserRemoved("MacBook Pro", stamp = 700L)
        KickedByPeer.onUserRemoved("MacBook Pro", stamp = 700L)
        settle()
        assertEquals(1, forgets)
        assertEquals(1, signOuts)
        assertEquals(1, told)
        assertEquals(700L, handled)
    }

    @Test
    fun aUserRemovalNoticeWithoutGoogleOnlyDropsTheRosterAndTellsTheUser() {
        forgets = 0
        var toldForgot = 0
        KickedByPeer.forgetCluster = { forgets++; 5 }
        KickedByPeer.announceForgot = { toldForgot++ }
        linked = false
        KickedByPeer.onUserRemoved("MacBook Pro", stamp = 710L)
        settle()
        assertEquals(1, forgets)
        assertEquals(0, signOuts)
        assertEquals(1, toldForgot)
    }

    @Test
    fun aRevokedAnswerThatSaysUserRemovedFromAPinnedPeerIsActedOnAtOnce() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++; 3 }
        KickedByPeer.onRevoked("a", 800L, roster, nowMs = 1_000L, reason = "user_removed", removedBy = "mac")
        settle()
        assertEquals(1, forgets)
        assertEquals(1, signOuts)
        assertEquals(800L, handled)
    }

    @Test
    fun aRevokedAnswerWithAnotherReasonIsAHintEvenFromTwoPeers() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++; 3 }
        KickedByPeer.onRevoked("a", 810L, roster, nowMs = 1_000L, reason = "superseded", removedBy = "mac")
        KickedByPeer.onRevoked("b", 810L, roster, nowMs = 1_500L, reason = "superseded", removedBy = "mac")
        settle()
        assertEquals(0, forgets)
        assertEquals(0, signOuts)
    }

    @Test
    fun aRevokedAnswerFromAnOldBuildWithNoReasonNeverSetsTheClusterAsideHoweverManyPeersSayIt() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++; 3 }
        KickedByPeer.onRevoked("a", 820L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("b", 820L, roster, nowMs = 1_500L)
        KickedByPeer.onRevoked("a", 820L, roster, nowMs = 1_000L + 3_600_000L)
        settle()
        assertEquals(0, forgets)
        assertEquals(0, signOuts)
        assertEquals(0L, handled)
    }

    @Test
    fun aUserRemovedAnswerFromAnUnpinnedOrRemovedPeerCountsForNothing() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++; 3 }
        KickedByPeer.onRevoked("c", 830L, roster, nowMs = 1_000L, reason = "user_removed", removedBy = "mac")
        KickedByPeer.onRevoked("d", 830L, roster, nowMs = 1_000L, reason = "user_removed", removedBy = "mac")
        KickedByPeer.onRevoked(null, 830L, roster, nowMs = 1_000L, reason = "user_removed", removedBy = "mac")
        settle()
        assertEquals(0, forgets)
    }

    @Test
    fun aDisabledFirebaseUserWithoutAGoogleLinkLeavesTheClusterAlone() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        linked = false
        KickedByPeer.onFirebaseUserDisabled()
        settle()
        assertEquals(0, forgets)
    }

    @Test
    fun aDeviceWithoutGoogleIsToldItWasRemovedOnlyWhenARosterWasDropped() {
        var told = 0
        KickedByPeer.announceForgot = { told++ }
        linked = false
        KickedByPeer.forgetCluster = { 3 }
        KickedByPeer.onRevoked("a", 590L, roster, nowMs = 1_000L, reason = "user_removed")
        settle()
        assertEquals(1, told)
        KickedByPeer.forgetCluster = { 0 }
        KickedByPeer.onRevoked("a", 591L, roster, nowMs = 1_000L, reason = "user_removed")
        settle()
        assertEquals(1, told)
    }

    @Test
    fun aStampThatWasAlreadyHandledDoesNotSetTheClusterAsideAgain() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        KickedByPeer.onRevoked("a", 580L, roster, nowMs = 1_000L, reason = "user_removed")
        KickedByPeer.onRevoked("b", 580L, roster, nowMs = 1_000L, reason = "user_removed")
        KickedByPeer.onRevoked("a", 580L, roster, nowMs = 9_000_000L, reason = "user_removed")
        settle()
        assertEquals(1, forgets)
    }

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
        KickedByPeer.onRevoked("a", 500L, roster, nowMs = 1_000L)
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun twoDifferentPeersWithoutAReasonStillCountForNothing() {
        KickedByPeer.onRevoked("a", 510L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("b", 510L, roster, nowMs = 1_500L)
        settle()
        assertEquals(0, signOuts)
        assertEquals(0L, handled)
    }

    @Test
    fun onePeerTwiceFarEnoughApartStillCountsForNothing() {
        KickedByPeer.onRevoked("a", 520L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("a", 520L, roster, nowMs = 1_000L + 3_600_000L)
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun anAnswerFromAPeerWithoutAPinOrAStrangerOrARemovedPeerCountsForNothing() {
        KickedByPeer.onRevoked("c", 530L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("d", 530L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("stranger", 530L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("c", 530L, roster, nowMs = 1_000L + 3_600_000L, reason = "user_removed")
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun aPeerReachedOverTheTailnetCountsEvenWhenItsLanAddressDiffers() {
        KickedByPeer.onRevoked("a", 535L, roster, nowMs = 1_000L, reason = "user_removed")
        settle()
        assertEquals(1, signOuts)
    }

    @Test
    fun anAnswerThatCouldNotBeTiedToAPeerCountsForNothing() {
        KickedByPeer.onRevoked(null, 536L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked(null, 536L, roster, nowMs = 1_000L + 3_600_000L, reason = "user_removed")
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun anAnswerWithoutAStampDoesNothing() {
        KickedByPeer.onRevoked("a", 0L, roster, nowMs = 1_000L)
        KickedByPeer.onRevoked("b", 0L, roster, nowMs = 1_000L)
        settle()
        assertEquals(0, signOuts)
    }

    @Test
    fun aStampIsActedOnOnlyOnce() {
        KickedByPeer.onRevoked("a", 540L, roster, nowMs = 1_000L, reason = "user_removed")
        KickedByPeer.onRevoked("b", 540L, roster, nowMs = 1_000L, reason = "user_removed")
        KickedByPeer.onRevoked("a", 540L, roster, nowMs = 9_000_000L, reason = "user_removed")
        settle()
        assertEquals(1, signOuts)
    }

    @Test
    fun aDeviceNotLinkedToGoogleOnlyRecordsTheStamp() {
        linked = false
        KickedByPeer.onRevoked("a", 550L, roster, nowMs = 1_000L, reason = "user_removed")
        settle()
        assertEquals(0, signOuts)
        assertEquals(550L, handled)
    }

    private fun record(stamp: Long) = RemovedDeviceRecord(
        deviceId = "me", publicKeyHash = "h", lastKnownIp = "", port = 8080, clusterVersion = stamp, removedAt = stamp,
        membershipProtocol = 2
    )

}
