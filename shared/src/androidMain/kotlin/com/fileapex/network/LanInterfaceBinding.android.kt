package com.fileapex.network

import com.fileapex.iroh.irohLoopbackOrNull
import com.fileapex.tailscale.TailscaleLoopbackDial
import com.fileapex.tailscale.tailscaleLoopbackOrNull
import kotlinx.coroutines.channels.ReceiveChannel

private object AndroidPeerHttpPlatform : PeerHttpPlatform {
    override fun bindAttemptIps(peerHost: String): List<String> =
        LanInterfaceBinding.bindCandidatesForPeer(peerHost)

    override fun loopbackDial(host: String, port: Int): TailscaleLoopbackDial? =
        tailscaleLoopbackOrNull(host, port) ?: irohLoopbackOrNull(host, port)

    override fun bindConnectTimeoutMs(localIp: String, requestedMs: Long): Int =
        requestedMs.coerceIn(250L, 60_000L).toInt()

    override fun nativeStack(): NativeLanHttp? = null
}

actual suspend fun peerHttpGet(
    host: String,
    port: Int,
    path: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = AndroidPeerHttpPlatform.get(host, port, path, timeoutMs)

actual suspend fun peerHttpPost(
    host: String,
    port: Int,
    path: String,
    body: String,
    contentType: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = AndroidPeerHttpPlatform.post(host, port, path, body, contentType, timeoutMs)

actual suspend fun peerHttpUploadFromChannel(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    chunks: ReceiveChannel<ByteArray>,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    contentLength: Long?
): PeerBoundHttpResponse? = AndroidPeerHttpPlatform.uploadFromChannel(
    host, port, pathWithQuery, contentType, chunks, connectTimeoutMs, uploadIdleTimeoutMs, contentLength
)

actual suspend fun peerHttpUploadFromFile(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    sourcePath: String,
    offset: Long,
    length: Long,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)?
): PeerBoundHttpResponse? = AndroidPeerHttpPlatform.uploadFromFile(
    host, port, pathWithQuery, contentType, sourcePath, offset, length,
    connectTimeoutMs, uploadIdleTimeoutMs, onProgress
)

actual suspend fun peerHttpGetStreaming(
    host: String,
    port: Int,
    pathWithQuery: String,
    connectTimeoutMs: Long,
    readIdleTimeoutMs: Long,
    onChunk: suspend (ByteArray, Int) -> Unit,
    onStatus: ((Int) -> Unit)?
): PeerBoundStreamResult? = AndroidPeerHttpPlatform.getStreaming(
    host, port, pathWithQuery, connectTimeoutMs, readIdleTimeoutMs, onChunk, onStatus
)
