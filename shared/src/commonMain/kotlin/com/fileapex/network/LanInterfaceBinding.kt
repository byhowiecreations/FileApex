package com.fileapex.network

import com.fileapex.util.NetworkUtils
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * SSOT for binding LAN UDP/TCP to the primary routable interface instead of wildcard/loopback.
 */
object LanInterfaceBinding {
    fun primaryLanIpv4OrNull(): String? =
        NetworkUtils.preferredLanIpv4().takeIf { NetworkUtils.isUsableLanIpv4(it) }

    /** Ordered local IPs for outbound peer sockets — active LAN first. */
    fun lanBindCandidates(): List<String> = NetworkUtils.lanBindCandidates()

    /** Inbound HTTP share-server listen socket — all interfaces (LAN + Windows Firewall). */
    fun shareServerListenHost(): String = "0.0.0.0"

    /** HTTP share-server bind address — primary LAN IP when available. */
    fun shareServerBindHost(): String = primaryLanIpv4OrNull() ?: shareServerListenHost()
}

data class PeerBoundHttpResponse(
    val statusCode: Int,
    val body: String
)

data class PeerBoundStreamResult(
    val statusCode: Int
)

/** GET over TCP bound to the primary LAN interface (force-route for cross-platform peers). */
expect suspend fun peerHttpGet(
    host: String,
    port: Int,
    path: String,
    timeoutMs: Long
): PeerBoundHttpResponse?

/** POST over TCP bound to the primary LAN interface (force-route cluster merge). */
expect suspend fun peerHttpPost(
    host: String,
    port: Int,
    path: String,
    body: String,
    contentType: String,
    timeoutMs: Long
): PeerBoundHttpResponse?

/**
 * Streams an upload body over TCP bound to a LAN interface.
 * Returns null only when no bind candidate could connect (channel untouched).
 */
expect suspend fun peerHttpUploadFromChannel(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    chunks: ReceiveChannel<ByteArray>,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long
): PeerBoundHttpResponse?

/**
 * Streams a GET response body over TCP bound to a LAN interface.
 * Returns null only when no bind candidate could connect.
 */
expect suspend fun peerHttpGetStreaming(
    host: String,
    port: Int,
    pathWithQuery: String,
    connectTimeoutMs: Long,
    readIdleTimeoutMs: Long,
    onChunk: suspend (ByteArray) -> Unit
): PeerBoundStreamResult?

/** Sends wake UDP from the primary LAN interface (broadcast + directed subnet + multicast). */
expect fun sendWakeBroadcastOnPrimaryInterface()
