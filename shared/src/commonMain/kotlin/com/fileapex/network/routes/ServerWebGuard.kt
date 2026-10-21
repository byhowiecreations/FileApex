package com.fileapex.network.routes

import com.fileapex.network.isSplicedLoopbackOrigin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * The browser-facing web routes under `/api/v1/web` are open to anything that can reach the port, so a web page the user
 * visits could drive them. A request is taken only from a browser extension, or from a page the app served itself
 * under an address (not a name), which also stops DNS rebinding. Posting to the Bulletin Board is for the local
 * extension only, and the browser share page is closed to the LAN except while this device is showing its pairing code.
 */
internal object WebIntakeGuard {
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val EXTENSION_SCHEMES = listOf("moz-extension://", "chrome-extension://", "safari-web-extension://")

    fun allows(
        hostHeader: String?,
        origin: String?,
        remoteAddress: String,
        loopbackOnly: Boolean,
        lanOpen: Boolean = false
    ): Boolean {
        val local = isSplicedLoopbackOrigin(remoteAddress)
        if (!local && (loopbackOnly || !lanOpen)) return false
        val host = hostName(hostHeader.orEmpty())
        if (!isAddress(host)) return false
        val from = origin?.trim() ?: return true
        if (EXTENSION_SCHEMES.any { from.startsWith(it) }) return true
        if (!from.startsWith("http://")) return false
        return hostName(from.removePrefix("http://").substringBefore('/')).equals(host, ignoreCase = true)
    }

    private fun hostName(hostHeader: String): String {
        val value = hostHeader.trim()
        return if (value.startsWith("[")) value.substringAfter("[").substringBefore("]") else value.substringBefore(':')
    }

    private fun isAddress(name: String): Boolean =
        name.equals("localhost", ignoreCase = true) || IPV4.matches(name) || (name.contains(':') && name.all { it.isLetterOrDigit() || it == ':' || it == '.' })

    suspend fun check(call: ApplicationCall, loopbackOnly: Boolean = false): Boolean {
        if (allows(
                hostHeader = call.request.headers[HttpHeaders.Host],
                origin = call.request.headers[HttpHeaders.Origin],
                remoteAddress = call.request.local.remoteAddress,
                loopbackOnly = loopbackOnly,
                lanOpen = com.fileapex.domain.pairing.LanPairingDiscovery.isHostingPairing()
            )
        ) return true
        call.respond(HttpStatusCode.Forbidden, "web_origin_refused")
        return false
    }
}
