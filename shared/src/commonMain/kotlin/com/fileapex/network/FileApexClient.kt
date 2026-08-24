package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.i18n.AppI18n
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Wi-Fi/Ethernet-bound HTTP client for paired peer nodes.
 *
 * All peer traffic uses [peerHttpGet], [peerHttpPost], [peerHttpUploadFromChannel], and
 * [peerHttpGetStreaming] so Android never routes RFC1918 peers over cellular.
 * Cloud traffic uses the process-wide Ktor client from [com.fileapex.di.FileApexServices].
 */
class FileApexClient(
    private val json: Json = FileApexHttpClientFactory.defaultJson,
    private val localDeviceId: () -> String = { loadLocalIdentity().deviceId }
) {
    /** In-memory PINs for peers that require PIN this session (host:port → pin). */
    private val sessionPinsLock = Any()
    private val sessionPins = mutableMapOf<String, String>()

    fun rememberSessionPin(host: String, port: Int, pin: String) {
        val trimmed = pin.trim()
        if (trimmed.isNotEmpty()) {
            synchronized(sessionPinsLock) {
                sessionPins[endpointKey(host, port)] = trimmed
            }
        }
    }

    fun clearSessionPin(host: String, port: Int) {
        synchronized(sessionPinsLock) {
            sessionPins.remove(endpointKey(host, port))
        }
    }

    private fun endpointKey(host: String, port: Int): String = "$host:$port"

    private fun sessionPin(host: String, port: Int): String? =
        synchronized(sessionPinsLock) {
            sessionPins[endpointKey(host, port)]
        }

    suspend fun listFiles(host: String, port: Int, path: String): List<RemoteFileItem> {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/files/list",
                host = host,
                port = port,
                params = mapOf("path" to path)
            ),
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        rejectPinRequired(response, "PIN required — open the device and enter its PIN")
        requireSuccess(response, "List failed (${response.statusCode}): $host:$port$path")
        return json.decodeFromString(ListSerializer(RemoteFileItem.serializer()), response.body)
    }

    suspend fun fetchPeerNodeState(
        host: String,
        port: Int,
        timeoutMs: Long = PEER_STATE_TIMEOUT_MS
    ): PeerNodeState {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/identity",
            timeoutMs = timeoutMs
        )
        requireSuccess(response, "Peer state fetch failed (${response.statusCode})")
        return json.decodeFromString(PeerNodeState.serializer(), response.body)
    }

    suspend fun fetchDeviceDiagnostics(host: String, port: Int): PeerDeviceDiagnostics {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/diagnostics",
                host = host,
                port = port
            ),
            timeoutMs = DIAGNOSTICS_TIMEOUT_MS
        )
        rejectPinRequired(response, "PIN required — open the device and enter its PIN")
        requireSuccess(response, "Device details failed (${response.statusCode})")
        return json.decodeFromString(PeerDeviceDiagnostics.serializer(), response.body)
    }

    suspend fun sendClipboard(
        host: String,
        port: Int,
        senderDeviceId: String,
        senderDeviceName: String,
        senderPublicKey: String,
        ciphertext: String,
        capturedAtEpochMs: Long
    ): com.fileapex.domain.clipboard.ClipboardSendResponse {
        val request = com.fileapex.domain.clipboard.ClipboardSendRequest(
            senderDeviceId = senderDeviceId,
            senderDeviceName = senderDeviceName,
            senderPublicKey = senderPublicKey,
            ciphertext = ciphertext,
            capturedAtEpochMs = capturedAtEpochMs
        )
        val bodyStr = json.encodeToString(com.fileapex.domain.clipboard.ClipboardSendRequest.serializer(), request)
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/clipboard/send",
                host = host,
                port = port
            ),
            body = bodyStr,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            val message = if (response.body.contains("clipboard_disabled")) {
                AppI18n.t("clipboard_disabled_on_peer")
            } else if (response.body.contains("pin_required")) {
                AppI18n.t("pin_required_open_device")
            } else {
                response.body.ifBlank { AppI18n.t("clipboard_disabled_on_peer") }
            }
            error(message)
        }
        requireSuccess(response, "Clipboard transfer failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.domain.clipboard.ClipboardSendResponse.serializer(), response.body)
    }

    suspend fun verifyPin(host: String, port: Int, pin: String) {
        val trimmed = pin.trim()
        require(trimmed.isNotEmpty()) { AppI18n.t("pin_required_error") }
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/auth/verify-pin",
                host = host,
                port = port,
                params = mapOf("pin" to trimmed)
            ),
            body = "",
            contentType = "text/plain",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            error(AppI18n.t("incorrect_pin"))
        }
        requireSuccess(response, "PIN check failed (${response.statusCode})")
        rememberSessionPin(host, port, trimmed)
    }

    suspend fun pingHealth(
        host: String,
        port: Int,
        timeoutMs: Long = HEALTH_PROBE_TIMEOUT_MS
    ): Boolean {
        if (!PeerLanHttpPolicy.canRoute(host)) return false
        val health = peerHttpGet(host, port, withSenderQuery("/api/v1/health"), timeoutMs)
        if (health != null && health.statusCode in 200..299) {
            return true
        }
        val heartbeat = peerHttpGet(host, port, withSenderQuery("/api/v1/heartbeat"), timeoutMs)
        return heartbeat != null && heartbeat.statusCode in 200..299
    }

    suspend fun postPairingRespond(
        host: String,
        port: Int,
        scannerDevice: PairedDeviceEntity,
        pin: String? = null,
        pairingCode: String? = null
    ) {
        val params = buildMap {
            if (!pin.isNullOrBlank()) {
                put("pin", pin.trim())
            }
            val code = pairingCode?.filter { it.isDigit() }.orEmpty()
            if (code.length == 6) {
                put("code", code)
            }
        }
        val payload = json.encodeToString(PairedDeviceEntity.serializer(), scannerDevice)
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/pairing/respond",
                host = host,
                port = port,
                params = params
            ),
            body = payload,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            val body = response.body.lowercase()
            if (body.contains("pairing_code")) {
                error(AppI18n.t("pairing_code_expired"))
            }
            error(AppI18n.t("incorrect_pin_pairing"))
        }
        requireSuccess(response, AppI18n.t("pairing_handshake_failed", response.statusCode.toString()))
    }

    suspend fun postRemoteRename(
        host: String,
        port: Int,
        newName: String
    ) {
        val payload = json.encodeToString(
            RenameDeviceRequest.serializer(),
            RenameDeviceRequest(deviceName = newName.trim())
        )
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/identity/rename",
            body = payload,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Remote rename failed (${response.statusCode})")
    }

    suspend fun postNote(
        host: String,
        port: Int,
        note: com.fileapex.data.note.NoteRecord
    ) {
        val payload = json.encodeToString(com.fileapex.data.note.NoteRecord.serializer(), note)
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/notes/send",
            body = payload,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Note dispatch failed (${response.statusCode})")
    }

    suspend fun uploadNoteAttachment(
        host: String,
        port: Int,
        noteId: String,
        fileName: String,
        localSourcePath: String
    ) {
        val source = Path(localSourcePath)
        check(SystemFileSystem.exists(source)) { "Local source missing: $localSourcePath" }
        val contentLength = SystemFileSystem.metadataOrNull(source)?.size?.takeIf { it > 0L } ?: 0L
        PeerLanHttpPolicy.ensureRoute(host)
        val response = peerHttpUploadFromFile(
            host = host,
            port = port,
            pathWithQuery = withSenderQuery(
                queryPath(
                    basePath = "/api/v1/notes/attachment",
                    host = host,
                    port = port,
                    params = mapOf("noteId" to noteId, "fileName" to fileName)
                )
            ),
            contentType = "application/octet-stream",
            sourcePath = localSourcePath,
            offset = 0L,
            length = contentLength,
            connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
            uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS
        ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (response.statusCode == 403) {
            error("PIN required — open the device and enter its PIN")
        }
        require(response.statusCode in 200..299) {
            "Note attachment upload failed (${response.statusCode})"
        }
    }

    suspend fun postNoteDelete(
        host: String,
        port: Int,
        noteId: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentName: String? = null
    ) {
        val drive = driveFileId?.takeIf { it.isNotBlank() }?.replace("\"", "")
        val hash = checksum?.takeIf { it.isNotBlank() }?.replace("\"", "")
        val name = attachmentName?.takeIf { it.isNotBlank() }?.replace("\"", "")
        val payload = buildString {
            append("""{"noteId":"$noteId","action":"RETRACT_MESSAGE"""")
            if (!drive.isNullOrBlank()) append(""","driveFileId":"$drive"""")
            if (!hash.isNullOrBlank()) append(""","checksum":"$hash"""")
            if (!name.isNullOrBlank()) append(""","attachmentName":"$name"""")
            append("}")
        }
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/notes/delete",
            body = payload,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Note delete failed (${response.statusCode})")
    }

    suspend fun postBulletinSyncBatch(
        host: String,
        port: Int,
        batch: com.fileapex.data.bulletin.BulletinSyncBatch
    ): com.fileapex.data.bulletin.BulletinSyncAck {
        val payload = json.encodeToString(com.fileapex.data.bulletin.BulletinSyncBatch.serializer(), batch)
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/bulletin/sync/batch",
            body = payload,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Bulletin sync batch failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.data.bulletin.BulletinSyncAck.serializer(), response.body)
    }

    suspend fun downloadBulletinFile(
        host: String,
        port: Int,
        messageId: String,
        fileName: String,
        expectedSha256: String,
        expectedSizeBytes: Long
    ): String {
        val dest = com.fileapex.platform.UniqueFileNames.resolveInDirectory(
            com.fileapex.platform.defaultDownloadsDir(),
            fileName
        )
        val target = Path(dest)
        target.parent?.let { parent ->
            if (!SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
        }
        var bytesWritten = 0L
        SystemFileSystem.sink(target).buffered().use { sink ->
            PeerLanHttpPolicy.ensureRoute(host)
            val result = peerHttpGetStreaming(
                host = host,
                port = port,
                pathWithQuery = withSenderQuery(
                    queryPath(
                        basePath = "/api/v1/bulletin/file",
                        host = host,
                        port = port,
                        params = mapOf("messageId" to messageId, "fileName" to fileName)
                    )
                ),
                connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
                readIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
                onChunk = { buffer, length ->
                    sink.write(buffer, startIndex = 0, endIndex = length)
                    bytesWritten += length.toLong()
                }
            ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
            if (result.statusCode == 403) {
                error("PIN required — open the device and enter its PIN")
            }
            require(result.statusCode in 200..299) {
                "Bulletin file pull failed (${result.statusCode})"
            }
        }
        if (expectedSizeBytes > 0L && bytesWritten != expectedSizeBytes) {
            runCatching {
                if (SystemFileSystem.exists(target)) SystemFileSystem.delete(target)
            }
            error("Bulletin file size mismatch (expected $expectedSizeBytes, got $bytesWritten)")
        }
        val hash = com.fileapex.util.sha256HexFile(dest)
        if (expectedSha256.isNotBlank() && !hash.equals(expectedSha256, ignoreCase = true)) {
            runCatching {
                if (SystemFileSystem.exists(target)) SystemFileSystem.delete(target)
            }
            error("Bulletin file checksum mismatch")
        }
        return dest
    }

    suspend fun postClusterSync(
        host: String,
        port: Int,
        request: ClusterSyncRequest
    ) {
        val payload = json.encodeToString(ClusterSyncRequest.serializer(), request)
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/devices/merge",
            body = payload,
            contentType = "application/json",
            timeoutMs = CLUSTER_SYNC_TIMEOUT_MS
        )
        requireSuccess(response, "Cluster sync failed (${response.statusCode})")
    }

    suspend fun listPairedDevices(host: String, port: Int): List<PairedDeviceEntity> {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/devices",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Device list failed (${response.statusCode})")
        return json.decodeFromString(ListSerializer(PairedDeviceEntity.serializer()), response.body)
    }

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
                error("File is too large to preview (>${maxBytes / (1024 * 1024)} MB)")
            }
            sink.write(buffer, startIndex = 0, endIndex = length)
        }
        return sink.readByteArray()
    }

    suspend fun downloadToLocal(
        host: String,
        port: Int,
        remotePath: String,
        localTargetPath: String,
        expectedSizeBytes: Long? = null
    ) {
        val partPath = SocketFileStreamer.partPathFor(localTargetPath)
        var lastError: Throwable? = null
        repeat(TransferResumeProtocol.MAX_ATTEMPTS) { attempt ->
            val requestedOffset = SocketFileStreamer.fileLength(partPath)
            if (expectedSizeBytes != null && expectedSizeBytes > 0L && requestedOffset >= expectedSizeBytes) {
                SocketFileStreamer.finalizePart(partPath, localTargetPath)
                return
            }
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
                }
                if (expectedSizeBytes != null && expectedSizeBytes > 0L && bytesWritten != expectedSizeBytes) {
                    error(
                        "Download incomplete for ${Path(localTargetPath).name} " +
                            "(got $bytesWritten bytes, expected $expectedSizeBytes)"
                    )
                }
                runCatching { appender?.close() }
                appender = null
                SocketFileStreamer.finalizePart(partPath, localTargetPath)
                return
            } catch (error: Throwable) {
                lastError = error
                runCatching { appender?.close() }
                appender = null
                if (attempt == TransferResumeProtocol.MAX_ATTEMPTS - 1) {
                    throw error
                }
                delay(TransferResumeProtocol.RETRY_DELAY_MS)
            } finally {
                runCatching { appender?.close() }
            }
        }
        throw lastError ?: error("Download failed")
    }

    suspend fun streamRemoteFile(
        host: String,
        port: Int,
        remotePath: String,
        offset: Long = 0L,
        onStatus: ((Int) -> Unit)? = null,
        onChunk: suspend (ByteArray, Int) -> Unit
    ) {
        PeerLanHttpPolicy.ensureRoute(host)
        val params = buildMap {
            put("path", remotePath)
            if (offset > 0L) {
                put(TransferResumeProtocol.OFFSET_QUERY, offset.toString())
            }
        }
        val result = peerHttpGetStreaming(
            host = host,
            port = port,
            pathWithQuery = withSenderQuery(
                queryPath(
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
        ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (result.statusCode == 403) {
            error("PIN required — open the device and enter its PIN")
        }
        require(result.statusCode in 200..299) {
            "Stream failed (${result.statusCode})"
        }
    }

    suspend fun queryUploadResumeOffset(
        host: String,
        port: Int,
        remoteTargetPath: String,
        expectedSizeBytes: Long
    ): Long {
        val response = runCatching {
            boundGet(
                host = host,
                port = port,
                pathWithQuery = queryPath(
                    basePath = "/api/v1/files/resume",
                    host = host,
                    port = port,
                    params = buildMap {
                        put("targetPath", remoteTargetPath)
                        if (expectedSizeBytes > 0L) {
                            put(TransferResumeProtocol.EXPECTED_SIZE_QUERY, expectedSizeBytes.toString())
                        }
                    }
                ),
                timeoutMs = PEER_REQUEST_TIMEOUT_MS
            )
        }.getOrNull() ?: return 0L
        if (response.statusCode !in 200..299) return 0L
        return runCatching {
            json.decodeFromString(ResumeOffsetResponse.serializer(), response.body).offset
        }.getOrDefault(0L).coerceAtLeast(0L)
    }

    suspend fun uploadFromLocal(
        host: String,
        port: Int,
        localSourcePath: String,
        remoteTargetPath: String
    ) {
        val source = Path(localSourcePath)
        check(SystemFileSystem.exists(source)) { "Local source missing: $localSourcePath" }
        val totalSize = SystemFileSystem.metadataOrNull(source)?.size?.coerceAtLeast(0L) ?: 0L
        var lastError: Throwable? = null
        repeat(TransferResumeProtocol.MAX_ATTEMPTS) { attempt ->
            val offset = queryUploadResumeOffset(host, port, remoteTargetPath, totalSize)
                .coerceAtMost(totalSize)
            if (offset >= totalSize && totalSize > 0L) {
                return
            }
            val remaining = (totalSize - offset).coerceAtLeast(0L)
            try {
                PeerLanHttpPolicy.ensureRoute(host)
                val response = peerHttpUploadFromFile(
                    host = host,
                    port = port,
                    pathWithQuery = withSenderQuery(
                        uploadPathWithQuery(host, port, remoteTargetPath, offset, totalSize)
                    ),
                    contentType = "application/octet-stream",
                    sourcePath = localSourcePath,
                    offset = offset,
                    length = remaining,
                    connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
                    uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS
                ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
                if (response.statusCode == 403) {
                    error("PIN required — open the device and enter its PIN")
                }
                require(response.statusCode in 200..299) {
                    "Upload failed (${response.statusCode})"
                }
                return
            } catch (error: Throwable) {
                lastError = error
                if (attempt == TransferResumeProtocol.MAX_ATTEMPTS - 1) {
                    throw error
                }
                delay(TransferResumeProtocol.RETRY_DELAY_MS)
            }
        }
        throw lastError ?: error("Upload failed")
    }

    suspend fun uploadFromChunkChannel(
        host: String,
        port: Int,
        remoteTargetPath: String,
        chunks: ReceiveChannel<ByteArray>,
        contentLength: Long? = null,
        resumeOffset: Long = 0L,
        totalSize: Long? = null
    ) {
        PeerLanHttpPolicy.ensureRoute(host)
        val remaining = contentLength?.takeIf { it > 0L }
        val response = peerHttpUploadFromChannel(
            host = host,
            port = port,
            pathWithQuery = withSenderQuery(
                uploadPathWithQuery(host, port, remoteTargetPath, resumeOffset, totalSize ?: contentLength)
            ),
            contentType = "application/octet-stream",
            chunks = chunks,
            connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
            uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
            contentLength = remaining
        ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (response.statusCode == 403) {
            error("PIN required — open the device and enter its PIN")
        }
        require(response.statusCode in 200..299) {
            "Upload failed (${response.statusCode})"
        }
    }

    fun close() = Unit

    private suspend fun boundGet(
        host: String,
        port: Int,
        pathWithQuery: String,
        timeoutMs: Long
    ): PeerBoundHttpResponse {
        PeerLanHttpPolicy.ensureRoute(host)
        return peerHttpGet(host, port, withSenderQuery(pathWithQuery), timeoutMs)
            ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
    }

    private suspend fun boundPost(
        host: String,
        port: Int,
        pathWithQuery: String,
        body: String,
        contentType: String,
        timeoutMs: Long
    ): PeerBoundHttpResponse {
        PeerLanHttpPolicy.ensureRoute(host)
        return peerHttpPost(
            host = host,
            port = port,
            path = withSenderQuery(pathWithQuery),
            body = body,
            contentType = contentType,
            timeoutMs = timeoutMs
        ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
    }

    private fun withSenderQuery(pathWithQuery: String): String {
        val id = localDeviceId().trim()
        if (id.isEmpty()) return pathWithQuery
        val part = "from=${id.encodeURLParameter()}"
        return if (pathWithQuery.contains('?')) {
            "$pathWithQuery&$part"
        } else {
            "$pathWithQuery?$part"
        }
    }

    private fun queryPath(
        basePath: String,
        host: String,
        port: Int,
        params: Map<String, String> = emptyMap()
    ): String {
        val queryParts = buildList {
            for ((key, value) in params) {
                add("${key.encodeURLParameter()}=${value.encodeURLParameter()}")
            }
            sessionPin(host, port)?.let { pin ->
                add("pin=${pin.encodeURLParameter()}")
            }
        }
        if (queryParts.isEmpty()) {
            return basePath
        }
        return "$basePath?${queryParts.joinToString("&")}"
    }

    private fun uploadPathWithQuery(
        host: String,
        port: Int,
        remoteTargetPath: String,
        offset: Long = 0L,
        totalSize: Long? = null
    ): String {
        val params = buildMap {
            put("targetPath", remoteTargetPath)
            if (offset > 0L) {
                put(TransferResumeProtocol.OFFSET_QUERY, offset.toString())
            }
            if (totalSize != null && totalSize > 0L) {
                put(TransferResumeProtocol.TOTAL_SIZE_QUERY, totalSize.toString())
            }
        }
        return queryPath(
            basePath = "/api/v1/files/upload",
            host = host,
            port = port,
            params = params
        )
    }

    private fun rejectPinRequired(response: PeerBoundHttpResponse, message: String) {
        if (response.statusCode == 403) {
            error(message)
        }
    }

    private fun requireSuccess(response: PeerBoundHttpResponse, message: String) {
        if (response.statusCode !in 200..299) {
            error(message)
        }
    }

    companion object {
        const val CHUNK_SIZE = SocketFileStreamer.BUFFER_BYTES
        private const val PEER_CONNECT_TIMEOUT_MS = 5_000L
        private const val TRANSFER_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val PEER_REQUEST_TIMEOUT_MS = 15_000L
        private const val HEALTH_PROBE_TIMEOUT_MS = 5_000L
        private const val PEER_STATE_TIMEOUT_MS = 5_000L
        private const val DIAGNOSTICS_TIMEOUT_MS = 15_000L
        private const val CLUSTER_SYNC_TIMEOUT_MS = 15_000L
    }
}

@kotlinx.serialization.Serializable
data class RenameDeviceRequest(
    val deviceName: String
)
