package com.omninode.domain.transfer

import com.omninode.network.OmniNodeClient
import com.omninode.platform.UniqueFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
import kotlinx.io.write

/**
 * Coordinates Multi Copy: one source stream fan-out to many destinations in parallel.
 * Reads each source file once and multiplexes the same immutable chunk reference to every
 * destination channel (no per-destination copies). Writer results are gathered via [awaitAll].
 */
class MultiCopyBroadcastEngine(
    private val client: OmniNodeClient
) {
    suspend fun broadcast(
        sources: List<MultiCopySource>,
        destinations: List<MultiCopyDestination>
    ): List<MultiCopyResult> = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "Select at least one file" }
        require(destinations.isNotEmpty()) { "Select at least one destination device" }
        sources.map { source ->
            broadcastOne(source, destinations)
        }
    }

    private suspend fun broadcastOne(
        source: MultiCopySource,
        destinations: List<MultiCopyDestination>
    ): MultiCopyResult = coroutineScope {
        // Small bound keeps only a few shared chunk refs in flight per destination.
        val chunkChannels = destinations.map {
            Channel<ByteArray>(capacity = CHANNEL_CAPACITY)
        }

        val writers = destinations.mapIndexed { index, destination ->
            async(Dispatchers.IO) {
                runCatching {
                    when (destination) {
                        is MultiCopyDestination.LocalDevice -> {
                            writeLocalFromChannel(
                                absolutePath = destination.absolutePath,
                                chunks = chunkChannels[index]
                            )
                        }
                        is MultiCopyDestination.RemoteDevice -> {
                            client.uploadFromChunkChannel(
                                host = destination.host,
                                port = destination.port,
                                remoteTargetPath = destination.absolutePath,
                                chunks = chunkChannels[index],
                                contentLength = source.sizeBytes.takeIf { it > 0 }
                            )
                        }
                    }
                    WriterOutcome(deviceId = destination.deviceId, errorMessage = null)
                }.getOrElse { error ->
                    runCatching { chunkChannels[index].close() }
                    WriterOutcome(
                        deviceId = destination.deviceId,
                        errorMessage = error.message
                            ?: "Transfer failed on ${destination.deviceName}"
                    )
                }
            }
        }

        val producer = launch(Dispatchers.IO) {
            try {
                streamSource(source) { chunk ->
                    // Same immutable array ref to every channel — no per-destination copyOf().
                    for (channel in chunkChannels) {
                        runCatching { channel.send(chunk) }
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

    private suspend fun streamSource(
        source: MultiCopySource,
        onChunk: suspend (ByteArray) -> Unit
    ) {
        when (source) {
            is MultiCopySource.Local -> {
                val path = Path(source.absolutePath)
                check(SystemFileSystem.exists(path)) { "Missing local file: ${source.absolutePath}" }
                SystemFileSystem.source(path).buffered().use { input ->
                    val buffer = ByteArray(OmniNodeClient.CHUNK_SIZE)
                    while (!input.exhausted()) {
                        val read = input.readAtMostTo(buffer)
                        if (read > 0) {
                            // One immutable slice per read; shared by all destination channels.
                            onChunk(buffer.copyOf(read))
                        }
                    }
                }
            }
            is MultiCopySource.Remote -> {
                client.streamRemoteFile(
                    host = source.host,
                    port = source.port,
                    remotePath = source.absolutePath,
                    onChunk = onChunk
                )
            }
        }
    }

    private suspend fun writeLocalFromChannel(
        absolutePath: String,
        chunks: Channel<ByteArray>
    ) {
        val resolved = UniqueFileNames.resolve(absolutePath)
        val target = Path(resolved)
        target.parent?.let { parent ->
            if (!SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
        }
        SystemFileSystem.sink(target).buffered().use { sink ->
            for (chunk in chunks) {
                sink.write(chunk)
            }
        }
    }

    private data class WriterOutcome(
        val deviceId: String,
        val errorMessage: String?
    )

    companion object {
        private const val CHANNEL_CAPACITY = 2
    }
}
