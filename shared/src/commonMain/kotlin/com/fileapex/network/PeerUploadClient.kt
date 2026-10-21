package com.fileapex.network

import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexClient.Companion.PEER_CONNECT_TIMEOUT_MS
import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS
import com.fileapex.network.FileApexClient.Companion.TRANSFER_IDLE_TIMEOUT_MS
import com.fileapex.platform.generateDeviceId
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Pushes files to paired peers (resumable and segmented uploads) and relays a file from one peer to another.
 */
class PeerUploadClient internal constructor(private val http: FileApexClient, private val capabilities: TransferCapabilityCache, private val downloads: PeerDownloadClient) {
    suspend fun queryUploadResumeOffset(
        host: String,
        port: Int,
        remoteTargetPath: String,
        expectedSizeBytes: Long = 0L,
        transactionId: String = "", backup: Boolean = false
    ): Long {
        return cancellableCatching {
            PeerLanHttpPolicy.ensureRoute(host)
            val response = http.boundGet(
                host = host,
                port = port,
                pathWithQuery = http.withSenderQuery(
                    http.queryPath(
                        basePath = "/api/v1/files/resume",
                        host = host,
                        port = port,
                        params = buildMap {
                            put("targetPath", remoteTargetPath)
                            if (expectedSizeBytes > 0L) {
                                put(TransferResumeProtocol.EXPECTED_SIZE_QUERY, expectedSizeBytes.toString())
                            }
                            if (transactionId.isNotBlank()) put(TransferResumeProtocol.TRANSACTION_ID_QUERY, transactionId)
                            if (backup) put("backup", "1")
                        }
                    )
                ),
                timeoutMs = PEER_REQUEST_TIMEOUT_MS
            )
            response
        }.getOrNull()?.let { response ->
            if (response.statusCode !in 200..299) return@let 0L
            runCatching {
                http.json.decodeFromString(ResumeOffsetResponse.serializer(), response.body).offset
            }.getOrDefault(0L).coerceAtLeast(0L)
        } ?: 0L
    }

