package com.fileapex.network

import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.tailscale.writeTailscaleDialToken
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException

internal suspend fun PeerHttpPlatform.executeBoundGetStreamingOnLocalIp(
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
    val connectTimeout = bindConnectTimeoutMs(localIp, connectTimeoutMs)
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

internal suspend fun PeerHttpPlatform.executeBoundUploadOnLocalIp(
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
    val connectTimeout = bindConnectTimeoutMs(localIp, connectTimeoutMs)
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

internal fun PeerHttpPlatform.executeBoundHttpOnLocalIp(
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
    val connectTimeout = bindConnectTimeoutMs(localIp, timeoutMs)
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

internal class BoundConnectFailed(cause: Throwable) : Exception(cause)
