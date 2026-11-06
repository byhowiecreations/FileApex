package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state field is on the health path of every probe and answer, so building it must cost next to nothing. */
class ChainStateSourceTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private var clock = 1_000_000L
    private var profileReads = 0
    private var name = "Mac"

    private fun source(log: MembershipChainLog) = ChainStateSource(log, now = { clock }) {
        profileReads++
        PeerNodeState(deviceId = "mac", deviceName = name, port = 8080)
    }

    private suspend fun log() = MembershipChainLog(InMemoryChainStore()).also {
        it.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        repeat(100) { if (!check()) Thread.sleep(20) }
        assertTrue(what, check())
    }

    @Test
    fun theOwnProfileIsBuiltOncePerTenSecondsHoweverOftenTheStateIsAsked() = runBlocking {
        val s = source(log())
        repeat(200) { s.current() }
        assertEquals(1, profileReads)
        clock += 9_000
        repeat(50) { s.current() }
        assertEquals(1, profileReads)
        clock += 2_000
        s.current()
        waitFor("rebuilt in the background") { profileReads == 2 }
        repeat(50) { s.current() }
        assertEquals(2, profileReads)
    }

    @Test
    fun aStaleProfileIsAnsweredAtOnceAndRebuiltInTheBackgroundSoNoProbeWaitsForIt() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var slow = false
        val s = ChainStateSource(log(), now = { clock }) {
            profileReads++
            if (slow) gate.await()
            PeerNodeState(deviceId = "mac", deviceName = name, port = 8080)
        }
        val first = s.current()
        slow = true
        clock += 11_000
        val started = System.nanoTime()
        val stale = s.current()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("answered while the rebuild is still running ($tookMs ms)", tookMs < 500)
        assertEquals(first.profile, stale.profile)
        name = "Renamed"
        gate.complete(Unit)
        waitFor("the new digest is in") { runBlocking { s.current() }.profile != first.profile }
    }

    @Test
    fun aNewChainRecordShowsAtOnceWhileTheProfileDigestWaitsForItsTurn() = runBlocking {
        val l = log()
        val s = source(l)
        val before = s.current()
        l.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        val after = s.current()
        assertEquals(before.seq + 1, after.seq)
        assertNotEquals(before.head, after.head)
        assertEquals(before.profile, after.profile)
        assertEquals(1, profileReads)
    }

    @Test
    fun aRenameIsPickedUpWhenTheTurnComes() = runBlocking {
        val s = source(log())
        val before = s.current().profile
        name = "Renamed"
        assertEquals(before, s.current().profile)
        clock += 11_000
        s.current()
        waitFor("the rename shows") { runBlocking { s.current() }.profile != before }
    }

    @Test
    fun aProfileThatIsNotReadyYetIsAskedAgainNextTimeNotRememberedAsEmpty() = runBlocking {
        var ready = false
        val s = ChainStateSource(log(), now = { clock }) {
            profileReads++
            if (ready) PeerNodeState(deviceId = "mac", deviceName = "Mac", port = 8080) else null
        }
        assertEquals("00000000", s.current().profile)
        ready = true
        assertNotEquals("00000000", s.current().profile)
    }
}
