package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceDigestRoundsTest {
    private class View(var online: Set<String> = emptySet(), var usable: Boolean = true) : DigestPeerView {
        var reachable: Set<String> = emptySet()
        var fetches = 0
        override fun hasUsableEndpoint(peer: PairedDeviceEntity) = usable
        override fun isOnline(peer: PairedDeviceEntity) = peer.deviceId in online
        override fun wasRecentlyReachable(deviceId: String, withinMs: Long) = deviceId in reachable
        override suspend fun liveDevice(deviceId: String): PairedDeviceEntity? = null
        override suspend fun endpointFor(peer: PairedDeviceEntity) = PeerLanReachabilityVerdict.Direct("10.0.0.2", 8080)
        override suspend fun fetchDigest(host: String, port: Int, ask: PresenceDigestMessage): PresenceDigestMessage? {
            fetches++
            return null
        }
    }

    private fun device(id: String, key: String = "pub-$id", root: String = "/data") = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "10.0.0.1", port = 8080, publicKeyHash = "",
        rootPath = root, publicKey = key
    )

    private val four = listOf("A", "B", "C", "D").map { device(it) }

    @Test
    fun roundIsDoneOnlyWhenEverySourceFinishedOnce() {
        val round = DigestRound(setOf("A", "B"))
        round.finish("Z")
        round.finish("A")
        round.finish("A")
        assertFalse(round.done.isCompleted)
        round.finish("B")
        assertTrue(round.done.isCompleted)
    }

    @Test
    fun fewerThanFourPeersNeverStartARound() {
        val view = View(online = setOf("A", "B", "C"))
        assertNull(PresenceDigestRounds(view).startRound(four.take(3)))
    }

    @Test
    fun sourcesAreOnlineKeyedPeersCappedAtTwoInNameOrder() {
        val view = View(online = setOf("A", "B", "C", "D"))
        val peers = listOf(device("D"), device("C"), device("B", key = ""), device("A"))
        val round = PresenceDigestRounds(view).startRound(peers)!!
        assertEquals(setOf("A", "C"), round.sourceIds)
    }

    @Test
    fun offlinePeersAreNotSources() {
        val view = View(online = setOf("D"))
        val round = PresenceDigestRounds(view).startRound(four)!!
        assertEquals(setOf("D"), round.sourceIds)
    }

    @Test
    fun noHintMeansNoSkip() {
        val rounds = PresenceDigestRounds(View(online = setOf("A")))
        assertFalse(rounds.allowsSkip(device("A"), emptyMap()))
    }

    @Test
    fun aFreshHintSkipsOnceUntilDirectContact() {
        val rounds = PresenceDigestRounds(View(online = setOf("A")))
        val hints = mapOf("A" to TimeUtils.now())
        assertTrue(rounds.allowsSkip(device("A"), hints))
        assertFalse(rounds.allowsSkip(device("A"), hints))
        rounds.skips.directContact("A")
        assertTrue(rounds.allowsSkip(device("A"), hints))
    }

    @Test
    fun staleHintOrIncompleteMetadataNeverSkips() {
        val rounds = PresenceDigestRounds(View(online = setOf("A")))
        assertFalse(rounds.allowsSkip(device("A"), mapOf("A" to TimeUtils.now() - 10 * 60_000L)))
        assertFalse(rounds.allowsSkip(device("A", root = "/"), mapOf("A" to TimeUtils.now())))
        assertFalse(rounds.allowsSkip(device("A", root = ""), mapOf("A" to TimeUtils.now())))
    }

    @Test
    fun sourceNotRecentlyReachableIsNotAsked() {
        val view = View(online = setOf("A"))
        val hints = runBlocking { PresenceDigestRounds(view).collectHints(listOf(device("A")), 60_000L) }
        assertTrue(hints.isEmpty())
        assertEquals(0, view.fetches)
    }
}
