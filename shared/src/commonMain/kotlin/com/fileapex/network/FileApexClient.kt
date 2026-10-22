package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.i18n.AppI18n
import com.fileapex.util.cancellableCatching
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
    internal val json: Json = FileApexHttpClientFactory.defaultJson,
    private val localDeviceId: () -> String = { loadLocalIdentity().deviceId }
) {
    var onRevocationDetected: (suspend (host: String) -> Unit)? = null
    /** This node's membership stamp, sent as `mv` for the peer's revocation gate. */
    var membershipVersionProvider: (() -> Long)? = null

    /** In-memory PINs for peers that require PIN this session (host:port → pin). */
    private val sessionPinsLock = Any()
    private val sessionPins = mutableMapOf<String, String>()

    val transferCapabilities: TransferCapabilityCache = TransferCapabilityCache(this)
    val downloads: PeerDownloadClient = PeerDownloadClient(this, transferCapabilities)
    val uploads: PeerUploadClient = PeerUploadClient(this, transferCapabilities, downloads)

    fun rememberSessionPin(host: String, port: Int, pin: String) {
        val trimmed = pin.trim()
        if (trimmed.isNotEmpty()) {
            synchronized(sessionPinsLock) {
                sessionPins[endpointKey(host, port)] = trimmed
            }
        }
    }

    internal fun endpointKey(host: String, port: Int): String = "$host:$port"

    private fun sessionPin(host: String, port: Int): String? =
        synchronized(sessionPinsLock) {
            sessionPins[endpointKey(host, port)]
        }

    /**
     * Lists a remote directory in pages of [LIST_PAGE_SIZE] so no single response (or its decoded
     * String) holds the whole directory. Peers that predate paging ignore `limit`/`offset` and return
     * everything in the first response, which is detected and returned as-is.
     */
    suspend fun listFiles(host: String, port: Int, path: String): List<RemoteFileItem> {
        val all = ArrayList<RemoteFileItem>()
        var offset = 0
        var previousFirstId: String? = null
        while (true) {
            val response = boundGet(
                host = host,
                port = port,
                pathWithQuery = queryPath(
                    basePath = "/api/v1/files/list",
                    host = host,
                    port = port,
                    params = mapOf(
                        "path" to path,
                        "limit" to LIST_PAGE_SIZE.toString(),
                        "offset" to offset.toString()
                    )
                ),
                timeoutMs = LIST_REQUEST_TIMEOUT_MS
            )
            rejectPinRequired(response, com.fileapex.i18n.AppI18n.t("pin_required_open_device"))
            requireSuccess(response, "List failed (${response.statusCode}): $host:$port$path")
            val page = json.decodeFromString(ListSerializer(RemoteFileItem.serializer()), response.body)
            // Older peer: ignored limit/offset and sent the whole directory (more than one page).
            if (offset == 0 && page.size > LIST_PAGE_SIZE) return page
            // Older peer whose directory is exactly one page: same page would repeat forever.
            if (previousFirstId != null && page.firstOrNull()?.id == previousFirstId) return all
            all += page
            if (page.size < LIST_PAGE_SIZE || all.size >= LIST_MAX_ITEMS) return all
            previousFirstId = page.firstOrNull()?.id
            offset += page.size
        }
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

    /**
     * Sends this device's TLS pin to a peer that has none for us yet and returns the peer's own announcement.
     * Null when the peer answers 204 (no TLS of its own). Throws on any other failure.
     */
    suspend fun announceTlsPin(
        host: String,
        port: Int,
        announcement: com.fileapex.security.tls.TlsPinAnnouncement
    ): com.fileapex.security.tls.TlsPinAnnouncement? {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/tls/pin",
            body = json.encodeToString(com.fileapex.security.tls.TlsPinAnnouncement.serializer(), announcement),
            contentType = "application/json",
            timeoutMs = PEER_STATE_TIMEOUT_MS
        )
        if (response.statusCode == 204) return null
        requireSuccess(response, "TLS pin announcement failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.security.tls.TlsPinAnnouncement.serializer(), response.body)
    }

    /**
     * Asks a paired peer which peers it has recently reached itself. Null when the peer has no such endpoint
     * (older build). Throws on any other failure.
     */
    suspend fun fetchPresenceDigest(
        host: String,
        port: Int,
        message: com.fileapex.domain.presence.PresenceDigestMessage,
        timeoutMs: Long = PEER_STATE_TIMEOUT_MS
    ): com.fileapex.domain.presence.PresenceDigestMessage? {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/presence/digest",
            body = json.encodeToString(com.fileapex.domain.presence.PresenceDigestMessage.serializer(), message),
            contentType = "application/json",
            timeoutMs = timeoutMs
        )
        if (response.statusCode == 404) return null
        requireSuccess(response, "Presence digest failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.domain.presence.PresenceDigestMessage.serializer(), response.body)
    }

    /** Swaps Iroh addresses with a paired peer. Null when the peer has Iroh off or predates it. */
    suspend fun exchangeIrohAddress(
        host: String,
        port: Int,
        message: com.fileapex.iroh.IrohAddressMessage,
        timeoutMs: Long = PEER_STATE_TIMEOUT_MS
    ): com.fileapex.iroh.IrohAddressMessage? {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/iroh/address",
            body = json.encodeToString(com.fileapex.iroh.IrohAddressMessage.serializer(), message),
            contentType = "application/json",
            timeoutMs = timeoutMs
        )
        if (response.statusCode == 404) return null
        requireSuccess(response, "Iroh address exchange failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.iroh.IrohAddressMessage.serializer(), response.body)
    }

    /** [summaryOnly] asks for the quick subset and [groups] for only those parts; peers that predate either send everything. */
    suspend fun fetchDeviceDiagnostics(host: String, port: Int, summaryOnly: Boolean = false, groups: Set<com.fileapex.domain.diagnostics.DiagnosticGroup>? = null): PeerDeviceDiagnostics {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/diagnostics",
                host = host,
                port = port,
                params = com.fileapex.domain.diagnostics.DiagnosticGroups.queryParams(summaryOnly, groups)
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

    suspend fun postNotificationEvent(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.NotificationEventRequest
    ) {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(basePath = "/api/v1/notifications/event", host = host, port = port),
            body = json.encodeToString(com.fileapex.domain.notifications.NotificationEventRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Notification delivery failed (${response.statusCode})")
    }

    suspend fun postNotificationCommand(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.NotificationCommandRequest
    ) {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(basePath = "/api/v1/notifications/command", host = host, port = port),
            body = json.encodeToString(com.fileapex.domain.notifications.NotificationCommandRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Notification command failed (${response.statusCode})")
    }

    suspend fun postPhoneSettingsPrompt(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.PhoneSettingsPromptRequest
    ) {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(basePath = "/api/v1/notifications/prompt", host = host, port = port),
            body = json.encodeToString(com.fileapex.domain.notifications.PhoneSettingsPromptRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Settings prompt failed (${response.statusCode})")
    }

    /** Null when the peer answered but has no notification routes (an older build); throws when it could not be reached. */
    suspend fun getNotificationSyncStatus(
        host: String,
        port: Int
    ): com.fileapex.domain.notifications.NotificationSyncStatus? {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = queryPath(basePath = "/api/v1/notifications/status", host = host, port = port),
            timeoutMs = 3_000
        )
        if (response.statusCode == 404) return null
        requireSuccess(response, "Notification status check failed (${response.statusCode})")
        return json.decodeFromString(
            com.fileapex.domain.notifications.NotificationSyncStatus.serializer(),
            response.body
        )
    }

    suspend fun postNotificationSnapshot(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.NotificationSnapshotRequest
    ): com.fileapex.domain.notifications.NotificationSnapshotResponse {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(basePath = "/api/v1/notifications/snapshot", host = host, port = port),
            body = json.encodeToString(com.fileapex.domain.notifications.NotificationSnapshotRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Notification snapshot failed (${response.statusCode})")
        return json.decodeFromString(
            com.fileapex.domain.notifications.NotificationSnapshotResponse.serializer(),
            response.body
        )
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

    suspend fun triggerDeviceBeep(host: String, port: Int, continuous: Boolean = false): Boolean =
        triggerDeviceBeepStatus(host, port, continuous) in 200..299

    /** HTTP status of the beep request; throws when the peer cannot be reached. */
    suspend fun triggerDeviceBeepStatus(host: String, port: Int, continuous: Boolean = false): Int {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/device/beep",
                host = host,
                port = port,
                params = if (continuous) mapOf("continuous" to "1") else emptyMap()
            ),
            body = "{}",
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode
    }

    suspend fun reportLocateFound(host: String, port: Int): Boolean {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(basePath = "/api/v1/device/beep/found", host = host, port = port),
            body = "{}",
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode in 200..299
    }

    suspend fun stopDeviceBeep(host: String, port: Int): Boolean {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(basePath = "/api/v1/device/beep/stop", host = host, port = port),
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
        val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return null
        val sealed = runCatching {
            json.decodeFromString(com.fileapex.domain.clipboard.ClipboardSendRequest.serializer(), response.body)
        }.getOrNull()
        if (sealed != null && sealed.ciphertext.isNotBlank()) {
            val key = com.fileapex.domain.clipboard.ClipboardShareCoordinator
                .verifiedSenderKey(sealed.senderDeviceId, sealed.senderPublicKey)
            return com.fileapex.domain.clipboard.ClipboardE2ee.decrypt(
                ciphertextBase64 = sealed.ciphertext,
                localDeviceId = localDeviceId().trim(),
                peerDeviceId = sealed.senderDeviceId.trim(),
                peerPublicKeyBase64 = key
            ).decodeToString()
        }
        // Peers older than this change answer with plain text; shown only, never applied to a clipboard.
        return (root["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
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
        timeoutMs: Long = HEALTH_PROBE_TIMEOUT_MS,
        companion: Boolean? = null
    ): Boolean = probeHealth(host, port, timeoutMs, companion).reached

    /** [respondedAs] is the device id the answering node reported; null from builds that do not report one. */
    data class HealthProbe(
        val reached: Boolean,
        val respondedAs: String? = null,
        val membershipVersion: Long? = null,
        val bulletinVersion: Long? = null,
        val removals: String? = null
    )

    suspend fun probeHealth(
        host: String,
        port: Int,
        timeoutMs: Long = HEALTH_PROBE_TIMEOUT_MS,
        companion: Boolean? = null
    ): HealthProbe {
        if (!PeerLanHttpPolicy.canRoute(host)) return HealthProbe(false)
        // The phone applies this only when the poll reached it over TLS from a paired computer.
        val healthPath = if (companion == null) "/api/v1/health" else "/api/v1/health?companion=${if (companion) 1 else 0}"
        val health = peerHttpGet(host, port, withSenderQuery(healthPath), timeoutMs) ?: return HealthProbe(false)
        checkRevocation(host, health)
        if (health.statusCode in 200..299) return HealthProbe(
            true, health.body.respondedAsId(), PeerHttpHeaders.membershipVersion(health.headers),
            PeerHttpHeaders.bulletinVersion(health.headers), PeerHttpHeaders.removals(health.headers)
        )
        return HealthProbe(false)
    }

    private fun String.respondedAsId(): String? =
        trim().takeIf { it.startsWith("ok:") }?.removePrefix("ok:")?.trim()?.takeIf { it.isNotEmpty() }

    /** The removal records a peer holds inside the fingerprint window. */
    suspend fun fetchRemovedDevices(
        host: String,
        port: Int
    ): List<com.fileapex.domain.pairing.RemovedDeviceRecord> {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/devices/removed",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Removed devices fetch failed (${response.statusCode})")
        return json.decodeFromString(
            ListSerializer(com.fileapex.domain.pairing.RemovedDeviceRecord.serializer()),
            response.body
        )
    }

    /** Introductions sealed for [selfDeviceId] by the peer at [host]. Only that peer's pair key opens them. */
    suspend fun fetchIntroductions(
        host: String,
        port: Int,
        selfDeviceId: String
    ): List<com.fileapex.security.SealedIntroduction> {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/cluster/introductions?for=${java.net.URLEncoder.encode(selfDeviceId, "UTF-8")}",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Introductions fetch failed (${response.statusCode})")
        return json.decodeFromString(
            ListSerializer(com.fileapex.security.SealedIntroduction.serializer()),
            response.body
        )
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

    suspend fun fetchBulletinDelta(
        host: String,
        port: Int,
        since: Long
    ): com.fileapex.data.bulletin.BulletinDeltaResponse {
        val response = boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/bulletin/delta?since=$since",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        requireSuccess(response, "Bulletin delta failed (${response.statusCode})")
        return json.decodeFromString(com.fileapex.data.bulletin.BulletinDeltaResponse.serializer(), response.body)
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
        val response = cancellableCatching {
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
        val fallback = cancellableCatching {
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



















    /** Deletes [remotePath] on the peer. The peer's owner must have remote deletion switched on. */
    suspend fun deleteRemote(host: String, port: Int, remotePath: String) {
        val response = boundPost(
            host = host,
            port = port,
            pathWithQuery = queryPath(
                basePath = "/api/v1/files/delete",
                host = host,
                port = port,
                params = mapOf("targetPath" to remotePath)
            ),
            body = "",
            contentType = "text/plain",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        when {
            response.statusCode == 403 -> error(AppI18n.t("pin_required_open_device"))
            // Peers older than this feature have no such route.
            response.statusCode == 404 || response.statusCode == 405 -> error(AppI18n.t("remote_delete_unsupported"))
        }
        requireSuccess(response, "Delete failed (${response.statusCode}): $remotePath")
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

    internal suspend fun boundGet(
        host: String,
        port: Int,
        pathWithQuery: String,
        timeoutMs: Long
    ): PeerBoundHttpResponse {
        PeerLanHttpPolicy.ensureRoute(host)
        val response = peerHttpGet(host, port, withSenderQuery(withCompanion(pathWithQuery, host, port)), timeoutMs)
            ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        checkRevocation(host, response)
        return response
    }

    internal suspend fun boundPost(
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
            path = withSenderQuery(withCompanion(pathWithQuery, host, port)),
            body = body,
            contentType = contentType,
            timeoutMs = timeoutMs
        ) ?: throw PeerUnreachableException(PeerLanHttpPolicy.unreachableMessage(host, port))
        checkRevocation(host, response)
        return response
    }

    /** Every request to the connected phone renews its notification grant. */
    private fun withCompanion(pathWithQuery: String, host: String, port: Int): String {
        val id = com.fileapex.security.tls.PeerTlsRoutes.lookup(host, port)?.deviceId ?: return pathWithQuery
        if (!com.fileapex.domain.notifications.NotificationCompanion.isCompanion(id)) return pathWithQuery
        return pathWithQuery + (if (pathWithQuery.contains('?')) "&" else "?") + "companion=1"
    }

    internal fun withSenderQuery(pathWithQuery: String): String {
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

    internal fun queryPath(
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

    internal fun uploadPathWithQuery(
        host: String,
        port: Int,
        remoteTargetPath: String,
        offset: Long = 0L,
        totalSize: Long? = null,
        transactionId: String = "",
        transactionTimestamp: Long = 0L,
        backup: Boolean = false,
        sha256: String = ""
    ): String {
        val params = buildMap {
            put("targetPath", remoteTargetPath)
            if (backup) put("backup", "1")
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
            if (sha256.isNotBlank()) {
                put(TransferResumeProtocol.SHA256_QUERY, sha256)
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

    internal fun requireSuccess(response: PeerBoundHttpResponse, message: String) {
        if (response.statusCode !in 200..299) {
            error(message)
        }
    }

    companion object {
        const val CHUNK_SIZE = SocketFileStreamer.BUFFER_BYTES
        internal const val PEER_CONNECT_TIMEOUT_MS = 5_000L
        /**
         * A stream that moves no bytes for this long is abandoned and its range resumes from the
         * ledger. The progress sheet starts reporting silence well before this.
         */
        internal const val TRANSFER_IDLE_TIMEOUT_MS = 2 * 60 * 1000L
        internal const val PEER_REQUEST_TIMEOUT_MS = 15_000L
        private const val LIST_REQUEST_TIMEOUT_MS = 4_000L
        private const val LIST_PAGE_SIZE = 2_000
        private const val LIST_MAX_ITEMS = 500_000
        internal const val HEALTH_PROBE_TIMEOUT_MS = 5_000L
        private const val PEER_STATE_TIMEOUT_MS = 5_000L
        private const val BATTERY_CHECK_TIMEOUT_MS = 20_000L
        private const val DIAGNOSTICS_TIMEOUT_MS = 25_000L
        private const val CLUSTER_SYNC_TIMEOUT_MS = 15_000L
    }
}

@kotlinx.serialization.Serializable
data class RenameDeviceRequest(
    val deviceName: String,
    val renamedByDeviceId: String = "",
    val renamedByDeviceName: String = ""
)
