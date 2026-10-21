package com.fileapex.network

import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.iroh.irohLoopbackOrNull
import com.fileapex.tailscale.tailscaleLoopbackOrNull
import com.fileapex.tailscale.writeTailscaleDialToken
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching
import kotlin.coroutines.cancellation.CancellationException
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

private fun executeBoundHttp(
    host: String,
    port: Int,
    method: String,
    path: String,
    body: String?,
    contentType: String?,
    timeoutMs: Long
): PeerBoundHttpResponse? {
    val candidates = LanInterfaceBinding.bindCandidatesForPeer(host)
    if (candidates.isEmpty()) {
        return null
    }
    for (localIp in candidates) {
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
    val candidates = LanInterfaceBinding.bindCandidatesForPeer(host)
    if (candidates.isEmpty()) {
        return null
    }
    for (localIp in candidates) {
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
    val connectTimeout = connectTimeoutMs.coerceIn(250L, 60_000L).toInt()
    val idleTimeout = readIdleTimeoutMs.coerceIn(1000L, 600_000L).toInt()
    val socket = Socket()
    try {
        socket.bind(InetSocketAddress(localIp, 0))
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
    val candidates = LanInterfaceBinding.bindCandidatesForPeer(host)
    if (candidates.isEmpty()) {
        return null
    }
    for (localIp in candidates) {
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
    val connectTimeout = connectTimeoutMs.coerceIn(250L, 60_000L).toInt()
    val idleTimeout = uploadIdleTimeoutMs.coerceIn(1000L, 600_000L).toInt()
    val socket = Socket()
    return withTrackedTransferSocket(socket) {
        socket.bind(InetSocketAddress(localIp, 0))
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
        val raw = io.getInputStream().readBoundedBytes().toString(Charsets.UTF_8)
        parseHttpResponse(raw)
    }
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
    val timeout = timeoutMs.coerceIn(250L, 60_000L).toInt()
    Socket().use { socket ->
        socket.bind(InetSocketAddress(localIp, 0))
        val io = PeerTlsConnector.connect(socket, host, port, timeout)
        io.soTimeout = timeout
        val payload = body.orEmpty()
        val output = io.getOutputStream()
        writeTailscaleDialToken(output, dialToken)
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
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()
        val raw = io.getInputStream().readBoundedBytes().toString(Charsets.UTF_8)
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

private fun readHttpStatusLine(input: java.io.BufferedInputStream): Int {
    val statusLine = input.readAsciiLine()
    return Regex("HTTP/\\d\\.\\d (\\d+)").find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}




