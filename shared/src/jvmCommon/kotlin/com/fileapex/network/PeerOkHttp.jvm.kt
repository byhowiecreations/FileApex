package com.fileapex.network

import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.domain.transfer.TransferOwner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** What a peer response may cost: control replies are small JSON, so nothing larger is read into memory. */
internal const val MAX_PEER_RESPONSE_BYTES = 32 * 1024 * 1024
internal const val MAX_HEADER_LINE_CHARS = 16 * 1024
internal const val MAX_HEADER_LINES = 256

/** The connect step failed (as opposed to the exchange): the caller tries the next local address. */
internal class BoundConnectFailed(cause: Throwable) : Exception(cause)

/** OkHttp must not add what the hand-written client never sent: a User-Agent and a gzip offer. */
private val plainWire = Interceptor { chain ->
    chain.proceed(chain.request().newBuilder().removeHeader("User-Agent").removeHeader("Accept-Encoding").build())
}

/** One shared base: HTTP/1.1 only, no proxy, no redirects, no connection reuse (every request says `Connection: close`). */
private val baseClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .proxy(Proxy.NO_PROXY)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(plainWire)
        .build()
}

private fun clientFor(factory: PinnedPeerSocketFactory, connectMs: Long, readMs: Long, writeMs: Long): OkHttpClient =
    baseClient.newBuilder()
        .socketFactory(factory)
        .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(readMs, TimeUnit.MILLISECONDS)
        .writeTimeout(writeMs, TimeUnit.MILLISECONDS)
        .build()

private fun requestFor(host: String, port: Int, pathWithQuery: String, accept: String?): Request.Builder {
    val builder = Request.Builder()
        .url("http://$host:$port${PeerPinHeader.stripPin(pathWithQuery)}")
        .header("Connection", "close")
    PeerPinHeader.pinOf(pathWithQuery)?.let { builder.header(PeerPinHeader.NAME, it) }
    accept?.let { builder.header("Accept", it) }
    return builder
}

/** The header caps of the hand-written reader, applied to what OkHttp parsed. */
private fun requireBoundedHeaders(response: Response) {
    if (response.headers.size > MAX_HEADER_LINES) throw IOException("Too many HTTP header lines")
    for (index in 0 until response.headers.size) {
        if (response.headers.name(index).length + response.headers.value(index).length > MAX_HEADER_LINE_CHARS) throw IOException("HTTP header line too long")
    }
}

private fun lowerCaseHeaders(response: Response): Map<String, String> {
    val headers = HashMap<String, String>()
    for (index in 0 until response.headers.size) headers[response.headers.name(index).lowercase()] = response.headers.value(index).trim()
    return headers
}

private fun Response.toPeerResponse(): PeerBoundHttpResponse {
    requireBoundedHeaders(this)
    if (body.contentLength() > MAX_PEER_RESPONSE_BYTES) throw IOException("Peer response exceeds $MAX_PEER_RESPONSE_BYTES bytes")
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    val source = body.source()
    while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        if (out.size() + read > MAX_PEER_RESPONSE_BYTES) throw IOException("Peer response exceeds $MAX_PEER_RESPONSE_BYTES bytes")
        out.write(buffer, 0, read)
    }
    return PeerBoundHttpResponse(statusCode = code, body = out.toByteArray().toString(Charsets.UTF_8).trim(), headers = lowerCaseHeaders(this))
}

/** A connect failure is reported as the exception the socket saw, never as OkHttp's wrapper. */
private fun Call.executeOrConnectFailure(failure: ConnectFailure): Response = try {
    execute()
} catch (error: IOException) {
    throw failure.error ?: error
}

/** Small GET and POST: the JSON control calls. */
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
    val connectMs = bindConnectTimeoutMs(localIp, timeoutMs).toLong()
    val readMs = timeoutMs.coerceIn(250L, 60_000L)
    val failure = ConnectFailure()
    val client = clientFor(PinnedPeerSocketFactory(localIp, dialToken, failure), connectMs, readMs, readMs)
    val builder = requestFor(host, port, path, "application/json")
    if (contentType != null) {
        builder.method(method, body.orEmpty().toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()))
    } else {
        builder.method(method, null)
    }
    return client.newCall(builder.build()).executeOrConnectFailure(failure).use { it.toPeerResponse() }
}

/** Runs [block] while a cancelled coroutine cancels [call], which is what ends a read that is waiting on a slow peer. */
private suspend fun <T> withCall(call: Call, block: suspend () -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}

/** A download in bounded buffers; a body shorter than it promised fails the transfer. */
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
    val connectMs = bindConnectTimeoutMs(localIp, connectTimeoutMs).toLong()
    val idleMs = readIdleTimeoutMs.coerceIn(1000L, 600_000L)
    val failure = ConnectFailure()
    val client = clientFor(PinnedPeerSocketFactory(localIp, dialToken, failure), connectMs, idleMs, idleMs)
    val builder = requestFor(host, port, pathWithQuery, "application/octet-stream")
    val resumeOffset = pathWithQuery.substringAfter("offset=", missingDelimiterValue = "").substringBefore('&').toLongOrNull()
    if (resumeOffset != null && resumeOffset > 0L) builder.header("Range", "bytes=$resumeOffset-")
    val call = client.newCall(builder.build())
    return withCall(call) {
        val response = try {
            call.execute()
        } catch (error: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("stream cancelled", error)
            throw failure.error?.let { BoundConnectFailed(it) } ?: error
        }
        response.use {
            onStatus?.invoke(it.code)
            requireBoundedHeaders(it)
            if (it.code in 200..299) {
                val source = it.body.source()
                val buffer = ByteArray(SocketFileStreamer.BUFFER_BYTES)
                try {
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        onChunk(buffer, read)
                    }
                } catch (error: IOException) {
                    if (!currentCoroutineContext().isActive) throw CancellationException("stream cancelled", error)
                    throw error
                }
            }
            PeerBoundStreamResult(statusCode = it.code)
        }
    }
}

/** An upload written straight from [writeBody]: with that Content-Length when it is known, chunked when it is not. */
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
    val context = currentCoroutineContext()
    val owner = context[TransferOwner]?.id.orEmpty()
    val connectMs = bindConnectTimeoutMs(localIp, connectTimeoutMs).toLong()
    val idleMs = uploadIdleTimeoutMs.coerceIn(1000L, 600_000L)
    val failure = ConnectFailure()
    var tracked: java.net.Socket? = null
    val factory = PinnedPeerSocketFactory(localIp, dialToken, failure) { socket ->
        tracked = socket
        TransferActivityGuard.trackTransferSocket(socket, owner)
    }
    val client = clientFor(factory, connectMs, idleMs, idleMs)
    val mediaType = contentType.toMediaType()
    val body = object : RequestBody() {
        override fun contentType() = mediaType

        override fun contentLength() = contentLength ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val output = sink.outputStream()
            runBlocking(context.minusKey(ContinuationInterceptor)) { writeBody(output) }
        }
    }
    val call = client.newCall(requestFor(host, port, pathWithQuery, null).post(body).build())
    try {
        return withCall(call) {
            try {
                call.execute().use { it.toPeerResponse() }
            } catch (error: IOException) {
                val connect = failure.error
                if (TransferActivityGuard.transferCancelRequested(owner) || !currentCoroutineContext().isActive) {
                    throw CancellationException("transfer cancelled")
                }
                throw if (connect != null) BoundConnectFailed(connect) else error
            }
        }
    } finally {
        tracked?.let { TransferActivityGuard.releaseTransferSocket(it) }
        try {
            tracked?.close()
        } catch (_: IOException) {
        }
    }
}
