package com.fileapex.network

import com.fileapex.membership.ChainAnswer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnpinnedChainTransportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val answer = ChainAnswer("head-1", emptyList())
    private val body = json.encodeToString(ChainAnswer.serializer(), answer)

    @Test
    fun aGoodAnswerIsDecoded() {
        assertEquals(answer, UnpinnedChainTransport.decode(PeerBoundHttpResponse(200, body), json))
    }

    @Test
    fun noAnswerAnErrorStatusOrGarbageGivesNothing() {
        assertNull(UnpinnedChainTransport.decode(null, json))
        assertNull(UnpinnedChainTransport.decode(PeerBoundHttpResponse(401, body), json))
        assertNull(UnpinnedChainTransport.decode(PeerBoundHttpResponse(200, "not json"), json))
    }
}
