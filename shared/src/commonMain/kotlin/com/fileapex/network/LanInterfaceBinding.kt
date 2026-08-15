package com.fileapex.network

import com.fileapex.util.NetworkUtils
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Bind LAN UDP/TCP to the primary routable interface instead of wildcard/loopback.
 */
object LanInterfaceBinding {
    fun primaryLanIpv4OrNull(): String? =
        NetworkUtils.preferredLanIpv4().takeIf { NetworkUtils.isUsableLanIpv4(it) }

    fun lanBindCandidates(): List<String> = NetworkUtils.lanBindCandidates()

    fun bindCandidatesForPeer(peerHost: String): List<String> =
        NetworkUtils.orderBindCandidatesForPeer(peerHost)

    fun shareServerListenHost(): String = "0.0.0.0"

    fun shareServerBindHost(): String = primaryLanIpv4OrNull() ?: shareServerListenHost()
}

data class PeerBoundHttpResponse(
    val statusCode: Int,
    val body: String
)

data class PeerBoundStreamResult(
    val statusCode: Int
)

expect suspend fun peerHttpGet(
    host: String,
    port: Int,
    path: String,
    timeoutMs: Long
): PeerBoundHttpResponse?

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
    uploadIdleTimeoutMs: Long,
    contentLength: Long? = null
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

expect fun sendWakeBroadcastOnPrimaryInterface()
