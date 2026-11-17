package com.fileapex.network

import com.fileapex.security.tls.TlsTransportPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The presence route may only be called by a paired peer over pinned TLS: a stranger must not be able to mark a peer online or offline. */
class PresenceRouteTest {
    private val route = "/api/v1/presence"

    @Test
    fun theRouteIsPeerOnly() {
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor(route))
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("$route/"))
    }

    @Test
    fun theRouteRequiresTls() {
        assertTrue(TlsTransportPolicy.requiresTls(route))
    }
}
