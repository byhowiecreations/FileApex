package com.fileapex.security.tls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsTransportPolicyTest {
    @Test
    fun sensitiveRoutesNeedTls() {
        listOf(
            "/api/v1/clipboard/send",
            "/api/v1/clipboard/current",
            "/api/v1/clipboard/opt-in-request",
            "/api/v1/notifications/event",
            "/api/v1/files/list?path=/",
            "/api/v1/bulletin/sync",
            "/api/v1/identity/rename",
            "/api/v1/cluster/merge"
        ).forEach { assertTrue(it, TlsTransportPolicy.requiresTls(it)) }
    }

    @Test
    fun pairingPresenceAndPinExchangeStayOpenOverHttp() {
        listOf(
            "/api/v1/pairing/respond?code=1",
            "/api/v1/auth/verify-pin",
            "/api/v1/tls/pin",
            "/api/v1/identity",
            "/api/v1/identity?from=x",
            "/api/v1/heartbeat?from=x",
            "/api/v1/health"
        ).forEach { assertFalse(it, TlsTransportPolicy.requiresTls(it)) }
    }
}
