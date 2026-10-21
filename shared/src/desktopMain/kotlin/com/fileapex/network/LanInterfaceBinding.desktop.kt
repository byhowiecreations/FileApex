package com.fileapex.network

import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.platform.DesktopMacTrayBridge
import com.fileapex.platform.DesktopPlatformPaths
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.iroh.irohLoopbackOrNull
import com.fileapex.tailscale.tailscaleLoopbackOrNull
import com.fileapex.tailscale.writeTailscaleDialToken
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

internal fun directedBroadcastOrNull(localIp: String, networkInterface: NetworkInterface): String? {
    val localAddress = InetAddress.getByName(localIp) as? Inet4Address ?: return null
    val ifaceAddress = networkInterface.interfaceAddresses.firstOrNull { address ->
        address.address is Inet4Address && address.address.hostAddress == localIp
    } ?: return null
    val prefixLength = ifaceAddress.networkPrefixLength.toInt()
    if (prefixLength !in 1..32) return null
    val ip = localAddress.address
    val mask = ByteArray(4)
    for (i in 0 until 4) {
        val bits = (prefixLength - i * 8).coerceIn(0, 8)
        mask[i] = ((0xFF shl (8 - bits)) and 0xFF).toByte()
    }
    val broadcast = ByteArray(4) { index ->
        (ip[index].toInt() and mask[index].toInt() or (mask[index].toInt().inv() and 0xFF)).toByte()
    }
    return InetAddress.getByAddress(broadcast).hostAddress
}

actual fun sendWakeBroadcastOnPrimaryInterface() {
    val candidates = LanInterfaceBinding.lanBindCandidates()
    if (candidates.isEmpty()) {
        return
    }
    val payloads = WakeHelloOutbox.payloads()
    for (localIp in candidates) {
        val networkInterface = networkInterfaceForIp(localIp) ?: continue
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(localIp, 0))
            val targets = linkedSetOf(
                WakeProtocol.BROADCAST_ADDRESS,
                WakeProtocol.MULTICAST_ADDRESS
            )
            directedBroadcastOrNull(localIp, networkInterface)?.let { targets.add(it) }
            for (target in targets) {
                cancellableCatching {
                    val address = InetAddress.getByName(target)
                    for (payload in payloads) {
                        socket.send(DatagramPacket(payload, payload.size, address, WakeProtocol.PORT))
                    }
                }
            }
        }
    }
}

private fun networkInterfaceForIp(localIp: String): NetworkInterface? =
    NetworkInterface.getNetworkInterfaces().toList().firstOrNull { iface ->
        iface.isUp &&
            !iface.isLoopback &&
            iface.inetAddresses.toList().any { address ->
                address is Inet4Address && address.hostAddress == localIp
            }
    }

actual suspend fun peerHttpGet(
    host: String,
    port: Int,
    path: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = withContext(Dispatchers.IO) {
    val loop = tailscaleLoopbackOrNull(host, port) ?: irohLoopbackOrNull(host, port)
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
    if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.isLoaded) {
        return@withContext macNativeHttp(
            "GET",
            host,
            port,
            path,
            body = null,
            contentType = null,
            timeoutMs = timeoutMs
        )
    }
    executeBoundHttp(
        host = host,
        port = port,
        method = "GET",
        path = path,
        body = null,
        contentType = null,
        timeoutMs = timeoutMs
    )
}

actual suspend fun peerHttpPost(
    host: String,
    port: Int,
    path: String,
    body: String,
    contentType: String,
    timeoutMs: Long
): PeerBoundHttpResponse? = withContext(Dispatchers.IO) {
    val loop = tailscaleLoopbackOrNull(host, port) ?: irohLoopbackOrNull(host, port)
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
    if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.isLoaded) {
        return@withContext macNativeHttp(
            method = "POST",
            host = host,
            port = port,
            path = path,
            body = body.toByteArray(Charsets.UTF_8),
            contentType = contentType,
            timeoutMs = timeoutMs
        )
    }
    executeBoundHttp(
        host = host,
        port = port,
        method = "POST",
        path = path,
        body = body,
        contentType = contentType,
        timeoutMs = timeoutMs
    )
}

