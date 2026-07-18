package com.omninode.domain.transfer

import com.omninode.network.OmniNodeClient
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
 * Reads each source file once and multiplexes chunks to every selected target.
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
        val chunkChannels = destinations.map {
            Channel<ByteArray>(capacity = Channel.BUFFERED)
        }
        val failures = linkedMapOf<String, String>()
        val succeeded = linkedSetOf<String>()

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
                    succeeded += destination.deviceId
                }.onFailure { error ->
                    runCatching { chunkChannels[index].close() }
                    failures[destination.deviceId] =
                        error.message ?: "Transfer failed on ${destination.deviceName}"
                }
            }
        }

        val producer = launch(Dispatchers.IO) {
            try {
                streamSource(source) { chunk ->
                    for (channel in chunkChannels) {
                        // Skip destinations that already failed/closed so others keep receiving.
                        runCatching { channel.send(chunk.copyOf()) }
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

        runCatching { producer.join() }
            .onFailure { error ->
                destinations.forEach { dest ->
                    failures.putIfAbsent(
                        dest.deviceId,
                        error.message ?: "Source read failed"
                    )
                }
            }
        writers.awaitAll()

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
        val target = Path(absolutePath)
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
}
