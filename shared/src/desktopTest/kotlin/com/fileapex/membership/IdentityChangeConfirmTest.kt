package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.security.IdentityBinding
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityChangeConfirmTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val honor = Dev("fx-old")
    private val newE2ee = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)
    private val newId = IdentityBinding.deriveDeviceId(newE2ee)
    private val announced = mutableListOf<List<String>>()

    private val transport = object : ChainTransport {
        override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? { announced += listOf(peerId); return null }
        override suspend fun pull(peerId: String): ChainAnswer? = null
        override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? = null
    }

    private val log = MembershipChainLog(InMemoryChainStore())
    private val tick: ChainTick

    init {
        runBlocking {
            log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
            log.append(mac.signer, RecordKind.ADMIT, honor.id, honor.key, honor.pin, "paired")
            log.append(mac.signer, RecordKind.ADMIT, "tablet", Dev("tablet").key, Dev("tablet").pin, "paired")
        }
        val replicator = ChainReplicator(log, transport, onChanged = {}, profiles = object : ChainProfiles {
            override suspend fun own() = PeerNodeState(deviceId = "mac", deviceName = "m", ipAddress = "10.0.0.1", port = 8080, publicKey = "k", tlsPort = 8443)
            override suspend fun outgoing() = listOf(own())
            override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) = Unit
        })
        tick = ChainTick("mac", log, replicator, transport, ChainBootstrap(log, { mac.signer }), { mac.signer })
    }

    private fun request() = IdentityChangeProof.request(honor.signer, honor.id, newId, newE2ee, "hw", "n1")

    @Test fun confirmingRemovesTheOldIdAdmitsTheNewOneWithTheSameKeysAndTellsTheOthers() = runBlocking {
        val verdict = tick.confirmIdentityChange(request())
        assertTrue(verdict.toString(), verdict is ChainVerdict.Accepted)
        val state = log.view().state
        assertFalse(state.member(honor.id)!!.active)
        assertEquals(honor.key, state.member(newId)!!.key)
        assertEquals(honor.pin, state.member(newId)!!.tlsPin)
        assertTrue(state.member(newId)!!.active)
        assertEquals(listOf("tablet"), announced.flatten().filter { it == "tablet" }.distinct())
    }

    @Test fun aRequestForAnUnknownOldIdWritesNothing() = runBlocking {
        val before = log.view().state.seq
        val stranger = Dev("fx-stranger")
        val verdict = tick.confirmIdentityChange(IdentityChangeProof.request(stranger.signer, stranger.id, newId, newE2ee, "hw", "n1"))
        assertTrue(verdict is ChainVerdict.Rejected)
        assertEquals(before, log.view().state.seq)
    }

    @Test fun ifTheAdmitIsRefusedTheRemovalIsNotLeftAlone() = runBlocking {
        // The old id is already removed: the pair of records must not be half written.
        tick.removeMember(honor.id)
        val before = log.view().state.seq
        val verdict = tick.confirmIdentityChange(request())
        assertTrue(verdict is ChainVerdict.Rejected)
        assertEquals(before, log.view().state.seq)
    }
}
