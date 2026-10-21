package com.fileapex.network

import com.fileapex.tailscale.TailscaleLoopbackDial
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/** What differs between Android and desktop in the interface-bound peer HTTP client. */
internal interface PeerHttpPlatform {
    /** Local addresses to bind to, in order; a blank entry means the OS default route. */
    fun bindAttemptIps(peerHost: String): List<String>

    fun bindConnectTimeoutMs(localIp: String, requestedMs: Long): Int

    /** The local pipe that carries a tailnet or Iroh peer, or null for a plain LAN peer. */
    fun loopbackDial(host: String, port: Int): TailscaleLoopbackDial?

    /** A native stack that replaces the Java sockets for LAN calls, or null to use them. */
    fun nativeStack(): NativeLanHttp?
}

internal interface NativeLanHttp {
    fun get(host: String, port: Int, path: String, timeoutMs: Long): PeerBoundHttpResponse?

    fun post(host: String, port: Int, path: String, body: String, contentType: String, timeoutMs: Long): PeerBoundHttpResponse?

    suspend fun uploadFromChannel(
        host: String,
        port: Int,
        pathWithQuery: String,
        contentType: String,
        chunks: ReceiveChannel<ByteArray>,
        uploadIdleTimeoutMs: Long
    ): PeerBoundHttpResponse?

    suspend fun uploadFromFile(
        host: String,
        port: Int,
        pathWithQuery: String,
        contentType: String,
        sourcePath: String,
        offset: Long,
        length: Long,
        uploadIdleTimeoutMs: Long,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)?
    ): PeerBoundHttpResponse?

    suspend fun download(
        host: String,
        port: Int,
        pathWithQuery: String,
        readIdleTimeoutMs: Long,
        onChunk: suspend (ByteArray, Int) -> Unit,
        onStatus: ((Int) -> Unit)?
    ): PeerBoundStreamResult?
}