    suspend fun uploadFromLocal(
        host: String,
        port: Int,
        localSourcePath: String,
        remoteTargetPath: String,
        knownResumeOffset: Long? = null,
        transactionId: String? = null,
        transactionTimestampEpochMs: Long? = null,
        /** Folder backup: the receiver replaces an older copy in place and skips the per-file "received" alert. */
        backup: Boolean = false,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val source = Path(localSourcePath)
        check(SystemFileSystem.exists(source)) { "Local source missing: $localSourcePath" }
        val totalSize = SystemFileSystem.metadataOrNull(source)?.size?.coerceAtLeast(0L) ?: 0L
        val txTimestamp = transactionTimestampEpochMs ?: TimeUtils.now()
        val fileSha256 = com.fileapex.platform.fileDigest(localSourcePath, -1L).orEmpty()
        val segments = TransferRuntime.segmentsFor(totalSize)
        if (!backup && segments > 1 && capabilities.of(host, port).segmentedUpload) {
            uploadSegmented(
                host = host,
                port = port,
                remoteTargetPath = remoteTargetPath,
                totalSize = totalSize,
                segments = segments,
                transactionId = transactionId?.takeIf { it.isNotBlank() } ?: generateDeviceId(),
                transactionTimestamp = txTimestamp,
                sha256 = fileSha256,
                onProgress = onProgress
            ) { start, end, pathWithQuery, onSent ->
                peerHttpUploadFromFile(
                    host = host,
                    port = port,
                    pathWithQuery = pathWithQuery,
                    contentType = "application/octet-stream",
                    sourcePath = localSourcePath,
                    offset = start,
                    length = end - start,
                    connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
                    uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
                    onProgress = { sent, _ -> onSent(sent - start) }
                )
            }
            return
        }
        val txId = transactionId.orEmpty()
        var lastError: Throwable? = null
        for (attempt in 0 until TransferResumeProtocol.MAX_ATTEMPTS) {
            if (attempt > 0) {
                delay(TransferResumeProtocol.RETRY_DELAY_MS * attempt)
            }
            val offset = (if (attempt == 0 && knownResumeOffset != null) knownResumeOffset else queryUploadResumeOffset(host, port, remoteTargetPath, totalSize, txId, backup))
                .coerceAtMost(totalSize)
            if (offset >= totalSize && totalSize > 0L) {
                onProgress?.invoke(totalSize, totalSize)
                return
            }
            val remaining = (totalSize - offset).coerceAtLeast(0L)
            val failure = transferCatching {
                TransferRuntime.streamBudget(host).withPermit {
                    PeerLanHttpPolicy.ensureRoute(host)
                    val response = peerHttpUploadFromFile(
                        host = host,
                        port = port,
                        pathWithQuery = http.withSenderQuery(
                            http.uploadPathWithQuery(
                                host = host,
                                port = port,
                                remoteTargetPath = remoteTargetPath,
                                offset = offset,
                                totalSize = totalSize,
                                transactionId = txId,
                                transactionTimestamp = txTimestamp,
                                backup = backup,
                                sha256 = fileSha256
                            )
                        ),
                        contentType = "application/octet-stream",
                        sourcePath = localSourcePath,
                        offset = offset,
                        length = remaining,
                        connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
                        uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
                        onProgress = onProgress
                    ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
                    if (response.statusCode == 403) {
                        error(AppI18n.t("pin_required_open_device"))
                    }
                    require(response.statusCode in 200..299) {
                        "${AppI18n.t("upload_failed")} (${response.statusCode})"
                    }
                }
            }.exceptionOrNull()
            if (failure == null) {
                onProgress?.invoke(totalSize, totalSize)
                return
            }
            if (failure is PeerUnreachableException || failure is TransferCancelledException) throw failure
            lastError = failure
        }
        throw lastError ?: error(AppI18n.t("upload_failed"))
    }

    /**
     * Streams a file from one peer straight into another without touching local disk.
     * Large files go as parallel ranges when both peers support it.
     */
    suspend fun relayRemoteFile(
        sourceHost: String,
        sourcePort: Int,
        sourcePath: String,
        sizeBytes: Long,
        host: String,
        port: Int,
        remoteTargetPath: String,
        transactionId: String,
        transactionTimestampEpochMs: Long,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val segments = TransferRuntime.segmentsFor(sizeBytes)
        if (segments > 1 &&
            capabilities.of(host, port).segmentedUpload &&
            capabilities.of(sourceHost, sourcePort).rangedStream
        ) {
            uploadSegmented(
                host = host,
                port = port,
                remoteTargetPath = remoteTargetPath,
                totalSize = sizeBytes,
                segments = segments,
                transactionId = transactionId,
                transactionTimestamp = transactionTimestampEpochMs,
                onProgress = onProgress
            ) { start, end, pathWithQuery, onSent ->
                relayRange(sourceHost, sourcePort, sourcePath, host, port, pathWithQuery, start, end, onSent)
            }
            return
        }
        relaySingleStream(
            sourceHost = sourceHost,
            sourcePort = sourcePort,
            sourcePath = sourcePath,
            sizeBytes = sizeBytes,
            host = host,
            port = port,
            remoteTargetPath = remoteTargetPath,
            transactionId = transactionId,
            transactionTimestampEpochMs = transactionTimestampEpochMs,
            onProgress = onProgress
        )
    }

    private suspend fun relayRange(
        sourceHost: String,
        sourcePort: Int,
        sourcePath: String,
        host: String,
        port: Int,
        pathWithQuery: String,
        start: Long,
        end: Long,
        onSent: (Long) -> Unit
    ): PeerBoundHttpResponse? = coroutineScope {
        val length = end - start
        val chunks = Channel<ByteArray>(capacity = RELAY_CHANNEL_CAPACITY)
        val producer = launch(TransferRuntime.outbound) {
            var produced = 0L
            try {
                downloads.streamRemoteFile(sourceHost, sourcePort, sourcePath, offset = start, length = length) { buffer, read ->
                    if (produced + read > length) {
                        throw SourceSizeChangedException("Source sent more than the requested range: $sourcePath")
                    }
                    chunks.send(buffer.copyOf(read))
                    produced += read.toLong()
                    onSent(produced)
                }
                chunks.close()
            } catch (error: Throwable) {
                chunks.close(error)
                throw error
            }
        }
        val response = try {
            peerHttpUploadFromChannel(
                host = host,
                port = port,
                pathWithQuery = pathWithQuery,
                contentType = "application/octet-stream",
                chunks = chunks,
                connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
                uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
                contentLength = length
            )
        } catch (error: Throwable) {
            producer.cancel()
            throw error
        }
        if (response == null) {
            // Upload never connected, so nothing drains the channel; the producer would park forever.
            producer.cancel()
        } else {
            producer.join()
        }
        response
    }

    private suspend fun relaySingleStream(
        sourceHost: String,
        sourcePort: Int,
        sourcePath: String,
        sizeBytes: Long,
        host: String,
        port: Int,
        remoteTargetPath: String,
        transactionId: String,
        transactionTimestampEpochMs: Long,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        var lastError: Throwable? = null
        for (attempt in 0 until TransferResumeProtocol.MAX_ATTEMPTS) {
            if (attempt > 0) {
                delay(TransferResumeProtocol.RETRY_DELAY_MS * attempt)
            }
            val offset = if (sizeBytes > 0L) {
                queryUploadResumeOffset(host, port, remoteTargetPath, sizeBytes, transactionId).coerceAtMost(sizeBytes)
            } else {
                0L
            }
            if (sizeBytes > 0L && offset >= sizeBytes) {
                onProgress?.invoke(sizeBytes, sizeBytes)
                return
            }
            val failure = transferCatching {
                TransferRuntime.streamBudget(host).withPermit {
                    coroutineScope {
                        val chunks = Channel<ByteArray>(capacity = RELAY_CHANNEL_CAPACITY)
                        val producer = launch(TransferRuntime.outbound) {
                            var sent = offset
                            try {
                                downloads.streamRemoteFile(sourceHost, sourcePort, sourcePath, offset = offset) { buffer, read ->
                                    chunks.send(buffer.copyOf(read))
                                    sent += read.toLong()
                                    if (sizeBytes > 0L) onProgress?.invoke(sent, sizeBytes)
                                }
                                chunks.close()
                            } catch (error: Throwable) {
                                chunks.close(error)
                                throw error
                            }
                        }
                        try {
                            uploadFromChunkChannel(
                                host = host,
                                port = port,
                                remoteTargetPath = remoteTargetPath,
                                chunks = chunks,
                                contentLength = (sizeBytes - offset).takeIf { sizeBytes > 0L },
                                resumeOffset = offset,
                                totalSize = sizeBytes.takeIf { it > 0L },
                                transactionId = transactionId,
                                transactionTimestampEpochMs = transactionTimestampEpochMs
                            )
                        } catch (error: Throwable) {
                            producer.cancel()
                            throw error
                        }
                        producer.join()
                    }
                }
            }.exceptionOrNull() ?: return
            if (failure is PeerUnreachableException || failure is TransferCancelledException) throw failure
            lastError = failure
        }
        throw lastError ?: error(AppI18n.t("upload_failed"))
    }

    /**
     * Sends [totalSize] bytes as parallel ranges, then asks the receiver to finalize.
     * [sendRange] streams `[start, end)` to the given path and reports bytes sent within it.
     * Coordinator coroutines hold no stream permit; each range takes one while it moves bytes.
     */
    private suspend fun uploadSegmented(
        host: String,
        port: Int,
        remoteTargetPath: String,
        totalSize: Long,
        segments: Int,
        transactionId: String,
        transactionTimestamp: Long,
        sha256: String = "",
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)?,
        sendRange: suspend (start: Long, end: Long, pathWithQuery: String, onSent: (Long) -> Unit) -> PeerBoundHttpResponse?
    ) {
        val plan = TransferRanges.plan(totalSize, segments)
        for (round in 0 until SEGMENT_COMPLETE_ROUNDS) {
            val state = querySegmentState(host, port, remoteTargetPath, totalSize, transactionId, prepare = round == 0)
            if (state.complete) {
                onProgress?.invoke(totalSize, totalSize)
                return
            }
            // Progress is what the receiver confirms, not what the socket accepted: userspace and
            // carrier buffers can hold tens of MB that have not arrived.
            val alreadyThere = TransferRanges.coveredBytes(state.ranges).coerceAtMost(totalSize)
            val confirmed = AtomicLong(alreadyThere)
            val sentByUs = AtomicLong(0L)
            val overIroh = com.fileapex.iroh.IrohBridgePorts.contains(port) && host == "127.0.0.1"
            onProgress?.invoke(confirmed.get(), totalSize)
            coroutineScope {
                val poller = launch(TransferRuntime.outbound) {
                    while (true) {
                        delay(CONFIRM_POLL_INTERVAL_MS)
                        val polled = try {
                            withTimeoutOrNull(com.fileapex.iroh.IrohTransferProgress.pollTimeoutMs(host, port, CONFIRM_POLL_TIMEOUT_MS)) {
                                querySegmentState(host, port, remoteTargetPath, totalSize, transactionId, prepare = false)
                            }
                        } catch (cancelled: TransferCancelledException) {
                            throw cancelled
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        if (polled.complete) continue
                        val landed = (TransferRanges.coveredBytes(polled.ranges) + polled.inFlightBytes)
                            .coerceIn(0L, totalSize)
                        val shown = confirmed.updateAndGet { previous -> maxOf(previous, landed) }
                        onProgress?.invoke(shown, totalSize)
                    }
                }
                try {
                plan.map { segment ->
                    async(TransferRuntime.outbound) {
                        uploadOneSegment(
                            host = host,
                            port = port,
                            remoteTargetPath = remoteTargetPath,
                            totalSize = totalSize,
                            transactionId = transactionId,
                            transactionTimestamp = transactionTimestamp,
                            segment = segment,
                            initial = state.ranges,
                            sendRange = sendRange
                        ) { delta ->
                            if (overIroh) {
                                val sent = sentByUs.addAndGet(delta)
                                val shown = confirmed.updateAndGet { previous ->
                                    com.fileapex.iroh.IrohTransferProgress.shown(previous, alreadyThere, sent, totalSize)
                                }
                                onProgress?.invoke(shown, totalSize)
                            }
                        }
                    }
                }.awaitAll()
                } finally {
                    poller.cancel()
                }
            }
            val response = http.boundPost(
                host = host,
                port = port,
                pathWithQuery = segmentPath(
                    basePath = "/api/v1/files/upload-complete",
                    host = host,
                    port = port,
                    remoteTargetPath = remoteTargetPath,
                    totalSize = totalSize,
                    transactionId = transactionId,
                    extra = buildMap {
                        put(TransferResumeProtocol.TIMESTAMP_QUERY, transactionTimestamp.toString())
                        if (sha256.isNotBlank()) put(TransferResumeProtocol.SHA256_QUERY, sha256)
                    }
                ),
                body = "",
                contentType = "text/plain",
                timeoutMs = PEER_REQUEST_TIMEOUT_MS
            )
            when {
                response.statusCode in 200..299 -> {
                    onProgress?.invoke(totalSize, totalSize)
                    return
                }
                response.statusCode == 403 -> error(AppI18n.t("pin_required_open_device"))
                response.statusCode != 409 -> error("${AppI18n.t("upload_failed")} (${response.statusCode})")
            }
        }
        error("${AppI18n.t("upload_failed")} (segments_missing)")
    }

    private suspend fun uploadOneSegment(
        host: String,
        port: Int,
        remoteTargetPath: String,
        totalSize: Long,
        transactionId: String,
        transactionTimestamp: Long,
        segment: ByteSpan,
        initial: List<ByteSpan>,
        sendRange: suspend (start: Long, end: Long, pathWithQuery: String, onSent: (Long) -> Unit) -> PeerBoundHttpResponse?,
        onDelta: (Long) -> Unit
    ) {
        var known = initial
        var lastError: Throwable? = null
        for (attempt in 0 until TransferResumeProtocol.MAX_ATTEMPTS) {
            if (attempt > 0) {
                delay(TransferResumeProtocol.RETRY_DELAY_MS * attempt)
                val state = querySegmentState(host, port, remoteTargetPath, totalSize, transactionId, prepare = false)
                if (state.complete) return
                known = state.ranges
            }
            val from = TransferRanges.resumePoint(segment, known)
            if (from >= segment.endExclusive) return
            val pathWithQuery = segmentPath(
                basePath = "/api/v1/files/upload-segment",
                host = host,
                port = port,
                remoteTargetPath = remoteTargetPath,
                totalSize = totalSize,
                transactionId = transactionId,
                extra = mapOf(
                    TransferResumeProtocol.OFFSET_QUERY to from.toString(),
                    TransferResumeProtocol.LENGTH_QUERY to (segment.endExclusive - from).toString(),
                    TransferResumeProtocol.TIMESTAMP_QUERY to transactionTimestamp.toString()
                )
            )
            val result = transferCatching {
                PeerLanHttpPolicy.ensureRoute(host)
                TransferRuntime.streamBudget(host).withPermit {
                    var reported = 0L
                    sendRange(from, segment.endExclusive, http.withSenderQuery(pathWithQuery)) { sentInRange ->
                        onDelta(sentInRange - reported)
                        reported = sentInRange
                    } ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
                }
            }
            val failure = result.exceptionOrNull()
            if (failure is PeerUnreachableException || failure is SourceSizeChangedException ||
                failure is TransferCancelledException
            ) {
                throw failure
            }
            val response = result.getOrNull()
            when {
                response == null -> lastError = failure
                response.statusCode in 200..299 -> return
                response.statusCode == 410 -> throw TransferCancelledException()
                response.statusCode == 403 -> error(AppI18n.t("pin_required_open_device"))
                else -> lastError = IllegalStateException("${AppI18n.t("upload_failed")} (${response.statusCode})")
            }
        }
        throw lastError ?: error(AppI18n.t("upload_failed"))
    }

    private suspend fun querySegmentState(
        host: String,
        port: Int,
        remoteTargetPath: String,
        totalSize: Long,
        transactionId: String,
        prepare: Boolean
    ): SegmentStateResponse {
        val response = http.boundGet(
            host = host,
            port = port,
            pathWithQuery = segmentPath(
                basePath = "/api/v1/files/segments",
                host = host,
                port = port,
                remoteTargetPath = remoteTargetPath,
                totalSize = totalSize,
                transactionId = transactionId,
                extra = if (prepare) mapOf(TransferResumeProtocol.PREPARE_QUERY to "1") else emptyMap()
            ),
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            error(AppI18n.t("pin_required_open_device"))
        }
        if (response.statusCode == 410) throw TransferCancelledException()
        http.requireSuccess(response, "Segment state failed (${response.statusCode})")
        return http.json.decodeFromString(SegmentStateResponse.serializer(), response.body)
    }

    private fun segmentPath(
        basePath: String,
        host: String,
        port: Int,
        remoteTargetPath: String,
        totalSize: Long,
        transactionId: String,
        extra: Map<String, String>
    ): String = http.queryPath(
        basePath = basePath,
        host = host,
        port = port,
        params = buildMap {
            put("targetPath", remoteTargetPath)
            put(TransferResumeProtocol.TOTAL_SIZE_QUERY, totalSize.toString())
            if (transactionId.isNotBlank()) {
                put(TransferResumeProtocol.TRANSACTION_ID_QUERY, transactionId)
            }
            putAll(extra)
        }
    )

    suspend fun uploadFromChunkChannel(
        host: String,
        port: Int,
        remoteTargetPath: String,
        chunks: ReceiveChannel<ByteArray>,
        contentLength: Long? = null,
        resumeOffset: Long = 0L,
        totalSize: Long? = null,
        transactionId: String? = null,
        transactionTimestampEpochMs: Long? = null
    ) {
        PeerLanHttpPolicy.ensureRoute(host)
        val txId = transactionId.orEmpty()
        val txTimestamp = transactionTimestampEpochMs ?: com.fileapex.util.TimeUtils.now()
        val remaining = contentLength?.takeIf { it > 0L }
        val response = peerHttpUploadFromChannel(
            host = host,
            port = port,
            pathWithQuery = http.withSenderQuery(
                http.uploadPathWithQuery(
                    host = host,
                    port = port,
                    remoteTargetPath = remoteTargetPath,
                    offset = resumeOffset,
                    totalSize = totalSize ?: contentLength,
                    transactionId = txId,
                    transactionTimestamp = txTimestamp
                )
            ),
            contentType = "application/octet-stream",
            chunks = chunks,
            connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
            uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
            contentLength = remaining
        ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (response.statusCode == 403) {
            error(com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
        }
        if (response.statusCode == 410) throw TransferCancelledException()
        require(response.statusCode in 200..299) {
            "${AppI18n.t("upload_failed")} (${response.statusCode})"
        }
    }

    private companion object {
private const val RELAY_CHANNEL_CAPACITY = 2
private const val SEGMENT_COMPLETE_ROUNDS = 2
private const val CONFIRM_POLL_INTERVAL_MS = 1_000L
private const val CONFIRM_POLL_TIMEOUT_MS = 5_000L
    }
}
