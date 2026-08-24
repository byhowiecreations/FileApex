package com.fileapex.domain.transfer

import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexClient
import com.fileapex.network.SocketFileStreamer
import com.fileapex.network.TransferResumeProtocol
import com.fileapex.platform.UniqueFileNames
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates Multi Copy: one source stream fan-out to many destinations in parallel.
 * Reads each source file once and multiplexes the same immutable chunk reference to every
 * destination channel (no per-destination copies). Writer results are gathered via [awaitAll].
 */
class MultiCopyBroadcastEngine(
    private val client: FileApexClient
) {
    suspend fun broadcast(
        sources: List<MultiCopySource>,
        destinations: List<MultiCopyDestination>
    ): List<MultiCopyResult> = withContext(Dispatchers.IO) {
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
        val plans = destinations.map { destination ->
            DestPlan(
                destination = destination,
                offset = queryDestinationOffset(destination, verifiedSource.sizeBytes)
            )
        }
        val failures = linkedMapOf<String, String>()
        val succeeded = linkedSetOf<String>()

        for (plan in plans) {
            if (plan.offset >= verifiedSource.sizeBytes && verifiedSource.sizeBytes > 0L) {
                succeeded += plan.destination.deviceId
            }
        }

        val pending = plans.filter { it.destination.deviceId !in succeeded }
        val zeroOffset = pending.filter { it.offset <= 0L }
        val resume = pending.filter { it.offset > 0L }

        if (zeroOffset.isNotEmpty()) {
            val fanOut = fanOutFromOffset(verifiedSource, zeroOffset.map { it.destination }, offset = 0L)
            succeeded += fanOut.succeededDeviceIds
            failures.putAll(fanOut.failures)
        }

        val stillFailed = destinations.filter { dest ->
            dest.deviceId !in succeeded && dest.deviceId in failures
        }
        val independent = resume.map { it.destination } + stillFailed
        for (destination in independent.distinctBy { it.deviceId }) {
            val outcome = uploadWithResume(verifiedSource, destination)
            if (outcome.errorMessage == null) {
                succeeded += destination.deviceId
                failures.remove(destination.deviceId)
            } else {
                failures[destination.deviceId] = outcome.errorMessage
                succeeded.remove(destination.deviceId)
            }
        }

        MultiCopyResult(
            fileName = verifiedSource.fileName,
            succeededDeviceIds = succeeded.toSet(),
            failures = failures.toMap()
        )
    }

    private suspend fun fanOutFromOffset(
        source: MultiCopySource,
        destinations: List<MultiCopyDestination>,
        offset: Long
    ): MultiCopyResult = coroutineScope {
        val chunkChannels = destinations.map {
            Channel<ByteArray>(capacity = CHANNEL_CAPACITY)
        }
        val remaining = (source.sizeBytes - offset).coerceAtLeast(0L)

        val writers = destinations.mapIndexed { index, destination ->
            async(Dispatchers.IO) {
                runCatching {
                    when (destination) {
                        is MultiCopyDestination.LocalDevice -> {
                            writeLocalFromChannel(
                                absolutePath = destination.absolutePath,
                                chunks = chunkChannels[index],
                                startOffset = offset,
                                totalSize = source.sizeBytes
                            )
                        }
                        is MultiCopyDestination.RemoteDevice -> {
                            client.uploadFromChunkChannel(
                                host = destination.host,
                                port = destination.port,
                                remoteTargetPath = destination.absolutePath,
                                chunks = chunkChannels[index],
                                contentLength = remaining,
                                resumeOffset = offset,
                                totalSize = source.sizeBytes
                            )
                        }
                    }
                    WriterOutcome(deviceId = destination.deviceId, errorMessage = null)
                }.getOrElse { error ->
                    runCatching { chunkChannels[index].close() }
                    WriterOutcome(
                        deviceId = destination.deviceId,
                        errorMessage = error.message
                            ?: AppI18n.t("transfer_failed_on", destination.deviceName)
                    )
                }
            }
        }

        var sentBytes = offset
        val totalBytes = source.sizeBytes
        TransferActivityGuard.updateProgress(sentBytes, totalBytes)

        val producer = launch(Dispatchers.IO) {
            try {
                streamSource(source, offset) { chunk ->
                    sentBytes += chunk.size
                    TransferActivityGuard.updateProgress(sentBytes, totalBytes)
                    coroutineScope {
                        chunkChannels.map { channel ->
                            async(Dispatchers.IO) {
                                channel.send(chunk)
                            }
                        }.awaitAll()
                    }
                }
                chunkChannels.forEach { channel ->
                    runCatching { channel.close() }
                }
            } catch (error: Throwable) {
                chunkChannels.forEach { channel ->
                    runCatching { channel.close(error) }
                }
                throw error
            }
        }

        val producerError = runCatching { producer.join() }.exceptionOrNull()
        val outcomes = writers.awaitAll()
        val failures = linkedMapOf<String, String>()
        val succeeded = linkedSetOf<String>()
        for (outcome in outcomes) {
            val message = outcome.errorMessage
            if (message == null) {
                succeeded += outcome.deviceId
            } else {
                failures[outcome.deviceId] = message
            }
        }
        if (producerError != null) {
            destinations.forEach { dest ->
                failures.putIfAbsent(
                    dest.deviceId,
                    producerError.message ?: "Source read failed"
                )
            }
        }
        MultiCopyResult(
            fileName = source.fileName,
            succeededDeviceIds = succeeded.toSet(),
            failures = failures.toMap()
        )
    }

    private suspend fun uploadWithResume(
        source: MultiCopySource,
        destination: MultiCopyDestination
    ): WriterOutcome {
        var lastError: String? = null
        repeat(TransferResumeProtocol.MAX_ATTEMPTS) { attempt ->
            val offset = queryDestinationOffset(destination, source.sizeBytes)
            if (offset >= source.sizeBytes && source.sizeBytes > 0L) {
                return WriterOutcome(destination.deviceId, errorMessage = null)
            }
            val remaining = (source.sizeBytes - offset).coerceAtLeast(0L)
            val result = runCatching {
                when (destination) {
                    is MultiCopyDestination.LocalDevice -> {
                        writeLocalFromSource(source, destination.absolutePath, offset, source.sizeBytes)
                    }
                    is MultiCopyDestination.RemoteDevice -> {
                        when (source) {
                            is MultiCopySource.Local -> {
                                client.uploadFromLocal(
                                    host = destination.host,
                                    port = destination.port,
                                    localSourcePath = source.absolutePath,
                                    remoteTargetPath = destination.absolutePath
                                )
                            }
                            is MultiCopySource.Remote -> {
                                val channel = Channel<ByteArray>(capacity = CHANNEL_CAPACITY)
                                coroutineScope {
                                    val producer = launch(Dispatchers.IO) {
                                        try {
                                            streamSource(source, offset) { chunk ->
                                                channel.send(chunk)
                                            }
                                        } finally {
                                            channel.close()
                                        }
                                    }
                                    client.uploadFromChunkChannel(
                                        host = destination.host,
                                        port = destination.port,
                                        remoteTargetPath = destination.absolutePath,
                                        chunks = channel,
                                        contentLength = remaining,
                                        resumeOffset = offset,
                                        totalSize = source.sizeBytes
                                    )
                                    producer.join()
                                }
                            }
                        }
                    }
                }
            }
            if (result.isSuccess) {
                return WriterOutcome(destination.deviceId, errorMessage = null)
            }
            lastError = result.exceptionOrNull()?.message
                ?: AppI18n.t("transfer_failed_on", destination.deviceName)
            if (attempt < TransferResumeProtocol.MAX_ATTEMPTS - 1) {
                delay(TransferResumeProtocol.RETRY_DELAY_MS)
            }
        }
        return WriterOutcome(destination.deviceId, errorMessage = lastError)
    }

    private suspend fun queryDestinationOffset(
        destination: MultiCopyDestination,
        expectedSize: Long
    ): Long = when (destination) {
        is MultiCopyDestination.LocalDevice -> {
            val resolved = UniqueFileNames.resolve(destination.absolutePath)
            SocketFileStreamer.fileLength(SocketFileStreamer.partPathFor(resolved))
        }
        is MultiCopyDestination.RemoteDevice -> {
            client.queryUploadResumeOffset(
                host = destination.host,
                port = destination.port,
                remoteTargetPath = destination.absolutePath,
                expectedSizeBytes = expectedSize
            )
        }
    }

    private suspend fun streamSource(
        source: MultiCopySource,
        offset: Long,
        onChunk: suspend (ByteArray) -> Unit
    ) {
        when (source) {
            is MultiCopySource.Local -> {
                val buffer = ByteArray(FileApexClient.CHUNK_SIZE)
                RandomAccessFile(source.absolutePath, "r").use { raf ->
                    val size = raf.length()
                    require(offset in 0L..size) { "Invalid stream offset $offset for size $size" }
                    raf.seek(offset)
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
                    remotePath = source.absolutePath,
                    offset = offset
                ) { buffer, length ->
                    onChunk(buffer.copyOf(length))
                }
            }
        }
    }

    private suspend fun writeLocalFromSource(
        source: MultiCopySource,
        absolutePath: String,
        offset: Long,
        totalSize: Long
    ) {
        val resolved = UniqueFileNames.resolve(absolutePath)
        val partPath = SocketFileStreamer.partPathFor(resolved)
        SocketFileStreamer.openAppender(partPath, offset).use { raf ->
            var written = offset
            streamSource(source, offset) { chunk ->
                raf.write(chunk)
                written += chunk.size
                TransferActivityGuard.updateProgress(written, totalSize)
            }
        }
        SocketFileStreamer.finalizePart(partPath, resolved)
    }

    private suspend fun writeLocalFromChannel(
        absolutePath: String,
        chunks: Channel<ByteArray>,
        startOffset: Long,
        totalSize: Long
    ) {
        val resolved = UniqueFileNames.resolve(absolutePath)
        val partPath = SocketFileStreamer.partPathFor(resolved)
        var written = startOffset
        SocketFileStreamer.openAppender(partPath, startOffset).use { raf ->
            for (chunk in chunks) {
                raf.write(chunk)
                written += chunk.size
                TransferActivityGuard.updateProgress(written, totalSize)
            }
        }
        SocketFileStreamer.finalizePart(partPath, resolved)
    }

    private data class DestPlan(
        val destination: MultiCopyDestination,
        val offset: Long
    )

    private data class WriterOutcome(
        val deviceId: String,
        val errorMessage: String?
    )

    companion object {
        private const val CHANNEL_CAPACITY = 2
    }
}