internal suspend fun PeerHttpPlatform.get(
    host: String,
    port: Int,
    path: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = withContext(Dispatchers.IO) {
    val loop = loopbackDial(host, port)
    if (loop != null) {
        return@withContext runCatching {
            executeBoundHttpOnLocalIp(
                localIp = "127.0.0.1",
                host = loop.host,
                port = loop.port,
                dialToken = loop.token,
                method = "GET",
                path = path,
                body = null,
                contentType = null,
                timeoutMs = timeoutMs
            )
        }.getOrNull()
    }
    nativeStack()?.let { return@withContext it.get(host, port, path, timeoutMs) }
    executeBoundHttp(host, port, "GET", path, null, null, timeoutMs)
}

internal suspend fun PeerHttpPlatform.post(
    host: String,
    port: Int,
    path: String,
    body: String,
    contentType: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = withContext(Dispatchers.IO) {
    val loop = loopbackDial(host, port)
    if (loop != null) {
        return@withContext runCatching {
            executeBoundHttpOnLocalIp(
                localIp = "127.0.0.1",
                host = loop.host,
                port = loop.port,
                dialToken = loop.token,
                method = "POST",
                path = path,
                body = body,
                contentType = contentType,
                timeoutMs = timeoutMs
            )
        }.getOrNull()
    }
    nativeStack()?.let { return@withContext it.post(host, port, path, body, contentType, timeoutMs) }
    executeBoundHttp(host, port, "POST", path, body, contentType, timeoutMs)
}

internal suspend fun PeerHttpPlatform.uploadFromChannel(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    chunks: ReceiveChannel<ByteArray>,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    contentLength: Long?
): PeerBoundHttpResponse? = withContext(TransferRuntime.outbound) {
    val writeBody: suspend (java.io.OutputStream) -> Unit = { output ->
        for (chunk in chunks) {
            output.write(chunk)
        }
    }
    val loop = loopbackDial(host, port)
    if (loop != null) {
        return@withContext cancellableCatching {
            executeBoundUploadOnLocalIp(
                localIp = "127.0.0.1",
                host = loop.host,
                port = loop.port,
                dialToken = loop.token,
                pathWithQuery = pathWithQuery,
                contentType = contentType,
                connectTimeoutMs = connectTimeoutMs,
                uploadIdleTimeoutMs = uploadIdleTimeoutMs,
                contentLength = contentLength,
                writeBody = writeBody
            )
        }.getOrElse { error ->
            if (error is BoundConnectFailed) null else throw error
        }
    }
    nativeStack()?.let {
        return@withContext it.uploadFromChannel(host, port, pathWithQuery, contentType, chunks, uploadIdleTimeoutMs)
    }
    executeBoundUpload(host, port, pathWithQuery, contentType, connectTimeoutMs, uploadIdleTimeoutMs, contentLength, writeBody)
}

internal suspend fun PeerHttpPlatform.uploadFromFile(
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
): PeerBoundHttpResponse? = withContext(TransferRuntime.outbound) {
    val loop = loopbackDial(host, port)
    val native = if (loop == null) nativeStack() else null
    if (native != null) {
        return@withContext native.uploadFromFile(
            host, port, pathWithQuery, contentType, sourcePath, offset, length, uploadIdleTimeoutMs, onProgress
        )
    }
    val total = if (length >= 0L) offset + length else SocketFileStreamer.fileLength(sourcePath)
    var currentSent = offset
    val writeBody: suspend (java.io.OutputStream) -> Unit = { output ->
        SocketFileStreamer.streamFromOffset(
            sourcePath = sourcePath,
            offset = offset,
            byteLimit = length
        ) { buffer, read ->
            output.write(buffer, 0, read)
            currentSent += read
            onProgress?.invoke(currentSent, total)
        }
    }
    if (loop != null) {
        return@withContext cancellableCatching {
            executeBoundUploadOnLocalIp(
                localIp = "127.0.0.1",
                host = loop.host,
                port = loop.port,
                dialToken = loop.token,
                pathWithQuery = pathWithQuery,
                contentType = contentType,
                connectTimeoutMs = connectTimeoutMs,
                uploadIdleTimeoutMs = uploadIdleTimeoutMs,
                contentLength = length.takeIf { it >= 0L },
                writeBody = writeBody
            )
        }.getOrElse { error ->
            if (error is BoundConnectFailed) null else throw error
        }
    }
    executeBoundUpload(
        host, port, pathWithQuery, contentType, connectTimeoutMs, uploadIdleTimeoutMs,
        length.takeIf { it >= 0L }, writeBody
    )
}

internal suspend fun PeerHttpPlatform.getStreaming(
    host: String,
    port: Int,
    pathWithQuery: String,
    connectTimeoutMs: Long,
    readIdleTimeoutMs: Long,
    onChunk: suspend (ByteArray, Int) -> Unit,
    onStatus: ((Int) -> Unit)?
): PeerBoundStreamResult? = withContext(TransferRuntime.outbound) {
    val loop = loopbackDial(host, port)
    if (loop != null) {
        return@withContext cancellableCatching {
            executeBoundGetStreamingOnLocalIp(
                localIp = "127.0.0.1",
                host = loop.host,
                port = loop.port,
                dialToken = loop.token,
                pathWithQuery = pathWithQuery,
                connectTimeoutMs = connectTimeoutMs,
                readIdleTimeoutMs = readIdleTimeoutMs,
                onChunk = onChunk,
                onStatus = onStatus
            )
        }.getOrElse { error ->
            if (error is BoundConnectFailed) null else throw error
        }
    }
    nativeStack()?.let {
        return@withContext it.download(host, port, pathWithQuery, readIdleTimeoutMs, onChunk, onStatus)
    }
    executeBoundGetStreaming(host, port, pathWithQuery, connectTimeoutMs, readIdleTimeoutMs, onChunk, onStatus)
}

private fun PeerHttpPlatform.executeBoundHttp(
    host: String,
    port: Int,
    method: String,
    path: String,
    body: String?,
    contentType: String?,
    timeoutMs: Long
): PeerBoundHttpResponse? {
    for (localIp in bindAttemptIps(host)) {
        val response = runCatching {
            executeBoundHttpOnLocalIp(
                localIp = localIp,
                host = host,
                port = port,
                method = method,
                path = path,
                body = body,
                contentType = contentType,
                timeoutMs = timeoutMs
            )
        }.getOrNull()
        if (response != null && response.statusCode > 0) {
            return response
        }
        if (!NetworkUtils.shouldTryNextBindCandidate(localIp, host)) break
    }
    return null
}

private suspend fun PeerHttpPlatform.executeBoundGetStreaming(
    host: String,
    port: Int,
    pathWithQuery: String,
    connectTimeoutMs: Long,
    readIdleTimeoutMs: Long,
    onChunk: suspend (ByteArray, Int) -> Unit,
    onStatus: ((Int) -> Unit)?
): PeerBoundStreamResult? {
    for (localIp in bindAttemptIps(host)) {
        val response = cancellableCatching {
            executeBoundGetStreamingOnLocalIp(
                localIp = localIp,
                host = host,
                port = port,
                pathWithQuery = pathWithQuery,
                connectTimeoutMs = connectTimeoutMs,
                readIdleTimeoutMs = readIdleTimeoutMs,
                onChunk = onChunk,
                onStatus = onStatus
            )
        }.getOrElse { error ->
            if (error is BoundConnectFailed) null else throw error
        }
        if (response != null) {
            return response
        }
    }
    return null
}

private suspend fun PeerHttpPlatform.executeBoundUpload(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    contentLength: Long?,
    writeBody: suspend (java.io.OutputStream) -> Unit
): PeerBoundHttpResponse? {
    for (localIp in bindAttemptIps(host)) {
        val response = cancellableCatching {
            executeBoundUploadOnLocalIp(
                localIp = localIp,
                host = host,
                port = port,
                pathWithQuery = pathWithQuery,
                contentType = contentType,
                connectTimeoutMs = connectTimeoutMs,
                uploadIdleTimeoutMs = uploadIdleTimeoutMs,
                contentLength = contentLength,
                writeBody = writeBody
            )
        }.getOrElse { error ->
            if (error is BoundConnectFailed) null else throw error
        }
        if (response != null) {
            return response
        }
    }
    return null
}
