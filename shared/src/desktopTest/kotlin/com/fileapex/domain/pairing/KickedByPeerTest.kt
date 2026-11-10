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
        pulls.clear(); rosterPulls = 0
        KickedByPeer.pullChainFrom = { pulls += it }
        KickedByPeer.pullChainFromRoster = { rosterPulls++ }
    }

    private val pulls = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var rosterPulls = 0

    private fun settle() = Thread.sleep(150)

    private var forgets = 0

    // The wipe has one trigger: a verified REMOVE of this device on the chain (ChainApplier -> onUserRemoved).

    @Test
    fun aConfirmedRemovalSetsTheOldClusterAsideEvenWhenGoogleIsNotLinked() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        linked = false
        KickedByPeer.onUserRemoved("a", 560L)
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
    fun aUserRemovalSetsTheClusterAsideSignsOutOfGoogleAndIsActedOnceForItsStamp() {
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
    fun aUserRemovalWithoutGoogleOnlyDropsTheRosterAndTellsTheUser() {
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
    fun aDeviceWithoutGoogleIsToldItWasRemovedOnlyWhenARosterWasDropped() {
        var told = 0
        KickedByPeer.announceForgot = { told++ }
        linked = false
        KickedByPeer.forgetCluster = { 3 }
        KickedByPeer.onUserRemoved("a", 590L)
        settle()
        assertEquals(1, told)
        KickedByPeer.forgetCluster = { 0 }
        KickedByPeer.onUserRemoved("a", 591L)
        settle()
        assertEquals(1, told)
    }

    @Test
    fun aStampThatWasAlreadyHandledDoesNotSetTheClusterAsideAgain() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        KickedByPeer.onUserRemoved("a", 580L)
        KickedByPeer.onUserRemoved("b", 580L)
        settle()
        assertEquals(1, forgets)
    }

    @Test
    fun aDeviceNotLinkedToGoogleOnlyRecordsTheStamp() {
        linked = false
        KickedByPeer.onUserRemoved("a", 550L)
        settle()
        assertEquals(0, signOuts)
        assertEquals(550L, handled)
    }

    // 0.20.10 (one trust path): a "401 revoked" answer is a hint. It wipes nothing, whatever it claims, and makes this
    // device pull the chain from that pinned peer, where a real removal would be a signed record.

    @Test
    fun aRevokedAnswerFromAPinnedPeerOnlyMakesThisDevicePullTheChainFromIt() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++; 3 }
        KickedByPeer.onRevoked("a", roster)
        settle()
        assertEquals(0, forgets)
        assertEquals(0, signOuts)
        assertEquals(0L, handled)
        assertEquals(listOf("a"), pulls.toList())
    }

    @Test
    fun aRevokedAnswerFromAnUnpinnedRemovedUnknownOrUntiedPeerCausesNothing() {
        forgets = 0
        KickedByPeer.forgetCluster = { forgets++ }
        KickedByPeer.onRevoked("c", roster)
        KickedByPeer.onRevoked("d", roster)
        KickedByPeer.onRevoked("stranger", roster)
        KickedByPeer.onRevoked(null, roster)
        settle()
        assertEquals(0, forgets)
        assertTrue(pulls.isEmpty())
    }

    // A disabled Firebase user ends the cloud session only; the cluster stays until the chain says otherwise.

    @Test
    fun aDisabledFirebaseUserEndsTheCloudSessionKeepsTheClusterAndPullsTheChain() {
        forgets = 0
        var keyDrops = 0
        KickedByPeer.forgetCluster = { forgets++ }
        KickedByPeer.dropClusterKey = { keyDrops++ }
        KickedByPeer.onFirebaseUserDisabled()
        settle()
        assertEquals(1, signOuts)
        assertEquals(0, forgets)
        assertEquals(0, keyDrops)
        assertEquals(1, rosterPulls)
    }

    @Test
    fun aDisabledFirebaseUserDoesNothingWhenGoogleIsNotLinked() {
        linked = false
        KickedByPeer.onFirebaseUserDisabled()
        settle()
        assertEquals(0, signOuts)
        assertEquals(0, rosterPulls)
    }

    private fun record(stamp: Long) = RemovedDeviceRecord(
        deviceId = "me", publicKeyHash = "h", lastKnownIp = "", port = 8080, clusterVersion = stamp, removedAt = stamp,
        membershipProtocol = 2
    )

}
