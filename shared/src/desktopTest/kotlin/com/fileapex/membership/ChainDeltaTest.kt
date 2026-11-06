package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chain is a log with a position: a device that is behind is sent what came after its head, not everything. */
class ChainDeltaTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")
    private val laptop = Dev("laptop")

    private suspend fun fourRecords(): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, laptop.id, laptop.key, laptop.pin, "paired")
        return log
    }

    @Test
    fun aKnownHeadGivesOnlyTheRecordsAboveIt() = runBlocking {
        val log = fourRecords()
        val second = log.recordsAfter(0L).first { it.seq == 2L }
        val delta = log.recordsAfterHead(second.hash())
        assertNotNull(delta)
        assertEquals(listOf(3L, 4L), delta!!.map { it.seq })
    }

    @Test
    fun theOwnHeadGivesNothing() = runBlocking {
        val log = fourRecords()
        assertEquals(emptyList<ChainRecord>(), log.recordsAfterHead(log.view().state.head))
    }

    @Test
    fun anUnknownOrEmptyHeadGivesNoDeltaSoTheCallerSendsEverything() = runBlocking {
        val log = fourRecords()
        assertNull(log.recordsAfterHead("f".repeat(64)))
        assertNull(log.recordsAfterHead(""))
    }

    @Test
    fun aHeadOnALosingBranchGivesNoDelta() = runBlocking {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        val parent = log.view().state
        val a = ChainRecords.sign(mac.signer, ChainRecord(parent.seq + 1, parent.head, RecordKind.ADMIT, mac.id, mac.key, tablet.id, tablet.key, tablet.pin, "paired"))
        val b = ChainRecords.sign(mac.signer, ChainRecord(parent.seq + 1, parent.head, RecordKind.ADMIT, mac.id, mac.key, laptop.id, laptop.key, laptop.pin, "paired"))
        log.ingest(listOf(a, b))
        val winner = MembershipChain.preferred(a, b)
        val loser = if (winner === a) b else a
        assertEquals(winner.hash(), log.view().state.head)
        assertNull(log.recordsAfterHead(loser.hash()))
        assertEquals(emptyList<ChainRecord>(), log.recordsAfterHead(winner.hash()))
    }

    @Test
    fun serveAnswersByWhatTheAskerHolds() = runBlocking {
        val log = fourRecords()
        val replicator = ChainReplicator(log, NoTransport)
        val head = log.view().state.head
        val second = log.recordsAfter(0L).first { it.seq == 2L }
        assertEquals(emptyList<ChainRecord>(), replicator.serve(since = head).records)
        assertEquals(head, replicator.serve(since = head).head)
        assertEquals(listOf(3L, 4L), replicator.serve(since = second.hash()).records.map { it.seq })
        assertEquals(4, replicator.serve(since = "f".repeat(64)).records.size)
        assertEquals(4, replicator.serve().records.size)
    }

    /**
     * Records which `since` each pull carried and which records each push carried. With [echo] a pushed-to peer answers
     * with the head it was sent (it caught up); otherwise it answers with [pushAnswer].
     */
    private class Spy(var pullAnswer: ChainAnswer?, var pushAnswer: ChainAnswer? = null, var echo: Boolean = false) : ChainTransport {
        val pulledSince = mutableListOf<String>()
        val pushed = mutableListOf<List<ChainRecord>>()
        override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? {
            pushed += records
            return pushAnswer
        }
        override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>, head: String): ChainAnswer? {
            push(peerId, records, profiles)
            return if (echo) ChainAnswer(head, emptyList()) else pushAnswer
        }
        override suspend fun pull(peerId: String): ChainAnswer? = pullAnswer
        override suspend fun pull(peerId: String, since: String): ChainAnswer? { pulledSince += since; return pullAnswer }
        override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? = null
    }

    @Test
    fun reconcileAsksWithItsHeadAndIngestsNothingWhenEqual() = runBlocking {
        val log = fourRecords()
        val head = log.view().state.head
        val spy = Spy(ChainAnswer(head, emptyList()))
        val replicator = ChainReplicator(log, spy)
        assertTrue(replicator.reconcile("peer"))
        assertEquals(listOf(head), spy.pulledSince)
        assertTrue(spy.pushed.isEmpty())
    }

    @Test
    fun reconcileAdoptsAChangeSentAsADelta() = runBlocking {
        val full = fourRecords()
        val behind = MembershipChainLog(InMemoryChainStore())
        behind.ingest(full.recordsAfter(0L).filter { it.seq <= 2L })
        val delta = full.recordsAfter(0L).filter { it.seq > 2L }
        val spy = Spy(ChainAnswer(full.view().state.head, delta))
        val replicator = ChainReplicator(behind, spy)
        replicator.reconcile("peer")
        assertEquals(full.view().state, behind.view().state)
        assertTrue("only the bare ack, no records", spy.pushed.all { it.isEmpty() })
    }

    @Test
    fun reconcilePushesOnlyWhatThePeerLacksWhenItsHeadIsKnown() = runBlocking {
        val log = fourRecords()
        val second = log.recordsAfter(0L).first { it.seq == 2L }
        val spy = Spy(ChainAnswer(second.hash(), emptyList()), echo = true)
        ChainReplicator(log, spy).reconcile("peer")
        assertEquals(listOf(3L, 4L), spy.pushed.single().map { it.seq })
    }

    @Test
    fun reconcilePushesEverythingToAPeerWhoseHeadWeDoNotKnow() = runBlocking {
        val log = fourRecords()
        val spy = Spy(ChainAnswer("e".repeat(64), emptyList()), echo = true)
        ChainReplicator(log, spy).reconcile("peer")
        assertEquals(4, spy.pushed.single().size)
    }

    @Test
    fun announceSendsADeltaToAPeerWhoseHeadWasSeenAndEverythingToOthers() = runBlocking {
        val log = fourRecords()
        val spy = Spy(ChainAnswer(log.view().state.head, emptyList()), echo = true)
        val replicator = ChainReplicator(log, spy)
        replicator.reconcile("known")
        log.append(mac.signer, RecordKind.REMOVE, laptop.id, laptop.key, "", MembershipReason.USER_REMOVED)
        spy.pushed.clear()
        replicator.announce(listOf("known"))
        assertEquals(listOf(5L), spy.pushed.single().map { it.seq })
        spy.pushed.clear()
        replicator.announce(listOf("stranger"))
        assertEquals(5, spy.pushed.single().size)
    }

    @Test
    fun aDeltaThePeerCouldNotUseIsFollowedByTheWholeChainOnce() = runBlocking {
        val log = fourRecords()
        val second = log.recordsAfter(0L).first { it.seq == 2L }
        val spy = Spy(ChainAnswer(second.hash(), emptyList()), pushAnswer = ChainAnswer(second.hash(), emptyList()))
        val replicator = ChainReplicator(log, spy)
        replicator.reconcile("peer")
        spy.pushed.clear()
        replicator.announce(listOf("peer"))
        assertEquals(listOf(2, 4), spy.pushed.map { it.size })
    }

    @Test
    fun receiveAnswersByTheHeadThePusherSent() = runBlocking {
        val log = fourRecords()
        val replicator = ChainReplicator(log, NoTransport)
        val head = log.view().state.head
        assertEquals(emptyList<ChainRecord>(), replicator.receive("peer", emptyList(), emptyList(), since = head).records)
        assertEquals(4, replicator.receive("peer", emptyList(), emptyList()).records.size)
    }

    @Test
    fun anOldPushWithoutAHeadStillDecodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(ChainPush.serializer(), """{"records":[],"profiles":[]}""")
        assertEquals("", old.head)
    }

    private object NoTransport : ChainTransport {
        override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? = null
        override suspend fun pull(peerId: String): ChainAnswer? = null
        override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? = null
    }
}
