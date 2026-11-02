package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Two members who removed different devices while apart: the chain keeps one, and its author issues the other again. */
class ChainTickForkTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val devs = listOf("amanda", "mac", "phone", "tablet").map { Dev(it) }.associateBy { it.id }
    private val roster = devs.keys

    private class Net {
        val ticks = mutableMapOf<String, ChainTick>()
        val offline = mutableSetOf<String>()
        fun transportFor(self: String) = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? =
                if (peerId in offline || self in offline) null else ticks.getValue(peerId).replicator.receive(self, records, profiles)

            override suspend fun pull(peerId: String): ChainAnswer? =
                if (peerId in offline || self in offline) null else ticks.getValue(peerId).replicator.serve()

            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? =
                if (peerId in offline || self in offline) null else ticks.getValue(peerId).receiveJoin(self, key, tlsPin, ticks.keys, profile)
        }
    }

    private fun node(net: Net, dev: Dev): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        val transport = net.transportFor(dev.id)
        net.ticks[dev.id] = ChainTick(dev.id, log, ChainReplicator(log, transport), transport, ChainBootstrap(log, { dev.signer }), { dev.signer })
        return log
    }

    private suspend fun round(net: Net, online: Set<String>) {
        for (id in roster) if (id in online) net.ticks.getValue(id).tick(roster, online - id, devs.getValue(id).pin)
    }

    @Test
    fun aRemovalThatLostAForkIsIssuedAgainByItsAuthor() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(5) { round(net, roster) }
        assertEquals(4, logs.getValue("mac").view().state.activeIds().size)
        net.offline += setOf("amanda", "mac")
        net.ticks.getValue("amanda").removeMember("phone")
        net.ticks.getValue("mac").removeMember("tablet")
        net.offline.clear()
        repeat(6) { round(net, roster) }
        assertEquals(listOf("amanda", "mac"), logs.getValue("phone").view().state.activeIds().sorted())
        val heads = logs.values.map { it.view().state.head }.toSet()
        assertEquals(1, heads.size)
    }

    @Test
    fun aRemovalThatIsInTheChainIsNotIssuedAgain() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(5) { round(net, roster) }
        net.ticks.getValue("amanda").removeMember("phone")
        repeat(6) { round(net, roster) }
        assertEquals(1, logs.getValue("mac").view().canonical.count { it.kind == RecordKind.REMOVE })
    }

    @Test
    fun aStoredRemovalThatNamedAnOldKeyIsNeverIssuedAgainAgainstTheSameDeviceWithANewKey() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }.toMutableMap()
        repeat(5) { round(net, roster) }
        val mac = devs.getValue("mac")
        val phone = devs.getValue("phone")
        net.ticks.getValue("mac").removeMember("phone")
        val macLog = logs.getValue("mac")
        val head = macLog.view().state
        val staleAdmit = ChainRecords.sign(
            mac.signer, ChainRecord(head.seq + 1, head.head, RecordKind.ADMIT, "mac", mac.key, "phone", "legacy:phone", phone.pin, "paired")
        )
        val staleRemoval = ChainRecords.sign(
            mac.signer, ChainRecord(head.seq + 2, staleAdmit.hash(), RecordKind.REMOVE, "mac", mac.key, "phone", "legacy:phone", phone.pin, "user_removed")
        )
        macLog.ingest(listOf(staleAdmit, staleRemoval))
        val fresh = Dev("phone")
        logs["phone"] = node(net, fresh)
        repeat(6) {
            for (id in roster) net.ticks.getValue(id).tick(roster, roster - id, if (id == "phone") fresh.pin else devs.getValue(id).pin)
        }
        assertEquals(fresh.key, macLog.view().state.member("phone")!!.key)
        assertEquals(true, macLog.view().state.member("phone")!!.active)
        assertEquals(1, macLog.view().canonical.count { it.kind == RecordKind.REMOVE })
    }
}
