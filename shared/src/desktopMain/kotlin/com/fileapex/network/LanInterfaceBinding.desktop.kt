package com.fileapex.network

import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.platform.DesktopMacTrayBridge
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import com.fileapex.iroh.irohLoopbackOrNull
import com.fileapex.tailscale.TailscaleLoopbackDial
import com.fileapex.tailscale.tailscaleLoopbackOrNull
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private const val BOUND_CONNECT_FAILOVER_MS = 750L

/**
 * Same-/24 as the peer first, then other LAN IPs, then OS-default routing. On macOS try unbound
 * first so Finder/Dock launches are not pinned to a bound Java socket that Local Network TCC
 * silently drops. Interface-bound connects fail over quickly so Mac can reach the unbound OS route
 * before short identity/health timeouts expire.
 */
private object DesktopPeerHttpPlatform : PeerHttpPlatform {
    override fun bindAttemptIps(peerHost: String): List<String> {
        val bound = LanInterfaceBinding.bindCandidatesForPeer(peerHost)
        return if (DesktopPlatformPaths.isMacOs()) listOf("") + bound else bound + ""
    }

    override fun loopbackDial(host: String, port: Int): TailscaleLoopbackDial? =
        tailscaleLoopbackOrNull(host, port) ?: irohLoopbackOrNull(host, port)

    override fun bindConnectTimeoutMs(localIp: String, requestedMs: Long): Int {
        val capped = requestedMs.coerceIn(250L, 60_000L)
        return if (localIp.isBlank()) capped.toInt() else capped.coerceAtMost(BOUND_CONNECT_FAILOVER_MS).toInt()
    }

    override fun nativeStack(): NativeLanHttp? =
        if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.isLoaded) MacNativeLanHttp else null
}

private object MacNativeLanHttp : NativeLanHttp {
    override fun get(host: String, port: Int, path: String, timeoutMs: Long): PeerBoundHttpResponse? =
        macNativeHttp("GET", host, port, path, body = null, contentType = null, timeoutMs = timeoutMs)

    override fun post(
        host: String,
        port: Int,
        path: String,
        body: String,
        contentType: String,
        timeoutMs: Long
    ): PeerBoundHttpResponse? = macNativeHttp(
        method = "POST",
        host = host,
        port = port,
        path = path,
        body = body.toByteArray(Charsets.UTF_8),
        contentType = contentType,
        timeoutMs = timeoutMs
    )

    override suspend fun uploadFromChannel(
        host: String,
        port: Int,
        pathWithQuery: String,
        contentType: String,
        chunks: ReceiveChannel<ByteArray>,
        uploadIdleTimeoutMs: Long
    ): PeerBoundHttpResponse? {
        val tmp = File.createTempFile("fileapex-up-", ".bin")
        try {
            tmp.outputStream().use { out ->
                for (chunk in chunks) {
                    out.write(chunk)
                }
            }
            return finishUnlessCancelled(
                DesktopMacTrayBridge.lanHttpUploadFile(
                    url = macLanUrl(host, port, pathWithQuery),
                    contentType = contentType,
                    filePath = tmp.absolutePath,
                    offsetBytes = 0L,
                    timeoutMs = uploadIdleTimeoutMs
                )
            )
        } finally {
            tmp.delete()
        }
    }

    override suspend fun uploadFromFile(
        host: String,
        port: Int,
        pathWithQuery: String,
        contentType: String,
        sourcePath: String,
        offset: Long,
        length: Long,
        uploadIdleTimeoutMs: Long,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)?
    ): PeerBoundHttpResponse? = finishUnlessCancelled(
        DesktopMacTrayBridge.lanHttpUploadFile(
            url = macLanUrl(host, port, pathWithQuery),
            contentType = contentType,
            filePath = sourcePath,
            offsetBytes = offset,
            lengthBytes = length,
            timeoutMs = uploadIdleTimeoutMs,
            onProgress = onProgress
        )
    )

    override suspend fun download(
        host: String,
        port: Int,
        pathWithQuery: String,
        readIdleTimeoutMs: Long,
        onChunk: suspend (ByteArray, Int) -> Unit,
        onStatus: ((Int) -> Unit)?
    ): PeerBoundStreamResult? = macNativeDownload(host, port, pathWithQuery, readIdleTimeoutMs, onChunk, onStatus)
}

private fun macLanUrl(host: String, port: Int, path: String): String {
    val normalized = if (path.startsWith("/")) path else "/$path"
    return "http://$host:$port$normalized"
}

private fun macNativeHttp(
    method: String,
    host: String,
    port: Int,
    path: String,
    body: ByteArray?,
    contentType: String?,
    timeoutMs: Long
): PeerBoundHttpResponse? {
    if (!DesktopPlatformPaths.isMacOs() || !DesktopMacTrayBridge.isLoaded) return null
    return DesktopMacTrayBridge.lanHttp(
        method = method,
        url = macLanUrl(host, port, path),
        contentType = contentType,
        body = body,
        timeoutMs = timeoutMs
    )
}

private suspend fun macNativeDownload(
    host: String,
    port: Int,
    pathWithQuery: String,
    timeoutMs: Long,
    onChunk: suspend (ByteArray, Int) -> Unit,
    onStatus: ((Int) -> Unit)?
): PeerBoundStreamResult? {
    if (!DesktopPlatformPaths.isMacOs() || !DesktopMacTrayBridge.isLoaded) return null
    val tmp = File.createTempFile("fileapex-dl-", ".bin")
    try {
        val status = DesktopMacTrayBridge.lanHttpDownloadFile(
            url = macLanUrl(host, port, pathWithQuery),
            destinationPath = tmp.absolutePath,
            timeoutMs = timeoutMs
        ) ?: return null
        onStatus?.invoke(status)
        if (status in 200..299) {
            java.io.RandomAccessFile(tmp, "r").use { raf ->
                val buffer = ByteArray(SocketFileStreamer.BUFFER_BYTES)
                while (true) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    onChunk(buffer, read)
                }
            }
        }
        return PeerBoundStreamResult(statusCode = status)
    } finally {
        tmp.delete()
    }
}

private suspend fun finishUnlessCancelled(response: PeerBoundHttpResponse?): PeerBoundHttpResponse? {
    if (TransferActivityGuard.transferCancelRequested() || !currentCoroutineContext().isActive) {
        throw CancellationException("transfer cancelled")
    }
    return response
}

actual suspend fun peerHttpGet(
    host: String,
    port: Int,
    path: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = DesktopPeerHttpPlatform.get(host, port, path, timeoutMs)

actual suspend fun peerHttpPost(
    host: String,
    port: Int,
    path: String,
    body: String,
    contentType: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = DesktopPeerHttpPlatform.post(host, port, path, body, contentType, timeoutMs)

actual suspend fun peerHttpUploadFromChannel(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    chunks: ReceiveChannel<ByteArray>,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    contentLength: Long?
): PeerBoundHttpResponse? = DesktopPeerHttpPlatform.uploadFromChannel(
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
): PeerBoundHttpResponse? = DesktopPeerHttpPlatform.uploadFromFile(
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
): PeerBoundStreamResult? = DesktopPeerHttpPlatform.getStreaming(
    host, port, pathWithQuery, connectTimeoutMs, readIdleTimeoutMs, onChunk, onStatus
)

actual suspend fun securePairingQrExchange(request: SecurePairingExchange): SecurePairingOutcome =
    SecurePairingClient.runQr(request)

actual suspend fun securePairingSasExchange(
    request: SecurePairingExchange,
    onCode: suspend (String) -> Boolean
): SecurePairingOutcome = SecurePairingClient.runSas(request, onCode)
