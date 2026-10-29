package com.fileapex.domain.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DeduperStateCodecTest {
    @Test
    fun theStateRoundTrips() {
        val state = PersistedDeduperState("abc", mapOf("h1" to 100L, "h2" to 200L))
        assertEquals(state, DeduperStateCodec.decode(DeduperStateCodec.encode(state.lastHash, state.seenHashes)))
    }

    @Test
    fun noLastHashStaysNull() {
        assertEquals(null, DeduperStateCodec.decode(DeduperStateCodec.encode(null, mapOf("h" to 5L))).lastHash)
    }

    @Test
    fun emptyOrBrokenTextGivesAnEmptyState() {
        assertEquals(PersistedDeduperState(null, emptyMap()), DeduperStateCodec.decode(""))
        assertEquals(PersistedDeduperState(null, emptyMap()), DeduperStateCodec.decode("not json"))
    }

    @Test
    fun entriesWithoutAHashOrATimestampAreDropped() {
        val text = """{"seen":{"":5,"h":0,"ok":7}}"""
        assertEquals(mapOf("ok" to 7L), DeduperStateCodec.decode(text).seenHashes)
    }
}
