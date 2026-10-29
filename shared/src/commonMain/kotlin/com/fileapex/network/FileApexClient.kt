package com.fileapex.network

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.i18n.AppI18n
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
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
    internal val localDeviceId: () -> String = { loadLocalIdentity().deviceId }
) {
    var onRevocationDetected: (suspend (host: String, port: Int, revokedAt: Long) -> Unit)? = null
    /** This node's membership stamp, sent as `mv` for the peer's revocation gate. */
    var membershipVersionProvider: (() -> Long)? = null

    /** In-memory PINs for peers that require PIN this session (host:port → pin). */
    private val sessionPinsLock = Any()
    private val sessionPins = mutableMapOf<String, String>()

    val transferCapabilities: TransferCapabilityCache = TransferCapabilityCache(this)
    val downloads: PeerDownloadClient = PeerDownloadClient(this, transferCapabilities)
    val uploads: PeerUploadClient = PeerUploadClient(this, transferCapabilities, downloads)
    val bulletin = PeerBulletinClient(this)
    val alerts = PeerAlertClient(this)
    val cluster = PeerClusterClient(this)
    val notifications = PeerNotificationClient(this)
    val clipboard = PeerClipboardClient(this)

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
        val removals: String? = null,
        val clusterDigest: String? = null
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
        checkRevocation(host, port, health)
        if (health.statusCode in 200..299) return HealthProbe(
            true, health.body.respondedAsId(), PeerHttpHeaders.membershipVersion(health.headers),
            PeerHttpHeaders.bulletinVersion(health.headers), PeerHttpHeaders.removals(health.headers),
            PeerHttpHeaders.clusterDigest(health.headers)
        )
        return HealthProbe(false)
    }

    private fun String.respondedAsId(): String? =
        trim().takeIf { it.startsWith("ok:") }?.removePrefix("ok:")?.trim()?.takeIf { it.isNotEmpty() }

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

    private suspend fun checkRevocation(host: String, port: Int, response: PeerBoundHttpResponse) {
        if (response.statusCode == 401 && response.body.contains("revoked", ignoreCase = true)) {
            try {
                onRevocationDetected?.invoke(host, port, response.headers["x-fileapex-revoked-at"]?.trim()?.toLongOrNull() ?: 0L)
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
        checkRevocation(host, port, response)
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
        checkRevocation(host, port, response)
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
        internal const val CLUSTER_SYNC_TIMEOUT_MS = 15_000L
    }
}

@kotlinx.serialization.Serializable
data class RenameDeviceRequest(
    val deviceName: String,
    val renamedByDeviceId: String = "",
    val renamedByDeviceName: String = ""
)
