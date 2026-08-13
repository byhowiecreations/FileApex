package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.files.LocalFileRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.domain.clipboard.ClipboardSendRequest
import com.fileapex.domain.clipboard.ClipboardSendResponse
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isWebUrl
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.collectDeviceDiagnostics
import com.fileapex.platform.collectDeviceDiagnosticsFallback
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.platform.notifyFilesReceived
import com.fileapex.util.PathUtils
import com.fileapex.util.TimeUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
import kotlinx.io.write
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent Ktor CIO host. Engine lifecycle is owned by the platform share controller
 * and is intentionally decoupled from individual request / pairing handler completion.
 */
class FileApexServer(
    private val port: Int,
    private val identityProvider: () -> LocalIdentity = { loadLocalIdentity() },
    private val onPairingRespond: suspend (PairedDeviceEntity) -> Unit = {},
    private val onPairingRespondComplete: suspend (PairedDeviceEntity) -> Unit = {},
    private val onClusterMerge: suspend (ClusterSyncRequest) -> Unit = {},
    private val onListDevices: suspend () -> List<PairedDeviceEntity> = { emptyList() },
    private val onLog: (String, Throwable?) -> Unit = { message, error ->
        if (error != null) {
            println("FileApexServer: $message :: ${error.message}")
            error.printStackTrace()
        } else {
            println("FileApexServer: $message")
        }
    }
) {
    private val engineLock = Any()
    private var serverEngine: EmbeddedServer<*, *>? = null
    private var lifecycleJob: Job = SupervisorJob()
    private var serverScope: CoroutineScope = CoroutineScope(Dispatchers.IO + lifecycleJob)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val localFiles = LocalFileRepository()

    val isRunning: Boolean
        get() = synchronized(engineLock) { serverEngine != null }

    fun start() {
        synchronized(engineLock) {
            if (serverEngine != null) {
                onLog("start() ignored — engine already running on port $port", null)
                return
            }
            if (lifecycleJob.isCancelled) {
                lifecycleJob = SupervisorJob()
                serverScope = CoroutineScope(Dispatchers.IO + lifecycleJob)
            }

            val bindHost = LanInterfaceBinding.shareServerListenHost()
            val advertiseIp = LanInterfaceBinding.primaryLanIpv4OrNull()
            onLog(
                "Starting CIO engine on $bindHost:$port" +
                    (advertiseIp?.let { " (LAN $it)" }.orEmpty()),
                null
            )
            serverEngine = embeddedServer(CIO, port = port, host = bindHost) {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    onLog("Unhandled route exception", cause)
                    runCatching {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            cause.message ?: "Internal server error"
                        )
                    }
                }
            }

            routing {
                suspend fun respondSelfPeerState(call: io.ktor.server.application.ApplicationCall) {
                    val identity = identityProvider()
                    val settings = FileApexServices.settings
                    val state = PeerNodeStateMapper.selfState(
                        identity = identity,
                        pinRequired = settings.pinRequiredEnabled.value
                    )
                    call.respondText(
                        text = json.encodeToString(PeerNodeState.serializer(), state),
                        contentType = ContentType.Application.Json
                    )
                }

                get("/api/v1/identity") {
                    runCatching {
                        respondSelfPeerState(call)
                    }.onFailure { error ->
                        onLog("GET /api/v1/identity failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "identity_failed")
                    }
                }

                get("/api/v1/heartbeat") {
                    runCatching {
                        respondSelfPeerState(call)
                    }.onFailure { error ->
                        onLog("GET /api/v1/heartbeat failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "heartbeat_failed")
                    }
                }

                post("/api/v1/identity/rename") {
                    runCatching {
                        val body = call.receiveText()
                        val request = json.decodeFromString(RenameDeviceRequest.serializer(), body)
                        val trimmed = request.deviceName.trim()
                        if (trimmed.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, "empty_name")
                            return@runCatching
                        }
                        withContext(Dispatchers.IO) {
                            LocalDeviceNameStore.apply(trimmed)
                            FileApexServices.pairingCoordinator.broadcastSelfIdentity()
                        }
                        onLog("Local device renamed to $trimmed via cluster request", null)
                        call.respond(HttpStatusCode.OK)
                    }.onFailure { error ->
                        onLog("POST /api/v1/identity/rename failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "rename_failed")
                    }
                }

                post("/api/v1/auth/verify-pin") {
                    runCatching {
                        if (!isPeerPinAccepted(providedPin(call))) {
                            call.respond(HttpStatusCode.Forbidden, "pin_required")
                            return@runCatching
                        }
                        call.respond(HttpStatusCode.OK)
                    }.onFailure { error ->
                        onLog("POST /api/v1/auth/verify-pin failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "verify_pin_failed")
                    }
                }

                post("/api/v1/pairing/respond") {
                    runCatching {
                        if (!isPeerPinAccepted(providedPin(call))) {
                            call.respond(HttpStatusCode.Forbidden, "pin_required")
                            return@runCatching
                        }
                        val body = call.receiveText()
                        if (body.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, "Empty pairing payload")
                            return@runCatching
                        }
                        val scanningDevice = runCatching {
                            json.decodeFromString(PairedDeviceEntity.serializer(), body)
                        }.getOrElse { decodeError ->
                            onLog("Invalid pairing JSON payload", decodeError)
                            call.respond(HttpStatusCode.BadRequest, "Invalid pairing payload")
                            return@runCatching
                        }
                        if (scanningDevice.deviceId.isBlank() || scanningDevice.deviceName.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, "Missing required device fields")
                            return@runCatching
                        }
                        val localId = identityProvider().deviceId
                        if (scanningDevice.deviceId == localId) {
                            call.respond(HttpStatusCode.BadRequest, "Cannot pair with self")
                            return@runCatching
                        }

                        // Persist off the request-critical path so Room failures never tear down CIO.
                        withContext(Dispatchers.IO) {
                            onPairingRespond(scanningDevice)
                        }
                        onLog(
                            "Paired inbound device ${scanningDevice.deviceName} (${scanningDevice.deviceId})",
                            null
                        )
                        call.respond(HttpStatusCode.Created)
                        serverScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    onPairingRespondComplete(scanningDevice)
                                }
                            }.onFailure { error ->
                                onLog(
                                    "Pairing roster seed failed for ${scanningDevice.deviceName}",
                                    error
                                )
                            }
                        }
                    }.onFailure { error ->
                        onLog("POST /api/v1/pairing/respond failed", error)
                        runCatching {
                            call.respond(HttpStatusCode.InternalServerError, "pairing_failed")
                        }
                    }
                }

                get("/api/v1/devices") {
                    runCatching {
                        val devices = withContext(Dispatchers.IO) { onListDevices() }
                        call.respondText(
                            text = json.encodeToString(
                                ListSerializer(PairedDeviceEntity.serializer()),
                                devices
                            ),
                            contentType = ContentType.Application.Json
                        )
                    }.onFailure { error ->
                        onLog("GET /api/v1/devices failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "devices_failed")
                    }
                }

                post("/api/v1/devices/merge") {
                    runCatching {
                        val body = call.receiveText()
                        if (body.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, "Empty cluster payload")
                            return@runCatching
                        }
                        val request = runCatching {
                            json.decodeFromString(ClusterSyncRequest.serializer(), body)
                        }.getOrElse { decodeError ->
                            onLog("Invalid cluster JSON payload", decodeError)
                            call.respond(HttpStatusCode.BadRequest, "Invalid cluster payload")
                            return@runCatching
                        }
                        withContext(Dispatchers.IO) {
                            onClusterMerge(request)
                        }
                        call.respond(HttpStatusCode.Created)
                    }.onFailure { error ->
                        onLog("POST /api/v1/devices/merge failed", error)
                        runCatching {
                            call.respond(HttpStatusCode.InternalServerError, "cluster_failed")
                        }
                    }
                }

                get("/api/v1/files/list") {
                    runCatching {
                        if (!isPeerPinAccepted(providedPin(call))) {
                            call.respond(HttpStatusCode.Forbidden, "pin_required")
                            return@runCatching
                        }
                        val pathStr = call.request.queryParameters["path"]
                            ?: return@runCatching call.respond(HttpStatusCode.BadRequest)
                        if (!isPathAllowed(pathStr)) {
                            call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                            return@runCatching
                        }
                        val listing = withContext(Dispatchers.IO) {
                            localFiles.listDirectory(pathStr)
                        }.getOrElse { error ->
                            val missing = error.message?.contains("does not exist") == true
                            if (missing) {
                                call.respond(HttpStatusCode.NotFound)
                            } else {
                                call.respond(HttpStatusCode.BadRequest, error.message ?: "list_failed")
                            }
                            return@runCatching
                        }
                        val items = listing.directories + listing.files
                        call.respondText(
                            text = json.encodeToString(items),
                            contentType = ContentType.Application.Json
                        )
                    }.onFailure { error ->
                        onLog("GET /api/v1/files/list failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "list_failed")
                    }
                }

                get("/api/v1/files/stream") {
                    runCatching {
                        if (!isPeerPinAccepted(providedPin(call))) {
                            call.respond(HttpStatusCode.Forbidden, "pin_required")
                            return@runCatching
                        }
                        val pathStr = call.request.queryParameters["path"]
                            ?: return@runCatching call.respond(HttpStatusCode.BadRequest)
                        if (!isPathAllowed(pathStr)) {
                            call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                            return@runCatching
                        }
                        val filePath = Path(pathStr)

                        val fileMetadata = SystemFileSystem.metadataOrNull(filePath)
                        if (SystemFileSystem.exists(filePath) &&
                            fileMetadata?.isDirectory != true
                        ) {
                            val fileSize = fileMetadata?.size?.coerceAtLeast(0L) ?: 0L
                            call.respondOutputStream(
                                contentType = ContentType.Application.OctetStream,
                                status = HttpStatusCode.OK,
                                contentLength = fileSize
                            ) {
                                SystemFileSystem.source(filePath).buffered().use { source ->
                                    val buffer = ByteArray(8192)
                                    while (!source.exhausted()) {
                                        val read = source.readAtMostTo(buffer)
                                        if (read > 0) {
                                            write(buffer, 0, read)
                                        }
                                    }
                                }
                            }
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }.onFailure { error ->
                        onLog("GET /api/v1/files/stream failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "stream_failed")
                    }
                }

                post("/api/v1/files/upload") {
                    runCatching {
                        // Browse/list/stream stay PIN-gated. Direct send (upload) is allowed
                        // regardless of peer browse-lock state so Multi Copy / Send File work.
                        val preferredPathStr = call.request.queryParameters["targetPath"]
                            ?: return@runCatching call.respond(HttpStatusCode.BadRequest)
                        if (!isPathAllowed(preferredPathStr)) {
                            call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                            return@runCatching
                        }
                        // Never overwrite an existing file — collide like Finder/Files: name (1).ext
                        val targetPathStr = UniqueFileNames.resolve(preferredPathStr)
                        if (!isPathAllowed(targetPathStr)) {
                            call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                            return@runCatching
                        }
                        val targetPath = Path(targetPathStr)
                        val parent = targetPath.parent
                        if (parent != null && !SystemFileSystem.exists(parent)) {
                            SystemFileSystem.createDirectories(parent)
                        }

                        val expectedLength = call.request.headers["Content-Length"]?.toLongOrNull()
                        val channel = call.receiveChannel()
                        val received = receiveUploadBytes(channel, targetPath, expectedLength)
                        val complete = expectedLength == null || received == expectedLength
                        if (!complete || received <= 0L) {
                            runCatching {
                                if (SystemFileSystem.exists(targetPath)) {
                                    SystemFileSystem.delete(targetPath)
                                }
                            }
                            val reason = if (received <= 0L) "upload_empty" else "upload_incomplete"
                            onLog(
                                "upload rejected path=$targetPathStr bytes=$received" +
                                    (expectedLength?.let { " expected=$it" } ?: "") +
                                    " reason=$reason",
                                null
                            )
                            call.respond(HttpStatusCode.BadRequest, reason)
                            return@runCatching
                        }
                        onLog(
                            "upload complete path=$targetPathStr bytes=$received" +
                                (expectedLength?.let { " expected=$it" } ?: ""),
                            null
                        )
                        val receivedName = targetPathStr
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                        if (receivedName.isNotBlank()) {
                            notifyFilesReceived(listOf(receivedName))
                        }
                        call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.Created)
                    }.onFailure { error ->
                        onLog("POST /api/v1/files/upload failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "upload_failed")
                    }
                }

                post("/api/v1/clipboard/send") {
                    runCatching {
                        val settings = FileApexServices.settings
                        if (!settings.clipboardSharingEnabled.value) {
                            call.respondText(
                                text = "clipboard_disabled",
                                status = HttpStatusCode.Forbidden
                            )
                            return@runCatching
                        }
                        if (!isPeerPinAccepted(providedPin(call))) {
                            call.respond(HttpStatusCode.Forbidden, "pin_required")
                            return@runCatching
                        }
                        val body = call.receiveText()
                        val request = json.decodeFromString(ClipboardSendRequest.serializer(), body)
                        if (request.text.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, "empty_text")
                            return@runCatching
                        }
                        withContext(Dispatchers.Main) {
                            PlatformClipboard.setSystemClipboardText(request.text)
                            if (isWebUrl(request.text)) {
                                PlatformClipboard.openUrlInDefaultBrowser(request.text)
                            }
                        }
                        val response = ClipboardSendResponse(
                            status = "ok",
                            recipientDeviceName = identityProvider().deviceName
                        )
                        call.respondText(
                            text = json.encodeToString(ClipboardSendResponse.serializer(), response),
                            contentType = ContentType.Application.Json
                        )
                    }.onFailure { error ->
                        onLog("POST /api/v1/clipboard/send failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "clipboard_failed")
                    }
                }

                post("/api/v1/notes/send") {
                    runCatching {
                        val body = call.receiveText()
                        val record = json.decodeFromString(com.fileapex.data.note.NoteRecord.serializer(), body)
                        FileApexServices.noteRepository.addNote(record.copy(isMine = false))
                        call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                    }.onFailure { error ->
                        onLog("POST /api/v1/notes/send failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "note_failed")
                    }
                }

                post("/api/v1/notes/delete") {
                    runCatching {
                        val body = call.receiveText()
                        val jsonObj = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                        val noteId = jsonObj?.get("noteId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                        if (noteId.isNotBlank()) {
                            FileApexServices.noteRepository.deleteNote(noteId)
                        }
                        call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                    }.onFailure { error ->
                        onLog("POST /api/v1/notes/delete failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "delete_failed")
                    }
                }

                get("/") {
                    call.respondText(WEB_SHARE_HTML, ContentType.Text.Html)
                }

                get("/share") {
                    call.respondText(WEB_SHARE_HTML, ContentType.Text.Html)
                }

                post("/api/v1/web/send-clipboard") {
                    runCatching {
                        val body = call.receiveText()
                        val jsonObj = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                        val targetDeviceId = jsonObj?.get("targetDeviceId")?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }.orEmpty()
                        val text = jsonObj?.get("text")?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }.orEmpty()

                        if (targetDeviceId.isBlank() || text.isBlank()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                """{"status":"error","message":"Target device and text are required"}"""
                            )
                            return@runCatching
                        }

                        val devices = withContext(Dispatchers.IO) { onListDevices() }
                        val targetDevice = devices.firstOrNull { it.deviceId == targetDeviceId }
                        if (targetDevice == null) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                """{"status":"error","message":"Target device not found"}"""
                            )
                            return@runCatching
                        }

                        val client = FileApexServices.client
                        val identity = identityProvider()
                        val result = client.sendClipboard(
                            host = targetDevice.lastKnownIp,
                            port = targetDevice.port,
                            senderDeviceId = identity.deviceId,
                            senderDeviceName = identity.deviceName,
                            text = text
                        )
                        val respJson = json.encodeToString(ClipboardSendResponse.serializer(), result)
                        call.respondText(respJson, ContentType.Application.Json)
                    }.onFailure { error ->
                        onLog("POST /api/v1/web/send-clipboard failed", error)
                        val errMsg = error.message ?: "Failed to send clipboard"
                        val safeMsg = json.encodeToString(errMsg)
                        call.respondText(
                            """{"status":"error","message":$safeMsg}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError
                        )
                    }
                }

                get("/api/v1/health") {
                    call.respondText("ok", ContentType.Text.Plain)
                }

                get("/api/v1/diagnostics") {
                    if (!isPeerPinAccepted(providedPin(call))) {
                        call.respond(HttpStatusCode.Forbidden, "pin_required")
                        return@get
                    }
                    val snapshot = withContext(Dispatchers.IO) {
                        runCatching { collectDeviceDiagnostics() }
                            .getOrElse { error ->
                                onLog("GET /api/v1/diagnostics collector failed — returning partial snapshot", error)
                                collectDeviceDiagnosticsFallback()
                            }
                    }
                    runCatching {
                        call.respondText(
                            text = json.encodeToString(PeerDeviceDiagnostics.serializer(), snapshot),
                            contentType = ContentType.Application.Json
                        )
                    }.onFailure { error ->
                        onLog("GET /api/v1/diagnostics encode failed", error)
                        call.respond(HttpStatusCode.InternalServerError, "diagnostics_failed")
                    }
                }
            }
        }.start(wait = false)

            serverScope.launch {
                onLog("CIO engine started and listening on port $port", null)
            }
        }
    }

    fun stop(gracePeriodMillis: Long = 1_000, timeoutMillis: Long = 2_000) {
        synchronized(engineLock) {
            onLog("Stopping CIO engine", null)
            runCatching {
                serverEngine?.stop(
                    gracePeriodMillis = gracePeriodMillis,
                    timeoutMillis = timeoutMillis
                )
            }.onFailure { error ->
                onLog("Error while stopping engine", error)
            }
            serverEngine = null
            lifecycleJob.cancel()
        }
    }

    private fun isPathAllowed(absolutePath: String): Boolean {
        return PathUtils.isWithinRoot(absolutePath, identityProvider().rootPath)
    }

    private fun providedPin(call: ApplicationCall): String {
        val fromQuery = call.request.queryParameters["pin"].orEmpty().trim()
        if (fromQuery.isNotEmpty()) return fromQuery
        return call.request.headers["X-FileApex-Pin"].orEmpty().trim()
    }

    /**
     * When PIN required is off, always accept.
     * When on, require a non-blank configured PIN that matches the peer-provided value.
     */
    private fun isPeerPinAccepted(provided: String): Boolean {
        val settings = FileApexServices.settings
        if (!settings.pinRequiredEnabled.value) return true
        val expected = settings.devicePin.value
        return expected.isNotBlank() && provided == expected
    }

    /**
     * Reads an upload body without hanging when the sender closes early or stalls.
     * URLSession clients send Content-Length; FileApex/Ktor senders may use chunked EOF.
     */
    private suspend fun receiveUploadBytes(
        channel: ByteReadChannel,
        targetPath: Path,
        expectedLength: Long?
    ): Long {
        var received = 0L
        var idleDeadlineMs = TimeUtils.now() + UPLOAD_IDLE_TIMEOUT_MS
        SystemFileSystem.sink(targetPath).buffered().use { sink ->
            val buffer = ByteArray(8192)
            while (expectedLength == null || received < expectedLength) {
                if (TimeUtils.now() >= idleDeadlineMs) break
                val remaining = expectedLength?.minus(received)
                val want = if (remaining == null) {
                    buffer.size
                } else {
                    minOf(buffer.size.toLong(), remaining).toInt().coerceAtLeast(1)
                }
                val read = channel.readAvailable(buffer, 0, want)
                when {
                    read > 0 -> {
                        sink.write(buffer, 0, read)
                        received += read.toLong()
                        idleDeadlineMs = TimeUtils.now() + UPLOAD_IDLE_TIMEOUT_MS
                    }
                    channel.isClosedForRead -> break
                    expectedLength != null && received >= expectedLength -> break
                    !channel.awaitContent() -> break
                }
            }
        }
        return received
    }

    companion object {
        private const val UPLOAD_IDLE_TIMEOUT_MS = 60_000L
        private val WEB_SHARE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>FileApex Web Share</title>
            <style>
              body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 100vh; }
              .card { background: #1e293b; border-radius: 16px; padding: 24px; max-width: 480px; width: 100%; box-shadow: 0 10px 25px rgba(0,0,0,0.5); border: 1px solid #334155; }
              h1 { font-size: 1.5rem; margin-top: 0; color: #38bdf8; text-align: center; }
              label { font-size: 0.9rem; color: #94a3b8; margin-top: 16px; display: block; }
              select, textarea, button { width: 100%; border-radius: 8px; border: 1px solid #475569; padding: 12px; margin-top: 6px; box-sizing: border-box; font-size: 1rem; background: #0f172a; color: #f8fafc; }
              textarea { height: 120px; resize: vertical; }
              button { background: #0284c7; border: none; font-weight: 600; cursor: pointer; margin-top: 20px; transition: background 0.2s; }
              button:hover { background: #0369a1; }
              .snackbar { visibility: hidden; min-width: 250px; background-color: #334155; color: #fff; text-align: center; border-radius: 8px; padding: 14px; position: fixed; z-index: 100; left: 50%; bottom: 30px; transform: translateX(-50%); font-size: 1rem; border: 1px solid #0284c7; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
              .snackbar.show { visibility: visible; animation: fadein 0.3s, fadeout 0.3s 2.7s; }
              @keyframes fadein { from { bottom: 0; opacity: 0; } to { bottom: 30px; opacity: 1; } }
              @keyframes fadeout { from { bottom: 30px; opacity: 1; } to { bottom: 0; opacity: 0; } }
            </style>
            </head>
            <body>
            <div class="card">
              <h1>FileApex Web Share</h1>
              <label for="deviceSelect">Select Destination Device:</label>
              <select id="deviceSelect"><option value="">Loading devices...</option></select>
              <label for="shareText">Text or Link to Share:</label>
              <textarea id="shareText" placeholder="Paste link or text here..."></textarea>
              <button id="sendBtn" onclick="sendClipboard()">Send Clipboard</button>
            </div>
            <div id="snackbar" class="snackbar"></div>
            <script>
              let snackbarTimer;
              function showSnackbar(msg) {
                const sb = document.getElementById("snackbar");
                sb.innerText = msg;
                sb.className = "snackbar show";
                clearTimeout(snackbarTimer);
                snackbarTimer = setTimeout(() => { sb.className = "snackbar"; }, 3000);
              }
              async function loadDevices() {
                try {
                  const res = await fetch('/api/v1/devices');
                  const devices = await res.json();
                  const select = document.getElementById('deviceSelect');
                  select.innerHTML = '';
                  if (!devices || devices.length === 0) {
                    select.innerHTML = '<option value="">No paired devices found</option>';
                    return;
                  }
                  devices.forEach(d => {
                    const opt = document.createElement('option');
                    opt.value = d.deviceId;
                    opt.innerText = d.deviceName + ' (' + d.ipAddress + ')';
                    select.appendChild(opt);
                  });
                } catch (e) {
                  document.getElementById('deviceSelect').innerHTML = '<option value="">Error loading devices</option>';
                }
              }
              async function sendClipboard() {
                const deviceId = document.getElementById('deviceSelect').value;
                const text = document.getElementById('shareText').value;
                if (!deviceId) { alert('Please select a destination device.'); return; }
                if (!text.trim()) { alert('Please enter text or link to send.'); return; }
                showSnackbar("Sending Clipboard…");
                try {
                  const res = await fetch('/api/v1/web/send-clipboard', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ targetDeviceId: deviceId, text: text })
                  });
                  const data = await res.json();
                  if (res.ok && data.status === 'ok') {
                    showSnackbar("Successfully received by " + data.recipientDeviceName);
                    document.getElementById('shareText').value = '';
                  } else {
                    showSnackbar(data.message || "Failed to send clipboard");
                  }
                } catch (e) {
                  showSnackbar("Error sending clipboard: " + e.message);
                }
              }
              loadDevices();
            </script>
            </body>
            </html>
        """.trimIndent()
    }
}
