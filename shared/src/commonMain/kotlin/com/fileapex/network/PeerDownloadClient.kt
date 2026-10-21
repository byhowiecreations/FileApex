package com.fileapex.network

import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexClient.Companion.PEER_CONNECT_TIMEOUT_MS
import com.fileapex.network.FileApexClient.Companion.TRANSFER_IDLE_TIMEOUT_MS
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * Pulls files from paired peers: single resumable streams and parallel ranges. Request plumbing stays in [FileApexClient].
 */
class PeerDownloadClient internal constructor(private val http: FileApexClient, private val capabilities: TransferCapabilityCache) {
    suspend fun downloadBytes(
        host: String,
        port: Int,
        remotePath: String,
        maxBytes: Long = 25L * 1024L * 1024L
    ): ByteArray {
        val sink = Buffer()
        var total = 0L
        streamRemoteFile(host, port, remotePath) { buffer, length ->
            total += length.toLong()
            if (total > maxBytes) {
                error(AppI18n.t("file_too_large_preview_mb", (maxBytes / (1024 * 1024)).toString()))
            }
            sink.write(buffer, startIndex = 0, endIndex = length)
        }
        return sink.readByteArray()
    }

    /**
     * Pulls a remote file to [localTargetPath]. Files at or above
     * [TransferRuntime.LARGE_FILE_THRESHOLD_BYTES] are fetched as parallel byte ranges when the
     * peer supports bounded streams; everything else uses one resumable stream.
     */
    suspend fun downloadToLocal(
        host: String,
        port: Int,
        remotePath: String,
        localTargetPath: String,
        expectedSizeBytes: Long? = null,
        onProgress: ((receivedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val expected = expectedSizeBytes?.takeIf { it > 0L } ?: 0L
        val segments = TransferRuntime.segmentsFor(expected)
        if (segments > 1 && capabilities.of(host, port).rangedStream) {
            downloadSegmented(host, port, remotePath, localTargetPath, expected, segments, onProgress)
            return
        }
        val partPath = SocketFileStreamer.partPathFor(localTargetPath)
        var lastError: Throwable? = null
        for (attempt in 0 until TransferResumeProtocol.MAX_ATTEMPTS) {
            if (attempt > 0) {
                delay(TransferResumeProtocol.RETRY_DELAY_MS * attempt)
            }
            val requestedOffset = SocketFileStreamer.fileLength(partPath)
            if (expected > 0L && requestedOffset >= expected) {
                SocketFileStreamer.finalizePart(partPath, localTargetPath)
                return
            }
            val failure = transferCatching {
                TransferRuntime.streamBudget(host).withPermit {
                    downloadSingleStreamAttempt(
                        host, port, remotePath, localTargetPath, partPath, requestedOffset, expected, onProgress
                    )
                }
            }.exceptionOrNull() ?: return
            if (failure is PeerUnreachableException || failure is TransferCancelledException) throw failure
            lastError = failure
        }
        throw lastError ?: error(AppI18n.t("download_failed"))
    }

    private suspend fun downloadSingleStreamAttempt(
        host: String,
        port: Int,
        remotePath: String,
        localTargetPath: String,
        partPath: String,
        requestedOffset: Long,
        expectedSizeBytes: Long,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        var appender: java.io.RandomAccessFile? = null
        try {
            var bytesWritten = requestedOffset
            streamRemoteFile(
                host = host,
                port = port,
                remotePath = remotePath,
                offset = requestedOffset,
                onStatus = { status ->
                    if (status in 200..299) {
                        val writeOffset = if (status == 206) requestedOffset else 0L
                        appender = SocketFileStreamer.openAppender(partPath, writeOffset)
                        bytesWritten = writeOffset
                    }
                }
            ) { buffer, length ->
                val raf = appender ?: error("Download body arrived before HTTP status")
                raf.write(buffer, 0, length)
                bytesWritten += length.toLong()
                if (expectedSizeBytes > 0L) onProgress?.invoke(bytesWritten, expectedSizeBytes)
            }
            if (expectedSizeBytes > 0L && bytesWritten != expectedSizeBytes) {
                error(
                    "Download incomplete for ${Path(localTargetPath).name} " +
                        "(got $bytesWritten bytes, expected $expectedSizeBytes)"
                )
            }
            runCatching { appender?.close() }
            appender = null
            SocketFileStreamer.finalizePart(partPath, localTargetPath)
        } finally {
            runCatching { appender?.close() }
        }
    }

    private suspend fun downloadSegmented(
        host: String,
        port: Int,
        remotePath: String,
        localTargetPath: String,
        totalSize: Long,
        segments: Int,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val partPath = SocketFileStreamer.segmentPartPathFor(localTargetPath)
        java.io.File(partPath).parentFile?.mkdirs()
        val initial = RangeLedger.prepare(partPath, totalSize)
        val plan = TransferRanges.plan(totalSize, segments)
        val received = AtomicLong(TransferRanges.coveredBytes(initial).coerceAtMost(totalSize))
        onProgress?.invoke(received.get(), totalSize)
        coroutineScope {
            plan.map { segment ->
                async(TransferRuntime.outbound) {
                    downloadOneSegment(
                        host = host,
                        port = port,
                        remotePath = remotePath,
                        partPath = partPath,
                        totalSize = totalSize,
                        segment = segment,
                        isLast = segment.endExclusive == totalSize,
                        initial = initial
                    ) { delta ->
                        onProgress?.invoke(received.addAndGet(delta).coerceIn(0L, totalSize), totalSize)
                    }
                }
            }.awaitAll()
        }
        val ranges = RangeLedger.read(partPath, totalSize)
        check(TransferRanges.covers(ranges, totalSize) && SocketFileStreamer.fileLength(partPath) == totalSize) {
            "Download incomplete for ${Path(localTargetPath).name} " +
                "(got ${TransferRanges.coveredBytes(ranges)} bytes, expected $totalSize)"
        }
        SocketFileStreamer.finalizePart(partPath, localTargetPath)
        RangeLedger.delete(partPath)
    }

    private suspend fun downloadOneSegment(
        host: String,
        port: Int,
        remotePath: String,
        partPath: String,
        totalSize: Long,
        segment: ByteSpan,
        isLast: Boolean,
        initial: List<ByteSpan>,
        onBytes: (Long) -> Unit
    ) {
        var known = initial
        var lastError: Throwable? = null
        for (attempt in 0 until TransferResumeProtocol.MAX_ATTEMPTS) {
            if (attempt > 0) {
                delay(TransferResumeProtocol.RETRY_DELAY_MS * attempt)
                known = RangeLedger.read(partPath, totalSize)
            }
            val from = TransferRanges.resumePoint(segment, known)
            if (from >= segment.endExclusive) return
            val failure = transferCatching {
                TransferRuntime.streamBudget(host).withPermit {
                    streamRangeToPart(host, port, remotePath, partPath, totalSize, from, segment.endExclusive, isLast, onBytes)
                }
            }.exceptionOrNull() ?: return
            if (failure is PeerUnreachableException || failure is SourceSizeChangedException ||
                failure is TransferCancelledException
            ) {
                throw failure
            }
            lastError = failure
        }
        throw lastError ?: error(AppI18n.t("download_failed"))
    }

    private suspend fun streamRangeToPart(
        host: String,
        port: Int,
        remotePath: String,
        partPath: String,
        totalSize: Long,
        from: Long,
        end: Long,
        isLast: Boolean,
        onBytes: (Long) -> Unit
    ) {
        // The last range asks for one byte past the expected end so a file that grew since
        // listing fails loudly instead of finalizing truncated.
        val requestLength = (end - from) + if (isLast) 1L else 0L
        java.io.RandomAccessFile(partPath, "rw").use { raf ->
            raf.seek(from)
            var position = from
            var recorded = from
            try {
                streamRemoteFile(
                    host = host,
                    port = port,
                    remotePath = remotePath,
                    offset = from,
                    length = requestLength
                ) { buffer, length ->
                    if (position + length > end) {
                        throw SourceSizeChangedException("Remote file is larger than $totalSize bytes: $remotePath")
                    }
                    raf.write(buffer, 0, length)
                    position += length.toLong()
                    onBytes(length.toLong())
                    if (position - recorded >= TransferRuntime.CHECKPOINT_BYTES) {
                        raf.channel.force(false)
                        RangeLedger.record(partPath, totalSize, ByteSpan(from, position))
                        recorded = position
                    }
                }
            } finally {
                if (position > recorded) {
                    runCatching {
                        raf.channel.force(false)
                        RangeLedger.record(partPath, totalSize, ByteSpan(from, position))
                    }
                }
            }
            check(position == end) { "Range incomplete ($position of $end) for $remotePath" }
        }
    }

    suspend fun streamRemoteFile(
        host: String,
        port: Int,
        remotePath: String,
        offset: Long = 0L,
        length: Long? = null,
        onStatus: ((Int) -> Unit)? = null,
        onChunk: suspend (ByteArray, Int) -> Unit
    ) {
        PeerLanHttpPolicy.ensureRoute(host)
        val params = buildMap {
            put("path", remotePath)
            if (offset > 0L) {
                put(TransferResumeProtocol.OFFSET_QUERY, offset.toString())
            }
            if (length != null && length > 0L) {
                put(TransferResumeProtocol.LENGTH_QUERY, length.toString())
            }
        }
        val result = peerHttpGetStreaming(
            host = host,
            port = port,
            pathWithQuery = http.withSenderQuery(
                http.queryPath(
                    basePath = "/api/v1/files/stream",
                    host = host,
                    port = port,
                    params = params
                )
            ),
            connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
            readIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
            onChunk = onChunk,
            onStatus = onStatus
        ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (result.statusCode == 403) {
            error(com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
        }
        require(result.statusCode in 200..299) {
            "Stream failed (${result.statusCode})"
        }
    }
}
