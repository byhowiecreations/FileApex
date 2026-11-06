package com.fileapex.network

import com.fileapex.i18n.AppI18n
import kotlinx.io.write
import com.fileapex.network.FileApexClient.Companion.PEER_CONNECT_TIMEOUT_MS
import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS
import com.fileapex.network.FileApexClient.Companion.TRANSFER_IDLE_TIMEOUT_MS

class PeerBulletinClient internal constructor(private val http: FileApexClient) {
    suspend fun fetchBulletinDelta(
        host: String,
        port: Int,
        since: Long
    ): com.fileapex.data.bulletin.BulletinDeltaResponse {
        val response = http.boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/bulletin/delta?since=$since",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Bulletin delta failed (${response.statusCode})")
        return http.json.decodeFromString(com.fileapex.data.bulletin.BulletinDeltaResponse.serializer(), response.body)
    }

    suspend fun postBulletinSyncBatch(
        host: String,
        port: Int,
        batch: com.fileapex.data.bulletin.BulletinSyncBatch
    ): com.fileapex.data.bulletin.BulletinSyncAck {
        val payload = http.json.encodeToString(com.fileapex.data.bulletin.BulletinSyncBatch.serializer(), batch)
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/bulletin/sync/batch",
            body = payload,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Bulletin sync batch failed (${response.statusCode})")
        return http.json.decodeFromString(com.fileapex.data.bulletin.BulletinSyncAck.serializer(), response.body)
    }

    suspend fun downloadBulletinFile(
        host: String,
        port: Int,
        messageId: String,
        fileName: String,
        expectedSha256: String,
        expectedSizeBytes: Long
    ): String {
        val preferredFinal = com.fileapex.platform.UniqueFileNames.resolveInDirectory(
            com.fileapex.platform.defaultDownloadsDir(),
            fileName
        )
        val partPath = SocketFileStreamer.partPathFor(preferredFinal, messageId)
        var appender: java.io.RandomAccessFile? = null
        var finished = false
        try {
            PeerLanHttpPolicy.ensureRoute(host)
            val result = peerHttpGetStreaming(
                host = host,
                port = port,
                pathWithQuery = http.withSenderQuery(
                    http.queryPath(
                        basePath = "/api/v1/bulletin/file",
                        host = host,
                        port = port,
                        params = mapOf("messageId" to messageId, "fileName" to fileName)
                    )
                ),
                connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
                readIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
                onChunk = { buffer, length ->
                    val raf = appender ?: error("Bulletin file body arrived before HTTP status")
                    raf.write(buffer, 0, length)
                },
                onStatus = { status ->
                    if (status in 200..299) appender = SocketFileStreamer.openAppender(partPath, 0L)
                }
            ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
            if (result.statusCode == 403) {
                error(com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
            }
            require(result.statusCode in 200..299) {
                "Bulletin file pull failed (${result.statusCode})"
            }
            runCatching { appender?.close() }
            appender = null
            return finishDownload(partPath, preferredFinal, expectedSizeBytes, expectedSha256).also { finished = true }
        } finally {
            runCatching { appender?.close() }
            if (!finished) runCatching { java.io.File(partPath).delete() }
        }
    }

    companion object {
        /** Checks the finished part against the post's size and checksum, then moves it to its final name; a bad part is deleted. */
        internal fun finishDownload(partPath: String, preferredFinal: String, expectedSizeBytes: Long, expectedSha256: String): String {
            val size = SocketFileStreamer.fileLength(partPath)
            if (expectedSizeBytes > 0L && size != expectedSizeBytes) {
                java.io.File(partPath).delete()
                error("Bulletin file size mismatch (expected $expectedSizeBytes, got $size)")
            }
            val hash = com.fileapex.util.sha256HexFile(partPath)
            if (expectedSha256.isNotBlank() && !hash.equals(expectedSha256, ignoreCase = true)) {
                java.io.File(partPath).delete()
                error("Bulletin file checksum mismatch")
            }
            return SocketFileStreamer.finalizePart(partPath, preferredFinal)
        }
    }
}
