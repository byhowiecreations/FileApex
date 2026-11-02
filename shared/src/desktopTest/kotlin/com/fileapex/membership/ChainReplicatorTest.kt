package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainReplicatorTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")

    /** Devices that reach each other directly; a device in [offline] does not answer. */
    private class Net {
        val nodes = mutableMapOf<String, ChainReplicator>()
        val offline = mutableSetOf<String>()
        fun transportFor(self: String) = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? =
                if (peerId in offline) null else nodes.getValue(peerId).receive(self, records, profiles)

            override suspend fun pull(peerId: String): ChainAnswer? =
                if (peerId in offline) null else nodes.getValue(peerId).serve()

            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? = null
        }
    }

    private fun node(net: Net, id: String): Pair<MembershipChainLog, ChainReplicator> {
        val log = MembershipChainLog(InMemoryChainStore())
        val replicator = ChainReplicator(log, net.transportFor(id))
        net.nodes[id] = replicator
        return log to replicator
    }

    private suspend fun MembershipChainLog.start() {
        append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
    }

    @Test
    fun reconcilePullsWhatThePeerHasAndWeLack() = runBlocking {
        val net = Net()
        val (a, ra) = node(net, "a")
        val (b, _) = node(net, "b")
        b.start()
        assertTrue(ra.reconcile("b"))
        assertEquals(b.view().state, a.view().state)
    }

    @Test
    fun reconcilePushesWhatThePeerLacks() = runBlocking {
        val net = Net()
        val (a, ra) = node(net, "a")
        val (b, _) = node(net, "b")
        a.start()
        assertTrue(ra.reconcile("b"))
        assertEquals(a.view().state, b.view().state)
    }

    @Test
    fun anUnreachablePeerChangesNothingAndIsReported() = runBlocking {
        val net = Net()
        val (a, ra) = node(net, "a")
        node(net, "b")
        a.start()
        net.offline += "b"
        assertFalse(ra.reconcile("b"))
        assertEquals(2L, a.view().state.seq)
    }

    @Test
    fun announceSendsTheChainToEveryNamedPeerThatIsReachable() = runBlocking {
        val net = Net()
        val (a, ra) = node(net, "a")
        val (b, _) = node(net, "b")
        val (c, _) = node(net, "c")
        a.start()
        net.offline += "c"
        ra.announce(listOf("b", "c"))
        assertEquals(2L, b.view().state.seq)
        assertEquals(0L, c.view().state.seq)
    }

    @Test
    fun aDeviceThatWasOfflineCatchesUpOnItsNextContactWithAnyone() = runBlocking {
        val net = Net()
        val (a, _) = node(net, "a")
        val (b, rb) = node(net, "b")
        val (c, rc) = node(net, "c")
        a.start()
        a.copyAllTo(b)
        net.offline += "c"
        a.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        a.copyAllTo(b)
        net.offline -= "c"
        assertTrue(rc.reconcile("b"))
        assertEquals(b.view().state, c.view().state)
        assertEquals(3L, c.view().state.seq)
    }

    @Test
    fun forkedDevicesConvergeAfterTheyMeet() = runBlocking {
        val net = Net()
        val (a, ra) = node(net, "a")
        val (b, _) = node(net, "b")
        a.start()
        a.copyAllTo(b)
        a.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        b.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        ra.reconcile("b")
        assertEquals(a.view().state.head, b.view().state.head)
        assertEquals(listOf("mac"), a.view().state.activeIds())
    }

    private suspend fun MembershipChainLog.copyAllTo(other: MembershipChainLog) { other.ingest(recordsAfter(0L)) }
}
