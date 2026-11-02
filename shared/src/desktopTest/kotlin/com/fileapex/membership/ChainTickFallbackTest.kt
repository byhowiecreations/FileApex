package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a peer rotates its TLS key while this device is away, the pinned health check fails and the peer is not in
 * [onlineIds]. The tick still pulls over [FallbackChainTransport] and [ChainApplier] stores the new pin silently.
 */
class ChainTickFallbackTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val roster = setOf("mac", "phone")
    private val pins = mutableMapOf("mac" to mac.pin, "phone" to phone.pin)

    private class Book(val self: String) : ChainProfiles {
        override suspend fun own(): PeerNodeState = PeerNodeState(deviceId = self, deviceName = self, port = 8080)
        override suspend fun outgoing(): List<PeerNodeState> = listOf(own())
        override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) = Unit
    }

    private class Effects : ChainEffects {
        override suspend fun refreshRoster() = Unit
        override suspend fun holdRemoval(deviceId: String, chainSeq: Long) = Unit
        override suspend fun removalHeld(deviceId: String, removalSeq: Long) = false
        val pins = mutableMapOf<String, String>()
        override suspend fun knownPin(deviceId: String): String? = pins[deviceId]
        override suspend fun setPin(deviceId: String, pin: String) { pins[deviceId] = pin }
        override suspend fun ensureProfile(deviceId: String) {}
        override suspend fun selfRemoved(by: String, recordHash: String) {}
        override suspend fun handledRemoval(): String = ""
        override suspend fun markHandled(recordHash: String) {}
        override suspend fun knownRemoved(deviceId: String): Boolean = false
        override suspend fun markRemoved(deviceId: String, by: String, reason: String, tlsPin: String) {}
    }

    private class Net {
        val ticks = mutableMapOf<String, ChainTick>()
        val effects = mutableMapOf<String, Effects>()
        val fallbackPulls = mutableMapOf<String, Int>()
        val pinnedFails = mutableSetOf<Pair<String, String>>()

        fun transportFor(self: String): ChainTransport {
            val inner = object : ChainTransport {
                override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? =
                    ticks[peerId]?.replicator?.receive(self, records, profiles)
                override suspend fun pull(peerId: String): ChainAnswer? = ticks[peerId]?.replicator?.serve()
                override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? =
                    ticks[peerId]?.receiveJoin(self, key, tlsPin, ticks.keys, profile)
            }
            val primary = object : ChainTransport {
                override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? =
                    if (self to peerId in pinnedFails) null else inner.push(peerId, records, profiles)
                override suspend fun pull(peerId: String): ChainAnswer? =
                    if (self to peerId in pinnedFails) null else inner.pull(peerId)
                override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? =
                    if (self to peerId in pinnedFails) null else inner.join(peerId, key, tlsPin, profile)
            }
            val fallback = object : ChainTransport {
                override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? = null
                override suspend fun pull(peerId: String): ChainAnswer? {
                    fallbackPulls[self] = (fallbackPulls[self] ?: 0) + 1
                    return inner.pull(peerId)
                }
                override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? = null
            }
            return FallbackChainTransport(primary, fallback)
        }
    }

    private fun node(net: Net, dev: Dev): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        val transport = net.transportFor(dev.id)
        val effects = Effects().also { net.effects[dev.id] = it }
        val apply = suspend { ChainApplier(dev.id, effects).apply(log.view()) }
        val replicator = ChainReplicator(log, transport, onChanged = apply, profiles = Book(dev.id))
        net.ticks[dev.id] = ChainTick(dev.id, log, replicator, transport, ChainBootstrap(log, { dev.signer }), { dev.signer }, onChanged = apply)
        return log
    }

    @Test
    fun aPeerWhosePinnedHealthFailsIsPulledOverTheFallbackAndItsNewPinIsStored() = runBlocking {
        val net = Net()
        val logs = mapOf("mac" to node(net, mac), "phone" to node(net, phone))
        repeat(4) {
            net.ticks.getValue("mac").tick(roster, setOf("phone"), pins.getValue("mac"))
            net.ticks.getValue("phone").tick(roster, setOf("mac"), pins.getValue("phone"))
        }
        assertEquals(roster.sorted(), logs.getValue("phone").view().state.activeIds().sorted())

        net.pinnedFails += "phone" to "mac"
        val newPin = "ab".repeat(32)
        pins["mac"] = newPin
        net.ticks.getValue("mac").tick(roster, emptySet(), newPin)

        net.fallbackPulls["phone"] = 0
        net.ticks.getValue("phone").tick(roster, emptySet(), pins.getValue("phone"))

        assertTrue((net.fallbackPulls["phone"] ?: 0) > 0)
        assertEquals(newPin, logs.getValue("phone").view().state.member("mac")!!.tlsPin)
        assertEquals(newPin, net.effects.getValue("phone").pins["mac"])
        assertTrue("phone" in logs.getValue("phone").view().state.activeIds())
    }
}
