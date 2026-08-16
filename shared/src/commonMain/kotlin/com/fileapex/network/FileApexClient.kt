package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.peer.PeerNodeState
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
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
        text: String
    ): com.fileapex.domain.clipboard.ClipboardSendResponse {
        val request = com.fileapex.domain.clipboard.ClipboardSendRequest(
            senderDeviceId = senderDeviceId,
            senderDeviceName = senderDeviceName,
            text = text
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
                "Clipboard sharing is disabled on destination device"
            } else if (response.body.contains("pin_required")) {
                "PIN required — open the device and enter its PIN"
            } else {
                response.body.ifBlank { "Clipboard sharing is disabled on destination device" }
            }
            error(message)
        }
        requireSuccess(response, "Clipboard transfer failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.domain.clipboard.ClipboardSendResponse.serializer(), response.body)
    }

    suspend fun verifyPin(host: String, port: Int, pin: String) {
        val trimmed = pin.trim()
        require(trimmed.isNotEmpty()) { "PIN is required" }
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
            error("Incorrect PIN")
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
        pin: String? = null
    ) {
        val params = buildMap {
            if (!pin.isNullOrBlank()) {
                put("pin", pin.trim())
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
            error("Incorrect PIN — pairing rejected")
        }
        requireSuccess(response, "Pairing handshake failed (${response.statusCode})")
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
        val contentLength = SystemFileSystem.metadataOrNull(source)?.size?.takeIf { it > 0L }
        val channel = Channel<ByteArray>(UPLOAD_CHANNEL_CAPACITY)
        coroutineScope {
            val producer = launch(Dispatchers.IO) {
                try {
                    SystemFileSystem.source(source).buffered().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        while (!input.exhausted()) {
                            val read = input.readAtMostTo(buffer)
                            if (read > 0) {
                                channel.send(buffer.copyOf(read))
                            }
                        }
                    }
                } finally {
                    channel.close()
                }
            }
            PeerLanHttpPolicy.ensureRoute(host)
            val response = peerHttpUploadFromChannel(
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
                chunks = channel,
                connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
                uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
                contentLength = contentLength?.takeIf { it > 0L }
            ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
            producer.join()
            if (response.statusCode == 403) {
                error("PIN required — open the device and enter its PIN")
            }
            require(response.statusCode in 200..299) {
                "Note attachment upload failed (${response.statusCode})"
            }
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
        streamRemoteFile(host, port, remotePath) { chunk ->
            total += chunk.size
            if (total > maxBytes) {
                error("File is too large to preview (>${maxBytes / (1024 * 1024)} MB)")
            }
            sink.write(chunk)
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
        val target = Path(localTargetPath)
        target.parent?.let { parent ->
            if (!SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
        }
        var bytesWritten = 0L
        SystemFileSystem.sink(target).buffered().use { sink ->
            streamRemoteFile(host, port, remotePath) { chunk ->
                sink.write(chunk)
                bytesWritten += chunk.size
            }
        }
        if (expectedSizeBytes != null && expectedSizeBytes > 0L && bytesWritten != expectedSizeBytes) {
            runCatching {
                if (SystemFileSystem.exists(target)) {
                    SystemFileSystem.delete(target)
                }
            }
            error(
                "Download incomplete for ${target.name} " +
                    "(got $bytesWritten bytes, expected $expectedSizeBytes)"
            )
        }
    }

    suspend fun streamRemoteFile(
        host: String,
        port: Int,
        remotePath: String,
        onChunk: suspend (ByteArray) -> Unit
    ) {
        PeerLanHttpPolicy.ensureRoute(host)
        val result = peerHttpGetStreaming(
            host = host,
            port = port,
            pathWithQuery = withSenderQuery(
                queryPath(
                    basePath = "/api/v1/files/stream",
                    host = host,
                    port = port,
                    params = mapOf("path" to remotePath)
                )
            ),
            connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
            readIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
            onChunk = { chunk -> onChunk(chunk) }
        ) ?: error(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (result.statusCode == 403) {
            error("PIN required — open the device and enter its PIN")
        }
        require(result.statusCode in 200..299) {
            "Stream failed (${result.statusCode})"
        }
    }

    suspend fun uploadFromLocal(
        host: String,
        port: Int,
        localSourcePath: String,
        remoteTargetPath: String
    ) {
        val source = Path(localSourcePath)
        check(SystemFileSystem.exists(source)) { "Local source missing: $localSourcePath" }
        val contentLength = SystemFileSystem.metadataOrNull(source)?.size?.takeIf { it > 0L }
        val channel = Channel<ByteArray>(UPLOAD_CHANNEL_CAPACITY)
        coroutineScope {
            val producer = launch(Dispatchers.IO) {
                try {
                    SystemFileSystem.source(source).buffered().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        while (!input.exhausted()) {
                            val read = input.readAtMostTo(buffer)
                            if (read > 0) {
                                channel.send(buffer.copyOf(read))
                            }
                        }
                    }
                } finally {
                    channel.close()
                }
            }
            uploadFromChunkChannel(
                host = host,
                port = port,
                remoteTargetPath = remoteTargetPath,
                chunks = channel,
                contentLength = contentLength
            )
            producer.join()
        }
    }

    suspend fun uploadFromChunkChannel(
        host: String,
        port: Int,
        remoteTargetPath: String,
        chunks: ReceiveChannel<ByteArray>,
        contentLength: Long? = null
    ) {
        PeerLanHttpPolicy.ensureRoute(host)
        val response = peerHttpUploadFromChannel(
            host = host,
            port = port,
            pathWithQuery = withSenderQuery(uploadPathWithQuery(host, port, remoteTargetPath)),
            contentType = "application/octet-stream",
            chunks = chunks,
            connectTimeoutMs = PEER_CONNECT_TIMEOUT_MS,
            uploadIdleTimeoutMs = TRANSFER_IDLE_TIMEOUT_MS,
            contentLength = contentLength?.takeIf { it > 0L }
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

    private fun uploadPathWithQuery(host: String, port: Int, remoteTargetPath: String): String =
        queryPath(
            basePath = "/api/v1/files/upload",
            host = host,
            port = port,
            params = mapOf("targetPath" to remoteTargetPath)
        )

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
        const val CHUNK_SIZE = 64 * 1024
        private const val UPLOAD_CHANNEL_CAPACITY = 2
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
