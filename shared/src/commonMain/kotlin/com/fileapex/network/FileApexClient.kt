package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.generateDeviceId
import com.fileapex.util.TimeUtils
import io.ktor.http.encodeURLParameter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
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
    var onRevocationDetected: (suspend (host: String) -> Unit)? = null
    /** This node's membership stamp, sent as `mv` for the peer's revocation gate. */
    var membershipVersionProvider: (() -> Long)? = null

    /** In-memory PINs for peers that require PIN this session (host:port → pin). */
    private val sessionPinsLock = Any()
    private val sessionPins = mutableMapOf<String, String>()

    private val capabilityCache = ConcurrentHashMap<String, Pair<TransferCapabilities, Long>>()

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
            timeoutMs = LIST_REQUEST_TIMEOUT_MS
        )
        rejectPinRequired(response, com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
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
        rejectPinRequired(response, com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
        requireSuccess(response, "Device details failed (${response.statusCode})")
        return json.decodeFromString(PeerDeviceDiagnostics.serializer(), response.body)
    }

    suspend fun fetchFastBattery(host: String, port: Int): BatteryDiagnostics {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/battery",
                host = host,
                port = port
            ),
            timeoutMs = BATTERY_CHECK_TIMEOUT_MS
        )
        if (response.statusCode == io.ktor.http.HttpStatusCode.NotFound.value) {
            return fetchDeviceDiagnostics(host, port).battery
        }
        rejectPinRequired(response, com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
        requireSuccess(response, "Battery check failed (${response.statusCode})")
        return json.decodeFromString(BatteryDiagnostics.serializer(), response.body)
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

    suspend fun getClipboardStatus(
        host: String,
        port: Int
    ): com.fileapex.domain.clipboard.ClipboardStatusResponse {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/clipboard/status",
                host = host,
                port = port
            ),
            timeoutMs = 3_000
        )
        requireSuccess(response, "Clipboard status check failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.domain.clipboard.ClipboardStatusResponse.serializer(), response.body)
    }

    suspend fun requestClipboardOptIn(
        host: String,
        port: Int,
        senderDeviceId: String,
        senderDeviceName: String,
        pendingPayload: com.fileapex.domain.clipboard.ClipboardSendRequest? = null
    ) {
        val request = com.fileapex.domain.clipboard.ClipboardOptInRequest(
            senderDeviceId = senderDeviceId,
            senderDeviceName = senderDeviceName,
            pendingPayload = pendingPayload
        )
        val bodyStr = json.encodeToString(com.fileapex.domain.clipboard.ClipboardOptInRequest.serializer(), request)
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/clipboard/opt-in-request",
                host = host,
                port = port
            ),
            body = bodyStr,
            contentType = "application/json",
            timeoutMs = 3_000
        )
        requireSuccess(response, "Clipboard opt-in request failed (${response.statusCode})")
    }

    suspend fun triggerDeviceBeep(host: String, port: Int): Boolean {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/device/beep",
                host = host,
                port = port
            ),
            body = "{}",
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode in 200..299
    }

    suspend fun sendDirectAlert(host: String, port: Int, title: String, text: String): Boolean {
        val bodyStr = """{"title":${json.encodeToString(title)},"text":${json.encodeToString(text)}}"""
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/device/alert",
                host = host,
                port = port
            ),
            body = bodyStr,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode in 200..299
    }

    suspend fun pullRemoteClipboard(host: String, port: Int): String? {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/clipboard/current",
                host = host,
                port = port
            ),
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            if (response.body.contains("clipboard_disabled")) {
                error(AppI18n.t("clipboard_disabled_on_peer"))
            } else {
                error(AppI18n.t("pin_required_open_device"))
            }
        }
        requireSuccess(response, "Clipboard pull failed (${response.statusCode})")
        val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull()
        return (root as? kotlinx.serialization.json.JsonObject)?.get("content")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
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
        val health = peerHttpGet(host, port, withSenderQuery("/api/v1/health"), timeoutMs) ?: return false
        checkRevocation(host, health)
        if (health.statusCode in 200..299) return true
        if (health.statusCode != 404) return false
        val heartbeat = peerHttpGet(host, port, withSenderQuery("/api/v1/heartbeat"), timeoutMs)
        if (heartbeat != null) {
            checkRevocation(host, heartbeat)
            if (heartbeat.statusCode in 200..299) return true
        }
        return false
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
        newName: String,
        renamedByDeviceId: String = "",
        renamedByDeviceName: String = ""
    ) {
        val payload = json.encodeToString(
            RenameDeviceRequest.serializer(),
            RenameDeviceRequest(
                deviceName = newName.trim(),
                renamedByDeviceId = renamedByDeviceId.trim(),
                renamedByDeviceName = renamedByDeviceName.trim()
            )
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
        ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (response.statusCode == 403) {
            error(com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
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
            ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
            if (result.statusCode == 403) {
                error(com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
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
    ): List<PairedDeviceEntity> {
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
        return runCatching {
            json.decodeFromString(ListSerializer(PairedDeviceEntity.serializer()), response.body)
        }.getOrDefault(emptyList())
    }

    suspend fun postClusterRemove(
        host: String,
        port: Int,
        record: com.fileapex.domain.pairing.RemovedDeviceRecord
    ): Boolean {
        val payload = json.encodeToString(com.fileapex.domain.pairing.RemovedDeviceRecord.serializer(), record)
        val response = runCatching {
            boundPost(
                host = host,
                port = port,
                pathWithQuery = "/api/v1/cluster/remove",
                body = payload,
                contentType = "application/json",
                timeoutMs = CLUSTER_SYNC_TIMEOUT_MS
            )
        }.getOrNull()
        if (response != null && response.statusCode in 200..299) return true
        val fallback = runCatching {
            boundPost(
                host = host,
                port = port,
                pathWithQuery = "/cluster/remove",
                body = payload,
                contentType = "application/json",
                timeoutMs = CLUSTER_SYNC_TIMEOUT_MS
            )
        }.getOrNull()
        return fallback != null && fallback.statusCode in 200..299
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
        if (segments > 1 && transferCapabilities(host, port).rangedStream) {
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
                TransferRuntime.streamBudget.withPermit {
                    downloadSingleStreamAttempt(
                        host, port, remotePath, localTargetPath, partPath, requestedOffset, expected, onProgress
                    )
                }
            }.exceptionOrNull() ?: return
            if (failure is PeerUnreachableException) throw failure
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
                TransferRuntime.streamBudget.withPermit {
                    streamRangeToPart(host, port, remotePath, partPath, totalSize, from, segment.endExclusive, isLast, onBytes)
                }
            }.exceptionOrNull() ?: return
            if (failure is PeerUnreachableException || failure is SourceSizeChangedException) throw failure
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

    suspend fun transferCapabilities(host: String, port: Int): TransferCapabilities {
        val key = endpointKey(host, port)
        val now = TimeUtils.now()
        capabilityCache[key]?.let { (cached, at) ->
            if (now - at < CAPABILITY_CACHE_MS) return cached
        }
        val response = boundGet(host, port, "/api/v1/files/capabilities", HEALTH_PROBE_TIMEOUT_MS)
        val capabilities = if (response.statusCode in 200..299) {
            runCatching {
                json.decodeFromString(TransferCapabilities.serializer(), response.body)
            }.getOrDefault(TransferCapabilities())
        } else {
            TransferCapabilities()
        }
        capabilityCache[key] = capabilities to now
        return capabilities
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
        ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        if (result.statusCode == 403) {
            error(com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
        }
        require(result.statusCode in 200..299) {
            "Stream failed (${result.statusCode})"
        }
    }

    suspend fun queryUploadResumeOffset(
        host: String,
        port: Int,
        remoteTargetPath: String,
        expectedSizeBytes: Long = 0L,
        transactionId: String = ""
    ): Long {
        return runCatching {
            PeerLanHttpPolicy.ensureRoute(host)
            val response = boundGet(
                host = host,
                port = port,
                pathWithQuery = withSenderQuery(
                    queryPath(
                        basePath = "/api/v1/files/resume",
                        host = host,
                        port = port,
                        params = buildMap {
                            put("targetPath", remoteTargetPath)
                            if (expectedSizeBytes > 0L) {
                                put(TransferResumeProtocol.EXPECTED_SIZE_QUERY, expectedSizeBytes.toString())
                            }
                            if (transactionId.isNotBlank()) {
                                put(TransferResumeProtocol.TRANSACTION_ID_QUERY, transactionId)
                            }
                        }
                    )
                ),
                timeoutMs = PEER_REQUEST_TIMEOUT_MS
            )
            response
        }.getOrNull()?.let { response ->
            if (response.statusCode !in 200..299) return@let 0L
            runCatching {
                json.decodeFromString(ResumeOffsetResponse.serializer(), response.body).offset
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
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val source = Path(localSourcePath)
        check(SystemFileSystem.exists(source)) { "Local source missing: $localSourcePath" }
        val totalSize = SystemFileSystem.metadataOrNull(source)?.size?.coerceAtLeast(0L) ?: 0L
        val txTimestamp = transactionTimestampEpochMs ?: TimeUtils.now()
        val segments = TransferRuntime.segmentsFor(totalSize)
        if (segments > 1 && transferCapabilities(host, port).segmentedUpload) {
            uploadSegmented(
                host = host,
                port = port,
                remoteTargetPath = remoteTargetPath,
                totalSize = totalSize,
                segments = segments,
                transactionId = transactionId?.takeIf { it.isNotBlank() } ?: generateDeviceId(),
                transactionTimestamp = txTimestamp,
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
            val offset = (if (attempt == 0 && knownResumeOffset != null) knownResumeOffset else queryUploadResumeOffset(host, port, remoteTargetPath, totalSize, txId))
                .coerceAtMost(totalSize)
            if (offset >= totalSize && totalSize > 0L) {
                onProgress?.invoke(totalSize, totalSize)
                return
            }
            val remaining = (totalSize - offset).coerceAtLeast(0L)
            val failure = transferCatching {
                TransferRuntime.streamBudget.withPermit {
                    PeerLanHttpPolicy.ensureRoute(host)
                    val response = peerHttpUploadFromFile(
                        host = host,
                        port = port,
                        pathWithQuery = withSenderQuery(
                            uploadPathWithQuery(
                                host = host,
                                port = port,
                                remoteTargetPath = remoteTargetPath,
                                offset = offset,
                                totalSize = totalSize,
                                transactionId = txId,
                                transactionTimestamp = txTimestamp
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
            if (failure is PeerUnreachableException) throw failure
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
            transferCapabilities(host, port).segmentedUpload &&
            transferCapabilities(sourceHost, sourcePort).rangedStream
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
                streamRemoteFile(sourceHost, sourcePort, sourcePath, offset = start, length = length) { buffer, read ->
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
                TransferRuntime.streamBudget.withPermit {
                    coroutineScope {
                        val chunks = Channel<ByteArray>(capacity = RELAY_CHANNEL_CAPACITY)
                        val producer = launch(TransferRuntime.outbound) {
                            var sent = offset
                            try {
                                streamRemoteFile(sourceHost, sourcePort, sourcePath, offset = offset) { buffer, read ->
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
            if (failure is PeerUnreachableException) throw failure
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
            val sent = AtomicLong(TransferRanges.coveredBytes(state.ranges).coerceAtMost(totalSize))
            onProgress?.invoke(sent.get(), totalSize)
            coroutineScope {
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
                            onProgress?.invoke(sent.addAndGet(delta).coerceIn(0L, totalSize), totalSize)
                        }
                    }
                }.awaitAll()
            }
            val response = boundPost(
                host = host,
                port = port,
                pathWithQuery = segmentPath(
                    basePath = "/api/v1/files/upload-complete",
                    host = host,
                    port = port,
                    remoteTargetPath = remoteTargetPath,
                    totalSize = totalSize,
                    transactionId = transactionId,
                    extra = mapOf(TransferResumeProtocol.TIMESTAMP_QUERY to transactionTimestamp.toString())
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
                TransferRuntime.streamBudget.withPermit {
                    var reported = 0L
                    sendRange(from, segment.endExclusive, withSenderQuery(pathWithQuery)) { sentInRange ->
                        onDelta(sentInRange - reported)
                        reported = sentInRange
                    } ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
                }
            }
            val failure = result.exceptionOrNull()
            if (failure is PeerUnreachableException || failure is SourceSizeChangedException) throw failure
            val response = result.getOrNull()
            when {
                response == null -> lastError = failure
                response.statusCode in 200..299 -> return
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
        val response = boundGet(
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
        requireSuccess(response, "Segment state failed (${response.statusCode})")
        return json.decodeFromString(SegmentStateResponse.serializer(), response.body)
    }

    private fun segmentPath(
        basePath: String,
        host: String,
        port: Int,
        remoteTargetPath: String,
        totalSize: Long,
        transactionId: String,
        extra: Map<String, String>
    ): String = queryPath(
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
            pathWithQuery = withSenderQuery(
                uploadPathWithQuery(
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
        require(response.statusCode in 200..299) {
            "${AppI18n.t("upload_failed")} (${response.statusCode})"
        }
    }

    suspend fun createDirectory(
        host: String,
        port: Int,
        remotePath: String
    ) {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/files/mkdir",
                host = host,
                port = port,
                params = mapOf("targetPath" to remotePath)
            ),
            body = "",
            contentType = "text/plain",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            error(AppI18n.t("pin_required_open_device"))
        }
        if (response.statusCode == 404) {
            return
        }
        requireSuccess(response, "Create directory failed (${response.statusCode}): $remotePath")
    }

    private suspend fun checkRevocation(host: String, response: PeerBoundHttpResponse) {
        if (response.statusCode == 401 && response.body.contains("revoked", ignoreCase = true)) {
            try {
                onRevocationDetected?.invoke(host)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                println("FileApexClient: revocation handler failed - ${error.message}")
            }
        }
    }

    private suspend fun boundGet(
        host: String,
        port: Int,
        pathWithQuery: String,
        timeoutMs: Long
    ): PeerBoundHttpResponse {
        PeerLanHttpPolicy.ensureRoute(host)
        val response = peerHttpGet(host, port, withSenderQuery(pathWithQuery), timeoutMs)
            ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        checkRevocation(host, response)
        return response
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
        val response = peerHttpPost(
            host = host,
            port = port,
            path = withSenderQuery(pathWithQuery),
            body = body,
            contentType = contentType,
            timeoutMs = timeoutMs
        ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        checkRevocation(host, response)
        return response
    }

    private fun withSenderQuery(pathWithQuery: String): String {
        val id = localDeviceId().trim()
        if (id.isEmpty()) return pathWithQuery
        val part = buildString {
            append("from=").append(id.encodeURLParameter())
            val mv = membershipVersionProvider?.invoke() ?: 0L
            if (mv > 0L) append("&mv=").append(mv)
        }
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
        totalSize: Long? = null,
        transactionId: String = "",
        transactionTimestamp: Long = 0L
    ): String {
        val params = buildMap {
            put("targetPath", remoteTargetPath)
            if (offset > 0L) {
                put(TransferResumeProtocol.OFFSET_QUERY, offset.toString())
            }
            if (totalSize != null && totalSize > 0L) {
                put(TransferResumeProtocol.TOTAL_SIZE_QUERY, totalSize.toString())
            }
            if (transactionId.isNotBlank()) {
                put(TransferResumeProtocol.TRANSACTION_ID_QUERY, transactionId)
            }
            if (transactionTimestamp > 0L) {
                put(TransferResumeProtocol.TIMESTAMP_QUERY, transactionTimestamp.toString())
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
            if (response.body.contains("pin_required", ignoreCase = true)) {
                error("pin_required: $message")
            } else {
                error("Access denied: ${response.body.ifBlank { "Forbidden" }}")
            }
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
        private const val LIST_REQUEST_TIMEOUT_MS = 4_000L
        private const val HEALTH_PROBE_TIMEOUT_MS = 5_000L
        private const val PEER_STATE_TIMEOUT_MS = 5_000L
        private const val BATTERY_CHECK_TIMEOUT_MS = 20_000L
        private const val DIAGNOSTICS_TIMEOUT_MS = 25_000L
        private const val CLUSTER_SYNC_TIMEOUT_MS = 15_000L
        private const val CAPABILITY_CACHE_MS = 10 * 60 * 1000L
        private const val RELAY_CHANNEL_CAPACITY = 2
        private const val SEGMENT_COMPLETE_ROUNDS = 2
    }
}

@kotlinx.serialization.Serializable
data class RenameDeviceRequest(
    val deviceName: String,
    val renamedByDeviceId: String = "",
    val renamedByDeviceName: String = ""
)
