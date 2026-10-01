package com.fileapex.domain.transfer

import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexClient
import com.fileapex.network.PeerUnreachableException
import com.fileapex.network.SocketFileStreamer
import com.fileapex.network.TransferRuntime
import com.fileapex.network.transferCatching
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.generateDeviceId
import com.fileapex.util.TimeUtils
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates Multi Copy for one source file across its destinations.
 *
 * Local sources, and any source with a single destination, run one independent transfer per
 * destination through [FileApexClient] (which handles ranges, resume, and retries). A remote
 * source going to several destinations is read once and multiplexed to every destination
 * channel so the source peer is not asked for the same bytes N times.
 */
class MultiCopyBroadcastEngine(
    private val client: FileApexClient
) {
    suspend fun broadcast(
        sources: List<MultiCopySource>,
        destinations: List<MultiCopyDestination>
    ): List<MultiCopyResult> = withContext(TransferRuntime.outbound) {
        require(sources.isNotEmpty()) { AppI18n.t("select_at_least_one_file") }
        require(destinations.isNotEmpty()) { AppI18n.t("select_destination_device") }
        sources.map { source ->
            broadcastOne(source, destinations)
        }
    }

    private suspend fun broadcastOne(
        source: MultiCopySource,
        destinations: List<MultiCopyDestination>
    ): MultiCopyResult = coroutineScope {
        val verifiedSource = when (source) {
            is MultiCopySource.Local -> source.verifiedFromDisk()
            is MultiCopySource.Remote -> source
        }
        TransferActivityGuard.setTransferContext(
            fileName = verifiedSource.fileName,
            destinationDeviceName = destinations.joinToString(", ") { it.deviceName }
        )
        val distinct = destinations.distinctBy { it.deviceId }
        if (verifiedSource.isDirectory) {
            val outcomes = distinct.map { destination ->
                val failure = transferCatching {
                    when (destination) {
                        is MultiCopyDestination.LocalDevice -> {
                            java.io.File(destination.absolutePath).mkdirs()
                        }
                        is MultiCopyDestination.RemoteDevice -> {
                            client.createDirectory(destination.host, destination.port, destination.absolutePath)
                        }
                    }
                }.exceptionOrNull()
                outcomeOf(destination, failure)
            }
            return@coroutineScope resultOf(verifiedSource.fileName, outcomes)
        }

        val txTimestamp = TimeUtils.now()
        val txMap = distinct.associate { it.deviceId to generateDeviceId() }

        if (verifiedSource is MultiCopySource.Local || distinct.size == 1) {
            val outcomes = distinct.map { destination ->
                async(TransferRuntime.outbound) {
                    transferIndependently(
                        source = verifiedSource,
                        destination = destination,
                        transactionId = txMap.getValue(destination.deviceId),
                        transactionTimestamp = txTimestamp
                    )
                }
            }.awaitAll()
            return@coroutineScope resultOf(verifiedSource.fileName, outcomes)
        }

        val fanOut = fanOut(
            source = verifiedSource,
            destinations = distinct,
            destinationTransactions = txMap,
            transactionTimestamp = txTimestamp
        )
        // Destinations that dropped out of the shared stream finish on their own resumable path.
        val retried = fanOut.filter { it.errorMessage != null && !it.unreachable }.map { failed ->
            val destination = distinct.first { it.deviceId == failed.deviceId }
            async(TransferRuntime.outbound) {
                transferIndependently(
                    source = verifiedSource,
                    destination = destination,
                    transactionId = txMap.getValue(destination.deviceId),
                    transactionTimestamp = txTimestamp
                )
            }
        }.awaitAll().associateBy { it.deviceId }
        resultOf(verifiedSource.fileName, fanOut.map { retried[it.deviceId] ?: it })
    }

    private suspend fun transferIndependently(
        source: MultiCopySource,
        destination: MultiCopyDestination,
        transactionId: String,
        transactionTimestamp: Long
    ): WriterOutcome {
        val streamKey = "${destination.deviceId}|$transactionId"
        TransferActivityGuard.beginTransfer(
            fileName = source.fileName,
            destinationDeviceName = destination.deviceName,
            deviceId = destination.deviceId,
            streamKey = streamKey
        )
        try {
            val onProgress: (Long, Long) -> Unit = { sent, total ->
                TransferActivityGuard.updateProgress(sent, total, destination.deviceId, streamKey)
            }
            val failure = transferCatching {
                when (destination) {
                    is MultiCopyDestination.LocalDevice -> when (source) {
                        is MultiCopySource.Local -> copyLocalWithResume(source, destination.absolutePath, onProgress)
                        is MultiCopySource.Remote -> client.downloadToLocal(
                            host = source.host,
                            port = source.port,
                            remotePath = source.absolutePath,
                            localTargetPath = destination.absolutePath,
                            expectedSizeBytes = source.sizeBytes.takeIf { it > 0L },
                            onProgress = onProgress
                        )
                    }
                    is MultiCopyDestination.RemoteDevice -> when (source) {
                        is MultiCopySource.Local -> client.uploadFromLocal(
                            host = destination.host,
                            port = destination.port,
                            localSourcePath = source.absolutePath,
                            remoteTargetPath = destination.absolutePath,
                            transactionId = transactionId,
                            transactionTimestampEpochMs = transactionTimestamp,
                            onProgress = onProgress
                        )
                        is MultiCopySource.Remote -> client.relayRemoteFile(
                            sourceHost = source.host,
                            sourcePort = source.port,
                            sourcePath = source.absolutePath,
                            sizeBytes = source.sizeBytes,
                            host = destination.host,
                            port = destination.port,
                            remoteTargetPath = destination.absolutePath,
                            transactionId = transactionId,
                            transactionTimestampEpochMs = transactionTimestamp,
                            onProgress = onProgress
                        )
                    }
                }
            }.exceptionOrNull()
            return outcomeOf(destination, failure)
        } finally {
            TransferActivityGuard.endTransfer(destination.deviceId, streamKey)
        }
    }

    /** Single read of [source] multiplexed to every destination; one attempt, no resume. */
    private suspend fun fanOut(
        source: MultiCopySource,
        destinations: List<MultiCopyDestination>,
        destinationTransactions: Map<String, String>,
        transactionTimestamp: Long
    ): List<WriterOutcome> = coroutineScope {
        val chunkChannels = destinations.map {
            Channel<ByteArray>(capacity = CHANNEL_CAPACITY)
        }
        val totalBytes = source.sizeBytes

        val writers = destinations.mapIndexed { index, destination ->
            async(TransferRuntime.outbound) {
                val failure = transferCatching {
                    when (destination) {
                        is MultiCopyDestination.LocalDevice -> {
                            writeLocalFromChannel(
                                absolutePath = destination.absolutePath,
                                chunks = chunkChannels[index],
                                totalSize = totalBytes
                            )
                        }
                        is MultiCopyDestination.RemoteDevice -> {
                            client.uploadFromChunkChannel(
                                host = destination.host,
                                port = destination.port,
                                remoteTargetPath = destination.absolutePath,
                                chunks = chunkChannels[index],
                                contentLength = totalBytes,
                                resumeOffset = 0L,
                                totalSize = totalBytes,
                                transactionId = destinationTransactions[destination.deviceId],
                                transactionTimestampEpochMs = transactionTimestamp
                            )
                        }
                    }
                }.exceptionOrNull()
                if (failure != null) {
                    // Closing lets the producer skip this channel instead of parking on it.
                    runCatching { chunkChannels[index].close() }
                }
                outcomeOf(destination, failure)
            }
        }

        // One read feeds every destination, so it counts once per destination toward the batch.
        val streamKey = "fanout|$transactionTimestamp|${source.fileName}"
        val fanOutTotal = totalBytes * destinations.size
        var sentBytes = 0L
        TransferActivityGuard.beginTransfer(fileName = source.fileName, streamKey = streamKey)

        // A source failure must not cancel the scope: writers still report, then retry alone.
        var producerError: Throwable? = null
        val producer = launch(TransferRuntime.outbound) {
            try {
                streamSource(source) { chunk ->
                    sentBytes += chunk.size
                    TransferActivityGuard.updateProgress(sentBytes * destinations.size, fanOutTotal, streamKey = streamKey)
                    coroutineScope {
                        chunkChannels.map { channel ->
                            async {
                                try {
                                    channel.send(chunk)
                                } catch (_: ClosedSendChannelException) {
                                    // Writer already failed; its outcome carries the error.
                                }
                            }
                        }.awaitAll()
                    }
                }
                chunkChannels.forEach { channel ->
                    runCatching { channel.close() }
                }
            } catch (cancelled: CancellationException) {
                chunkChannels.forEach { channel -> channel.cancel(cancelled) }
                throw cancelled
            } catch (error: Throwable) {
                producerError = error
                chunkChannels.forEach { channel -> channel.close(error) }
            }
        }

        val outcomes = try {
            producer.join()
            writers.awaitAll()
        } finally {
            TransferActivityGuard.endTransfer(streamKey = streamKey)
        }
        val readFailure = producerError ?: return@coroutineScope outcomes
        outcomes.map { outcome ->
            if (outcome.errorMessage != null) {
                outcome
            } else {
                outcome.copy(errorMessage = readFailure.message ?: AppI18n.t("source_read_failed"))
            }
        }
    }

    private fun copyLocalWithResume(
        source: MultiCopySource.Local,
        absolutePath: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val resolved = UniqueFileNames.resolve(absolutePath)
        val partPath = SocketFileStreamer.partPathFor(resolved)
        val offset = SocketFileStreamer.fileLength(partPath).coerceAtMost(source.sizeBytes)
        SocketFileStreamer.openAppender(partPath, offset).use { raf ->
            var written = offset
            SocketFileStreamer.streamFromOffset(source.absolutePath, offset) { buffer, length ->
                raf.write(buffer, 0, length)
                written += length.toLong()
                onProgress(written, source.sizeBytes)
            }
        }
        SocketFileStreamer.finalizePart(partPath, resolved)
    }

    private suspend fun streamSource(
        source: MultiCopySource,
        onChunk: suspend (ByteArray) -> Unit
    ) {
        when (source) {
            is MultiCopySource.Local -> {
                val buffer = ByteArray(FileApexClient.CHUNK_SIZE)
                RandomAccessFile(source.absolutePath, "r").use { raf ->
                    while (true) {
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        onChunk(buffer.copyOf(read))
                    }
                }
            }
            is MultiCopySource.Remote -> {
                client.streamRemoteFile(
                    host = source.host,
                    port = source.port,
                    remotePath = source.absolutePath
                ) { buffer, length ->
                    onChunk(buffer.copyOf(length))
                }
            }
        }
    }

    private suspend fun writeLocalFromChannel(
        absolutePath: String,
        chunks: Channel<ByteArray>,
        totalSize: Long
    ) {
        val resolved = UniqueFileNames.resolve(absolutePath)
        val partPath = SocketFileStreamer.partPathFor(resolved)
        var written = 0L
        SocketFileStreamer.openAppender(partPath, 0L).use { raf ->
            for (chunk in chunks) {
                raf.write(chunk)
                written += chunk.size
            }
        }
        if (totalSize > 0L && written != totalSize) {
            throw java.io.IOException("Incomplete copy of ${resolved.substringAfterLast('/')}: $written of $totalSize bytes")
        }
        SocketFileStreamer.finalizePart(partPath, resolved)
    }

    private fun outcomeOf(destination: MultiCopyDestination, failure: Throwable?): WriterOutcome =
        if (failure == null) {
            WriterOutcome(destination.deviceId, errorMessage = null)
        } else {
            WriterOutcome(
                deviceId = destination.deviceId,
                errorMessage = failure.message ?: AppI18n.t("transfer_failed_on", destination.deviceName),
                unreachable = failure is PeerUnreachableException
            )
        }

    private fun resultOf(fileName: String, outcomes: List<WriterOutcome>): MultiCopyResult =
        MultiCopyResult(
            fileName = fileName,
            succeededDeviceIds = outcomes.filter { it.errorMessage == null }.map { it.deviceId }.toSet(),
            failures = outcomes.mapNotNull { outcome -> outcome.errorMessage?.let { outcome.deviceId to it } }.toMap(),
            unreachableDeviceIds = outcomes.filter { it.unreachable }.map { it.deviceId }.toSet()
        )

    private data class WriterOutcome(
        val deviceId: String,
        val errorMessage: String?,
        val unreachable: Boolean = false
    )

    companion object {
        private const val CHANNEL_CAPACITY = 2
    }
}
