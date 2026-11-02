package com.fileapex.membership

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackChainTransportTest {
    private val answer = ChainAnswer("head", emptyList())

    private class Fake(var pullAnswer: ChainAnswer?, var pushAnswer: ChainAnswer? = null) : ChainTransport {
        var pulls = 0
        var pushes = 0
        override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? { pushes++; return pushAnswer }
        override suspend fun pull(peerId: String): ChainAnswer? { pulls++; return pullAnswer }
        override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? = null
    }

    @Test
    fun aPullThatWorksOverThePinnedRouteNeverTouchesTheFallback() = runBlocking {
        val primary = Fake(answer)
        val fallback = Fake(ChainAnswer("other", emptyList()))
        assertEquals(answer, FallbackChainTransport(primary, fallback).pull("p"))
        assertEquals(0, fallback.pulls)
    }

    @Test
    fun aPullThatFailsOverThePinnedRouteIsTriedOverTheFallback() = runBlocking {
        val primary = Fake(null)
        val fallback = Fake(answer)
        assertEquals(answer, FallbackChainTransport(primary, fallback).pull("p"))
        assertEquals(1, fallback.pulls)
    }

    @Test
    fun aPushIsNeverSentOverTheFallback() = runBlocking {
        val primary = Fake(null, pushAnswer = null)
        val fallback = Fake(answer, pushAnswer = answer)
        assertNull(FallbackChainTransport(primary, fallback).push("p", emptyList(), emptyList()))
        assertEquals(0, fallback.pushes)
    }
}
