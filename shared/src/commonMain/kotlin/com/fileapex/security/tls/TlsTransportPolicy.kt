package com.fileapex.security.tls

/**
 * A peer with a stored pin always reaches us over TLS. Plain HTTP that claims to be such a peer is refused,
 * except for the few routes needed to pair, probe presence and re-exchange pins. Presence must stay reachable
 * so a peer that lost our pin can still be seen online and re-announce.
 */
object TlsTransportPolicy {
    private val PLAIN_ALLOWED_PREFIXES = listOf(
        "/api/v1/pairing",
        "/api/v1/auth",
        "/api/v1/tls/pin",
        "/api/v1/identity",
        "/api/v1/heartbeat",
        "/api/v1/health"
    )

    fun requiresTls(path: String): Boolean {
        val clean = path.substringBefore('?')
        if (clean == "/api/v1/identity/rename") return true
        return PLAIN_ALLOWED_PREFIXES.none { clean == it || clean.startsWith("$it/") }
    }
}
