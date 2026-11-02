package com.fileapex.network

import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainBootstrap
import com.fileapex.membership.ChainJoinRequest
import com.fileapex.membership.ChainPush
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainTick
import com.fileapex.membership.ChainTransport
import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.InMemoryChainStore
import com.fileapex.membership.MembershipChainLog
import com.fileapex.membership.MembershipKeys
import com.fileapex.network.routes.ChainRouteHandlers
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRouteHandlersTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val amanda = Dev("amanda")
    private val mac = Dev("mac")
    private val roster = setOf("amanda", "mac")

    private val noNetwork = object : ChainTransport {
        override suspend fun push(peerId: String, records: List<com.fileapex.membership.ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? = null
        override suspend fun pull(peerId: String): ChainAnswer? = null
        override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? = null
    }

    private fun tick(): Pair<MembershipChainLog, ChainTick> {
        val log = MembershipChainLog(InMemoryChainStore())
        val replicator = ChainReplicator(log, noNetwork)
        return log to ChainTick("amanda", log, replicator, noNetwork, ChainBootstrap(log, { amanda.signer }), { amanda.signer })
    }

    private suspend fun started(): Pair<MembershipChainLog, ChainTick> = tick().also { (_, t) -> t.tick(roster, emptySet(), amanda.pin) }

    @Test
    fun aRosterDeviceThatAsksIsAdmittedAndGetsTheChainBack() = runBlocking {
        val (log, tick) = started()
        val body = json.encodeToString(ChainJoinRequest.serializer(), ChainJoinRequest(mac.key, mac.pin))
        val reply = json.decodeFromString(ChainAnswer.serializer(), ChainRouteHandlers.join(tick, "mac", mac.pin, body, roster, json))
        assertEquals(listOf("amanda", "mac"), log.view().state.activeIds().sorted())
        assertEquals(log.view().state.head, reply.head)
    }

    @Test
    fun aCallerThatIsNotOnTheRosterIsNotAdmitted() = runBlocking {
        val (log, tick) = started()
        val body = json.encodeToString(ChainJoinRequest.serializer(), ChainJoinRequest(mac.key, mac.pin))
        ChainRouteHandlers.join(tick, "stranger", mac.pin, body, roster, json)
        assertEquals(listOf("amanda"), log.view().state.activeIds())
    }

    @Test
    fun aBrokenBodyIsReportedToTheRouteAsAnError() = runBlocking {
        val (_, tick) = started()
        val failure = runCatching { ChainRouteHandlers.join(tick, "mac", mac.pin, "{nope", roster, json) }.exceptionOrNull()
        assertTrue(failure is SerializationException)
    }

    @Test
    fun aPushIsIngestedAndAnsweredWithTheChain() = runBlocking {
        val (aLog, aTick) = started()
        val (bLog, bTick) = tick()
        val body = json.encodeToString(ChainPush.serializer(), ChainPush(aLog.recordsAfter(0L)))
        val reply = json.decodeFromString(ChainAnswer.serializer(), ChainRouteHandlers.push(bTick.replicator, "amanda", body, json))
        assertEquals(aLog.view().state.head, bLog.view().state.head)
        assertEquals(bLog.view().state.head, reply.head)
        assertEquals(reply, json.decodeFromString(ChainAnswer.serializer(), ChainRouteHandlers.pull(aTick.replicator, json)))
    }

    @Test
    fun theJoinUsesTheKeyOfTheConnectionAndNotTheOneInTheBody() = runBlocking {
        val (log, tick) = started()
        val claimed = "ee".repeat(32)
        val body = json.encodeToString(ChainJoinRequest.serializer(), ChainJoinRequest(mac.key, claimed))
        ChainRouteHandlers.join(tick, "mac", mac.pin, body, roster, json)
        assertEquals(mac.pin, log.view().state.member("mac")!!.tlsPin)
    }
}
