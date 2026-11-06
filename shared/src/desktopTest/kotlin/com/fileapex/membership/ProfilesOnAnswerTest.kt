package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profiles ride on a change or a first meeting; an answer that says "nothing is new" carries none. */
class ProfilesOnAnswerTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")

    private fun state(id: String, ip: String) = PeerNodeState(deviceId = id, deviceName = id, ipAddress = ip, lastKnownIp = ip, port = 8080)

    private val book = object : ChainProfiles {
        override suspend fun own() = state("mac", "10.0.0.1")
        override suspend fun outgoing() = listOf(own(), state("phone", "10.0.0.2"))
        override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) = Unit
    }

    private object NoTransport : ChainTransport {
        override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? = null
        override suspend fun pull(peerId: String): ChainAnswer? = null
        override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? = null
    }

    private suspend fun replicator(): ChainReplicator {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        return ChainReplicator(log, NoTransport, profiles = book)
    }

    @Test
    fun anAnswerToADeviceThatHoldsTheSameHeadCarriesNoProfilesAndNoRecords() = runBlocking {
        val r = replicator()
        val answer = r.serve(since = r.serve().head)
        assertTrue(answer.records.isEmpty())
        assertTrue(answer.profiles.isEmpty())
    }

    @Test
    fun anAnswerToANewMemberOrAnOlderBuildCarriesTheProfilesWithTheirAddresses() = runBlocking {
        val profiles = replicator().serve().profiles.associateBy { it.deviceId }
        assertEquals("10.0.0.2", profiles.getValue("phone").resolvedIpAddress)
        assertEquals(2, profiles.size)
    }

    @Test
    fun anAnswerToADeviceBehindCarriesTheProfilesBecauseSomethingChanged() = runBlocking {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        val first = log.view().state.head
        log.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        val answer = ChainReplicator(log, NoTransport, profiles = book).serve(since = first)
        assertEquals(1, answer.records.size)
        assertEquals(2, answer.profiles.size)
    }

    @Test
    fun aRevokedCallerStillGetsNoProfiles() = runBlocking {
        val r = replicator()
        assertTrue(r.serve(withProfiles = false).profiles.isEmpty())
    }
}
