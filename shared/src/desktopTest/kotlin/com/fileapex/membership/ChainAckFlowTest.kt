package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A change is announced once; each device's answer is its ack, a silent one is asked again, and nothing depends on the book surviving. */
class ChainAckFlowTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")

    private class Rig {
        val reports = mutableListOf<String>()
        var pokes = 0
        val offline = mutableSetOf<String>()
        val replicators = mutableMapOf<String, ChainReplicator>()
        val logs = mutableMapOf<String, MembershipChainLog>()
        val books = mutableMapOf<String, ChainAckBook>()

        fun transport(self: String) = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? =
                push(peerId, records, profiles, "")

            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>, head: String): ChainAnswer? =
                if (peerId in offline || peerId !in replicators) null else replicators.getValue(peerId).receive(self, records, profiles, since = head)

            override suspend fun pull(peerId: String): ChainAnswer? = pull(peerId, "")

            override suspend fun pull(peerId: String, since: String): ChainAnswer? =
                if (peerId in offline || peerId !in replicators) null else replicators.getValue(peerId).serve(since = since)

            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? = null
        }

        fun node(id: String): ChainReplicator {
            val log = MembershipChainLog(InMemoryChainStore()).also { logs[id] = it }
            val book = ChainAckBook(now = { 1_000_000L }, descends = { change, theirs -> log.descendsOrEquals(theirs, change) }, report = { reports += "$id: $it" })
            books[id] = book
            return ChainReplicator(log, transport(id), acks = book, afterAnnounce = { pokes++ }).also { replicators[id] = it }
        }
    }

    private suspend fun cluster(rig: Rig): Triple<ChainReplicator, ChainReplicator, ChainReplicator> {
        val a = rig.node("mac")
        val b = rig.node("phone")
        val c = rig.node("tablet")
        val log = rig.logs.getValue("mac")
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        a.announce(listOf("phone", "tablet"))
        rig.reports.clear()
        rig.pokes = 0
        return Triple(a, b, c)
    }

    @Test
    fun everyAnswerIsAnAckAndTheClusterIsLoggedAsUpdated() = runBlocking {
        val rig = Rig()
        val (a, _, _) = cluster(rig)
        rig.logs.getValue("mac").append(mac.signer, RecordKind.REMOVE, tablet.id, tablet.key, tablet.pin, MembershipReason.USER_REMOVED)
        a.announce(listOf("phone", "tablet"), removed = setOf("tablet"))
        assertEquals(1, rig.reports.count { it.contains("cluster updated") })
        assertEquals(1, rig.pokes)
    }

    @Test
    fun aSilentPeerIsAskedAgainAndTheClusterIsLoggedWhenItAnswers() = runBlocking {
        val rig = Rig()
        val (a, _, _) = cluster(rig)
        rig.offline += "phone"
        rig.logs.getValue("mac").append(mac.signer, RecordKind.ADMIT, "newbie", mac.key, mac.pin, "paired")
        a.announce(listOf("phone", "tablet"))
        assertTrue(rig.reports.none { it.contains("cluster updated") })
        assertEquals(setOf("phone"), rig.books.getValue("mac").pending())
        rig.offline -= "phone"
        a.retryPending()
        assertEquals(1, rig.reports.count { it.contains("cluster updated") })
        assertEquals(rig.logs.getValue("mac").view().state.head, rig.logs.getValue("phone").view().state.head)
    }

    @Test
    fun aPullThatAdvancedTheDeviceTellsTheSenderWithABareAck() = runBlocking {
        val rig = Rig()
        val (a, b, _) = cluster(rig)
        rig.offline += "phone"
        rig.logs.getValue("mac").append(mac.signer, RecordKind.ADMIT, "newbie", mac.key, mac.pin, "paired")
        a.announce(listOf("phone"))
        rig.offline -= "phone"
        assertEquals(setOf("phone"), rig.books.getValue("mac").pending())
        assertTrue(b.reconcile("mac"))
        assertEquals(emptySet<String>(), rig.books.getValue("mac").pending())
        assertEquals(1, rig.reports.count { it.contains("cluster updated") })
    }

    @Test
    fun aRestartOfTheSenderLosesTheBookAndTheClusterStillConverges() = runBlocking {
        val rig = Rig()
        val (a, b, _) = cluster(rig)
        rig.offline += "phone"
        rig.logs.getValue("mac").append(mac.signer, RecordKind.ADMIT, "newbie", mac.key, mac.pin, "paired")
        a.announce(listOf("phone"))
        rig.books.remove("mac")
        rig.offline -= "phone"
        assertTrue(b.reconcile("mac"))
        assertEquals(rig.logs.getValue("mac").view().state.head, rig.logs.getValue("phone").view().state.head)
    }

    @Test
    fun aRetryThatFailsIsReportedAndNeverCrashesTheScope() = runBlocking {
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val reports = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> uncaught += e })
        var calls = 0
        val transport = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? {
                if (calls++ > 0) error("the network layer broke")
                return null
            }
            override suspend fun pull(peerId: String): ChainAnswer? = null
            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? = null
        }
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        val book = ChainAckBook(now = { 1_000_000L }, descends = { _, _ -> false })
        val replicator = ChainReplicator(
            log, transport, acks = book, scope = scope, retryDelaysMs = 10L to 20L, report = { reports += it }
        )
        replicator.announce(listOf("phone"))
        repeat(50) { if (reports.isEmpty()) Thread.sleep(20) }
        assertTrue("the failure is reported: $reports", reports.any { it.contains("retry") })
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    @Test
    fun nobodyToTellMeansNoExpectationAndNoPoke() = runBlocking {
        val rig = Rig()
        val (a, _, _) = cluster(rig)
        a.announce(emptyList())
        assertEquals(0, rig.pokes)
        assertEquals(0, rig.books.getValue("mac").open())
    }
}