actual suspend fun peerHttpUploadFromChannel(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    chunks: ReceiveChannel<ByteArray>,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    contentLength: Long?
): PeerBoundHttpResponse? = withContext(TransferRuntime.outbound) {
    val loop = tailscaleLoopbackOrNull(host, port) ?: irohLoopbackOrNull(host, port)
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
                writeBody = { output ->
                    for (chunk in chunks) {
                        output.write(chunk)
                    }
                }
            )
        }.getOrElse { error ->
            if (error is BoundConnectFailed) null else throw error
        }
    }
    if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.isLoaded) {
        val tmp = File.createTempFile("fileapex-up-", ".bin")
        try {
            tmp.outputStream().use { out ->
                for (chunk in chunks) {
                    out.write(chunk)
                }
            }
            return@withContext finishUnlessCancelled(
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
    executeBoundUpload(
        host = host,
        port = port,
        pathWithQuery = pathWithQuery,
        contentType = contentType,
        connectTimeoutMs = connectTimeoutMs,
        uploadIdleTimeoutMs = uploadIdleTimeoutMs,
        contentLength = contentLength,
        writeBody = { output ->
            for (chunk in chunks) {
                output.write(chunk)
            }
        }
    )
}

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
): PeerBoundHttpResponse? = withContext(TransferRuntime.outbound) {
    val loop = tailscaleLoopbackOrNull(host, port) ?: irohLoopbackOrNull(host, port)
    if (loop != null) {
        val total = if (length >= 0L) offset + length else SocketFileStreamer.fileLength(sourcePath)
        var currentSent = offset
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
                writeBody = { output ->
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
            )
        }.getOrElse { error ->
            if (error is BoundConnectFailed) null else throw error
        }
    }
    if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.isLoaded) {
        return@withContext finishUnlessCancelled(
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
    }
    val total = if (length >= 0L) offset + length else SocketFileStreamer.fileLength(sourcePath)
    var currentSent = offset
    executeBoundUpload(
        host = host,
        port = port,
        pathWithQuery = pathWithQuery,
        contentType = contentType,
        connectTimeoutMs = connectTimeoutMs,
        uploadIdleTimeoutMs = uploadIdleTimeoutMs,
        contentLength = length.takeIf { it >= 0L },
        writeBody = { output ->
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
    )
}

actual suspend fun peerHttpGetStreaming(
    host: String,
    port: Int,
    pathWithQuery: String,
    connectTimeoutMs: Long,
    readIdleTimeoutMs: Long,
    onChunk: suspend (ByteArray, Int) -> Unit,
    onStatus: ((Int) -> Unit)?
): PeerBoundStreamResult? = withContext(TransferRuntime.outbound) {
    val loop = tailscaleLoopbackOrNull(host, port) ?: irohLoopbackOrNull(host, port)
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
    if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.isLoaded) {
        return@withContext macNativeDownload(
            host = host,
            port = port,
            pathWithQuery = pathWithQuery,
            timeoutMs = readIdleTimeoutMs,
            onChunk = onChunk,
            onStatus = onStatus
        )
    }
    executeBoundGetStreaming(
        host = host,
        port = port,
        pathWithQuery = pathWithQuery,
        connectTimeoutMs = connectTimeoutMs,
        readIdleTimeoutMs = readIdleTimeoutMs,
        onChunk = onChunk,
        onStatus = onStatus
    )
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

private fun executeBoundHttp(
    host: String,
    port: Int,
    method: String,
    path: String,
    body: String?,
    contentType: String?,
    timeoutMs: Long
): PeerBoundHttpResponse? {
    for (localIp in peerBindAttemptIps(host)) {
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

private suspend fun executeBoundGetStreaming(
    host: String,
    port: Int,
    pathWithQuery: String,
    connectTimeoutMs: Long,
    readIdleTimeoutMs: Long,
    onChunk: suspend (ByteArray, Int) -> Unit,
    onStatus: ((Int) -> Unit)?
): PeerBoundStreamResult? {
    for (localIp in peerBindAttemptIps(host)) {
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
            if (error is BoundConnectFailed) {
                null
            } else {
                throw error
            }
        }
        if (response != null) {
            return response
        }
    }
    return null
}

private suspend fun executeBoundGetStreamingOnLocalIp(
    localIp: String,
    host: String,
    port: Int,
    pathWithQuery: String,
    connectTimeoutMs: Long,
    readIdleTimeoutMs: Long,
    onChunk: suspend (ByteArray, Int) -> Unit,
    onStatus: ((Int) -> Unit)?,
    dialToken: String = ""
): PeerBoundStreamResult {
    val connectTimeout = connectTimeoutForBindAttempt(localIp, connectTimeoutMs)
    val idleTimeout = readIdleTimeoutMs.coerceIn(1000L, 600_000L).toInt()
    val socket = Socket()
    try {
        if (localIp.isNotBlank()) {
            socket.bind(InetSocketAddress(localIp, 0))
        }
        val io = try {
            PeerTlsConnector.connect(socket, host, port, connectTimeout)
        } catch (error: Exception) {
            socket.close()
            throw BoundConnectFailed(error)
        }
        io.soTimeout = idleTimeout
        val output = io.getOutputStream()
        writeTailscaleDialToken(output, dialToken)
        val request = buildString {
            append("GET ")
            append(PeerPinHeader.stripPin(pathWithQuery))
            append(" HTTP/1.1\r\n")
            append("Host: ")
            append(host)
            append(':')
            append(port)
            append("\r\n")
            append("Connection: close\r\n").appendPinHeader(pathWithQuery)
            append("Accept: application/octet-stream\r\n")
            val resumeOffset = pathWithQuery.substringAfter("offset=", missingDelimiterValue = "")
                .substringBefore('&')
                .toLongOrNull()
            if (resumeOffset != null && resumeOffset > 0L) {
                append("Range: bytes=")
                append(resumeOffset)
                append("-\r\n")
            }
            append("\r\n")
        }
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()
        val input = io.getInputStream().buffered()
        val statusCode = readHttpStatusLine(input)
        onStatus?.invoke(statusCode)
        val headerLines = readHttpHeaderLines(input)
        val bodyHeaders = HttpTransferBodyReader.parseHeaders(headerLines)
        if (statusCode in 200..299) {
            HttpTransferBodyReader.readBody(
                readAsciiLine = { input.readAsciiLine() },
                readAtLeast = { buffer, offset, length ->
                    var total = 0
                    while (total < length) {
                        val read = input.read(buffer, offset + total, length - total)
                        if (read <= 0) break
                        total += read
                    }
                    total
                },
                headers = bodyHeaders,
                onChunk = onChunk
            )
        } else {
            drainAvailable(input)
        }
        return PeerBoundStreamResult(statusCode = statusCode)
    } finally {
        runCatching { socket.close() }
    }
}

private class BoundConnectFailed(cause: Throwable) : Exception(cause)

/** Same-/24 as the peer first, then other LAN IPs, then OS-default routing.
 * On macOS try unbound first so Finder/Dock launches are not pinned to a bound Java socket
 * that Local Network TCC silently drops.
 */
private fun peerBindAttemptIps(peerHost: String): List<String> {
    val bound = LanInterfaceBinding.bindCandidatesForPeer(peerHost)
    return if (DesktopPlatformPaths.isMacOs()) {
        listOf("") + bound
    } else {
        bound + ""
    }
}

/**
 * Interface-bound connects fail over quickly so Mac can reach the unbound OS route
 * before short identity/health timeouts expire.
 */
private fun connectTimeoutForBindAttempt(localIp: String, requestedMs: Long): Int {
    val capped = requestedMs.coerceIn(250L, 60_000L)
    if (localIp.isBlank()) {
        return capped.toInt()
    }
    return capped.coerceAtMost(BOUND_CONNECT_FAILOVER_MS).toInt()
}

private const val BOUND_CONNECT_FAILOVER_MS = 750L

private suspend fun executeBoundUpload(
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    contentLength: Long? = null,
    writeBody: suspend (java.io.OutputStream) -> Unit
): PeerBoundHttpResponse? {
    for (localIp in peerBindAttemptIps(host)) {
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
            if (error is BoundConnectFailed) {
                null
            } else {
                throw error
            }
        }
        if (response != null) {
            return response
        }
    }
    return null
}

private suspend fun executeBoundUploadOnLocalIp(
    localIp: String,
    host: String,
    port: Int,
    pathWithQuery: String,
    contentType: String,
    connectTimeoutMs: Long,
    uploadIdleTimeoutMs: Long,
    contentLength: Long? = null,
    dialToken: String = "",
    writeBody: suspend (java.io.OutputStream) -> Unit
): PeerBoundHttpResponse {
    val connectTimeout = connectTimeoutForBindAttempt(localIp, connectTimeoutMs)
    val idleTimeout = uploadIdleTimeoutMs.coerceIn(1000L, 600_000L).toInt()
    val socket = Socket()
    return withTrackedTransferSocket(socket) {
        if (localIp.isNotBlank()) {
            socket.bind(InetSocketAddress(localIp, 0))
        }
        val io = try {
            PeerTlsConnector.connect(socket, host, port, connectTimeout)
        } catch (error: Exception) {
            socket.close()
            if (TransferActivityGuard.transferCancelRequested()) {
                throw CancellationException("transfer cancelled")
            }
            throw BoundConnectFailed(error)
        }
        io.soTimeout = idleTimeout
        val output = io.getOutputStream()
        writeTailscaleDialToken(output, dialToken)
        val header = buildString {
            append("POST ")
            append(PeerPinHeader.stripPin(pathWithQuery))
            append(" HTTP/1.1\r\n")
            append("Host: ")
            append(host)
            append(':')
            append(port)
            append("\r\n")
            append("Connection: close\r\n").appendPinHeader(pathWithQuery)
            append("Content-Type: ")
            append(contentType)
            append("\r\n")
            if (contentLength != null) {
                append("Content-Length: ")
                append(contentLength)
                append("\r\n")
            }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        writeBody(output)
        output.flush()
        if (contentLength == null) {
            PeerTlsConnector.shutdownOutput(io)
        }
        val raw = readHttpResponse(io.getInputStream())
        parseHttpResponse(raw)
    }
}

private suspend fun finishUnlessCancelled(response: PeerBoundHttpResponse?): PeerBoundHttpResponse? {
    if (TransferActivityGuard.transferCancelRequested() || !currentCoroutineContext().isActive) {
        throw CancellationException("transfer cancelled")
    }
    return response
}

private fun executeBoundHttpOnLocalIp(
    localIp: String,
    host: String,
    port: Int,
    method: String,
    path: String,
    body: String?,
    contentType: String?,
    timeoutMs: Long,
    dialToken: String = ""
): PeerBoundHttpResponse {
    val connectTimeout = connectTimeoutForBindAttempt(localIp, timeoutMs)
    val readTimeout = timeoutMs.coerceIn(250L, 60_000L).toInt()
    Socket().use { socket ->
        if (localIp.isNotBlank()) {
            socket.bind(InetSocketAddress(localIp, 0))
        }
        val io = PeerTlsConnector.connect(socket, host, port, connectTimeout)
        io.soTimeout = readTimeout
        val payload = body.orEmpty()
        val request = buildString {
            append(method)
            append(' ')
            append(PeerPinHeader.stripPin(path))
            append(" HTTP/1.1\r\n")
            append("Host: ")
            append(host)
            append(':')
            append(port)
            append("\r\n")
            append("Connection: close\r\n").appendPinHeader(path)
            append("Accept: application/json\r\n")
            if (contentType != null) {
                append("Content-Type: ")
                append(contentType)
                append("\r\n")
                append("Content-Length: ")
                append(payload.toByteArray(Charsets.UTF_8).size)
                append("\r\n")
            }
            append("\r\n")
            if (contentType != null) {
                append(payload)
            }
        }
        val output = io.getOutputStream()
        writeTailscaleDialToken(output, dialToken)
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()
        val raw = readHttpResponse(io.getInputStream())
        return parseHttpResponse(raw)
    }
}

private fun parseHttpResponse(raw: String): PeerBoundHttpResponse {
    val headerEnd = raw.indexOf("\r\n\r\n")
    val header = if (headerEnd >= 0) raw.substring(0, headerEnd) else raw
    val body = if (headerEnd >= 0) raw.substring(headerEnd + 4) else ""
    val statusLine = header.lineSequence().firstOrNull().orEmpty()
    val statusCode = Regex("HTTP/\\d\\.\\d (\\d+)").find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: 0
    return PeerBoundHttpResponse(statusCode = statusCode, body = body.trim(), headers = com.fileapex.network.PeerHttpHeaders.parse(header))
}

/**
 * Read a full HTTP/1.1 response from [stream] without relying on TCP close for EOF.
 * Honors Content-Length and Transfer-Encoding: chunked (Ktor CIO often uses either).
 */
private fun readHttpResponse(stream: java.io.InputStream): String {
    val buf = stream.buffered(8192)
    val headerLines = readHttpHeaderLines(buf)
    val bodyHeaders = HttpTransferBodyReader.parseHeaders(headerLines)
    val bodyBytes = when {
        bodyHeaders.isChunked -> readChunkedBodySync(buf)
        bodyHeaders.contentLength != null && bodyHeaders.contentLength >= 0 -> {
            if (bodyHeaders.contentLength > MAX_PEER_RESPONSE_BYTES) throw java.io.IOException("Peer response exceeds $MAX_PEER_RESPONSE_BYTES bytes")
            val bodyBytes = ByteArray(bodyHeaders.contentLength.toInt())
            var offset = 0
            while (offset < bodyBytes.size) {
                val n = buf.read(bodyBytes, offset, bodyBytes.size - offset)
                if (n < 0) break
                offset += n
            }
            if (offset == bodyBytes.size) bodyBytes else bodyBytes.copyOf(offset)
        }
        else -> buf.readBoundedBytes()
    }

    val headerBlock = headerLines.joinToString("\r\n")
    return "$headerBlock\r\n\r\n${bodyBytes.toString(Charsets.UTF_8)}"
}

private fun readChunkedBodySync(input: java.io.BufferedInputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val sizeLine = input.readAsciiLine().trim()
        if (sizeLine.isEmpty()) continue
        val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16)
            ?: error("Invalid HTTP chunk size: $sizeLine")
        if (chunkSize == 0) {
            while (input.readAsciiLine().isNotEmpty()) {
                // consume optional trailer headers
            }
            break
        }
        if (chunkSize < 0 || out.size().toLong() + chunkSize > MAX_PEER_RESPONSE_BYTES) {
            throw java.io.IOException("Peer response exceeds $MAX_PEER_RESPONSE_BYTES bytes")
        }
        val chunk = ByteArray(chunkSize)
        var offset = 0
        while (offset < chunkSize) {
            val read = input.read(chunk, offset, chunkSize - offset)
            if (read <= 0) break
            offset += read
        }
        out.write(chunk, 0, offset)
        input.readAsciiLine()
    }
    return out.toByteArray()
}

private fun readHttpStatusLine(input: java.io.BufferedInputStream): Int {
    val statusLine = input.readAsciiLine()
    return Regex("HTTP/\\d\\.\\d (\\d+)").find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}




