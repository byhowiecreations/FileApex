package com.fileapex.domain.presence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerGateTest {
    @Test
    fun offlinePeerGetsNoRouteAndNothingIsResolved() = runBlocking {
        var lookups = 0
        val route = PeerGate.ifOnline(isOnline = false) {
            lookups++
            "172.16.16.88" to 8080
        }
        assertNull(route)
        assertEquals(0, lookups)
    }

    @Test
    fun onlinePeerResolvesExactlyOnce() = runBlocking {
        var lookups = 0
        val route = PeerGate.ifOnline(isOnline = true) {
            lookups++
            "172.16.16.88" to 8080
        }
        assertEquals("172.16.16.88" to 8080, route)
        assertEquals(1, lookups)
    }

    @Test
    fun onlinePeerWithNoRouteGivesNull() = runBlocking {
        assertNull(PeerGate.ifOnline<Pair<String, Int>>(isOnline = true) { null })
    }
}
