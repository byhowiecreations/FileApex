package com.fileapex.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A removed device that reaches a peer may read the signed chain, which carries the proof it was removed, and nothing else. */
class RevokedAccessTest {
    @Test
    fun onlyAReadOfTheChainIsAllowed() {
        assertTrue(RevokedAccess.allows("GET", "/api/v1/membership/chain"))
    }

    @Test
    fun everythingElseIsAnsweredAsRevoked() {
        listOf(
            "POST" to "/api/v1/membership/chain",
            "POST" to "/api/v1/membership/chain/join",
            "GET" to "/api/v1/membership/chain/join",
            "GET" to "/api/v1/files",
            "GET" to "/api/v1/membership/chainx",
            "GET" to "/api/v1/clipboard",
            "GET" to "/"
        ).forEach { (method, path) -> assertFalse("$method $path", RevokedAccess.allows(method, path)) }
    }

    @Test
    fun aQueryStringDoesNotChangeThePath() {
        assertTrue(RevokedAccess.allows("GET", "/api/v1/membership/chain?from=abc"))
        assertFalse(RevokedAccess.allows("GET", "/api/v1/files?x=/api/v1/membership/chain"))
    }

    @Test
    fun theDeviceRevocationGateLetsAChainReadThroughAndStopsTheRest() {
        assertFalse(RevokedAccess.gatedAsRevoked("GET", "/api/v1/membership/chain"))
        assertFalse(RevokedAccess.gatedAsRevoked("GET", "/api/v1/membership/chain?from=abc&mv=1"))
        assertTrue(RevokedAccess.gatedAsRevoked("POST", "/api/v1/membership/chain"))
        assertTrue(RevokedAccess.gatedAsRevoked("GET", "/api/v1/health"))
    }
}
