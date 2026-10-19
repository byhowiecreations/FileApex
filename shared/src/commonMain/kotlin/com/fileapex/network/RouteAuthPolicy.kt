package com.fileapex.network

/**
 * Who may call a route. Everything is [RouteAccess.Peer] (a paired device that proved its identity with its pinned
 * TLS certificate) unless it is listed here. Adding a route needs no entry to stay closed; opening one is a
 * deliberate edit to [OPEN_EXACT] or [OPEN_PREFIXES], which RouteAuthPolicyTest pins down.
 */
enum class RouteAccess { Open, Peer }

object RouteAuthPolicy {
    /** Presence probe and handshake reads, plus the browser share page. */
    val OPEN_EXACT: Set<String> = setOf(
        "/",
        "/share",
        "/api/v1/health",
        "/api/v1/identity",
        "/api/v1/tls/pin"
    )

    /** Pairing and PIN verification happen before two devices trust each other; the web intake serves a browser. */
    val OPEN_PREFIXES: List<String> = listOf(
        "/api/v1/pairing",
        "/api/v1/auth",
        "/api/v1/web"
    )

    fun accessFor(rawPath: String): RouteAccess {
        val path = rawPath.substringBefore('?').let { if (it.length > 1) it.trimEnd('/') else it }
        if (path in OPEN_EXACT) return RouteAccess.Open
        if (OPEN_PREFIXES.any { path == it || path.startsWith("$it/") }) return RouteAccess.Open
        return RouteAccess.Peer
    }
}
