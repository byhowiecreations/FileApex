package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

class HeldRemovalRouteTest {
    @After
    fun reset() {
        PeerTlsRoutes.update(emptyList())
        PeerTlsRoutes.onChange = null
    }

    @Test
    fun aHeldRouteReachesARemovedPeerOverItsPinUntilClosed() {
        PeerTlsRoutes.update(emptyList())
        assertNull(PeerTlsRoutes.lookup("10.0.0.5", 8080))

        val hold = PeerTlsRoutes.holdRoute("fx-gone", setOf("pin-a"), "10.0.0.5", 8080, 8081)

        val route = PeerTlsRoutes.lookup("10.0.0.5", 8080)!!
        assertEquals("fx-gone", route.deviceId)
        assertEquals(setOf("pin-a"), route.pins)
        assertEquals(8081, route.tlsPort)
        hold.close()
        assertNull(PeerTlsRoutes.lookup("10.0.0.5", 8080))
    }

    @Test
    fun theNativeClientIsToldAboutAHeldRouteAndItsEnd() {
        val pushed = mutableListOf<Int>()
        PeerTlsRoutes.onChange = { pushed += it.size }
        val hold = PeerTlsRoutes.holdRoute("fx-gone", setOf("pin-a"), "10.0.0.5", 8080, 8081)
        hold.close()
        assertEquals(listOf(1, 0), pushed)
    }

    @Test
    fun aRosterUpdateKeepsAHeldRoute() {
        val hold = PeerTlsRoutes.holdRoute("fx-gone", setOf("pin-a"), "10.0.0.5", 8080, 8081)
        PeerTlsRoutes.update(emptyList<PairedDeviceEntity>())
        assertNotNull(PeerTlsRoutes.lookup("10.0.0.5", 8080))
        hold.close()
    }
}
