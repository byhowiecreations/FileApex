package com.fileapex.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAuthPolicyTest {
    @Test
    fun theOpenSetIsExactlyWhatWasDecided() {
        assertEquals(
            setOf("/", "/share", "/api/v1/health", "/api/v1/identity", "/api/v1/tls/pin"),
            RouteAuthPolicy.OPEN_EXACT
        )
        assertEquals(listOf("/api/v1/pairing", "/api/v1/auth", "/api/v1/web"), RouteAuthPolicy.OPEN_PREFIXES)
    }

    @Test
    fun fileNoteBulletinAndRosterRoutesNeedAPeer() {
        listOf(
            "/api/v1/files/list", "/api/v1/files/upload", "/api/v1/files/delete", "/api/v1/files/stream",
            "/api/v1/devices", "/api/v1/devices/merge", "/api/v1/cluster/introductions", "/api/v1/cluster/remove", "/cluster/remove",
            "/api/v1/bulletin/delta", "/api/v1/bulletin/sync/batch", "/api/v1/bulletin/file",
            "/api/v1/notes/send", "/api/v1/notes/delete", "/api/v1/clipboard/send", "/api/v1/clipboard/current",
            "/api/v1/notifications/event", "/api/v1/diagnostics", "/api/v1/battery", "/api/v1/device/beep",
            "/api/v1/iroh/address", "/api/v1/presence/digest", "/api/v1/identity/rename"
        ).forEach { assertEquals(it, RouteAccess.Peer, RouteAuthPolicy.accessFor(it)) }
    }

    @Test
    fun presenceHandshakeAndWebIntakeStayOpen() {
        listOf(
            "/", "/share", "/api/v1/health", "/api/v1/health?from=x&mv=1", "/api/v1/identity",
            "/api/v1/tls/pin", "/api/v1/pairing/respond", "/api/v1/auth/verify-pin",
            "/api/v1/web/post-bulletin", "/api/v1/web/send-clipboard", "/api/v1/web/devices"
        ).forEach { assertEquals(it, RouteAccess.Open, RouteAuthPolicy.accessFor(it)) }
    }

    @Test
    fun theHeartbeatRouteIsGone() {
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("/api/v1/heartbeat"))
    }

    @Test
    fun aPathThatOnlyStartsLikeAnOpenOneIsNotOpen() {
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("/api/v1/pairingx"))
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("/api/v1/webhook"))
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("/api/v1/identity/rename"))
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("/api/v1/health/extra"))
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("/unknown"))
    }

    @Test
    fun aTrailingSlashDoesNotChangeTheDecision() {
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor("/api/v1/files/list/"))
        assertEquals(RouteAccess.Open, RouteAuthPolicy.accessFor("/api/v1/health/"))
    }
}
