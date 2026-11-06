package com.fileapex.network.routes

import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexServer
import com.fileapex.network.SocketFileStreamer
import com.fileapex.util.cancellableCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal fun Route.registerBulletinRoutes(server: FileApexServer) {
    get("/api/v1/bulletin/delta") {
        cancellableCatching {
            val from = server.requesterDeviceId(call)
            if (from.isEmpty() || FileApexServices.deviceRepositoryOrNull()?.getDevice(from) == null) {
                call.respond(HttpStatusCode.Unauthorized, "unauthorized_peer")
                return@cancellableCatching
            }
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val delta = FileApexServices.bulletinSyncEngine.buildDelta(since)
            call.respondText(
                server.json.encodeToString(com.fileapex.data.bulletin.BulletinDeltaResponse.serializer(), delta),
                ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/bulletin/delta failed", error)
            call.respond(HttpStatusCode.InternalServerError, "bulletin_delta_failed")
        }
    }

    post("/api/v1/bulletin/sync/batch") {
        cancellableCatching {
            val body = call.receiveBoundedText()
            val batch = server.json.decodeFromString(
                com.fileapex.data.bulletin.BulletinSyncBatch.serializer(),
                body
            )
            val isPaired = FileApexServices.deviceRepositoryOrNull()?.getDevice(batch.originDeviceId) != null &&
                server.authenticatedPeerId(call) == batch.originDeviceId
            if (!isPaired) {
                call.respond(HttpStatusCode.Unauthorized, "unauthorized_peer")
                return@cancellableCatching
            }
            val ack = FileApexServices.bulletinSyncEngine.processIncomingBatch(batch)
            call.respondText(
                server.json.encodeToString(com.fileapex.data.bulletin.BulletinSyncAck.serializer(), ack),
                ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("POST /api/v1/bulletin/sync/batch failed", error)
            call.respond(HttpStatusCode.InternalServerError, "bulletin_sync_failed")
        }
    }

    get("/api/v1/bulletin/file") {
        cancellableCatching {
            if (server.authenticatedPeerId(call) == null) {
                call.respond(HttpStatusCode.Unauthorized, "unauthorized_peer")
                return@cancellableCatching
            }
            val messageId = call.request.queryParameters["messageId"].orEmpty().trim()
            val fileName = call.request.queryParameters["fileName"].orEmpty().trim()
            if (messageId.isBlank() || fileName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "bulletin_file_missing")
                return@cancellableCatching
            }
            val message = FileApexServices.bulletinBoardRepository.getMessage(messageId)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, "bulletin_message_missing")
                    return@cancellableCatching
                }
            val meta = FileApexServices.bulletinBoardRepository.decodeFileMetadata(message)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, "bulletin_file_metadata_missing")
                    return@cancellableCatching
                }
            val localPath = meta.localPath?.takeIf { it.isNotBlank() }
                ?: run {
                    call.respond(HttpStatusCode.NotFound, "bulletin_file_unavailable")
                    return@cancellableCatching
                }
            val source = Path(localPath)
            if (!SystemFileSystem.exists(source)) {
                call.respond(HttpStatusCode.NotFound, "bulletin_file_missing_on_disk")
                return@cancellableCatching
            }
            val size = SystemFileSystem.metadataOrNull(source)?.size ?: 0L
            call.respondOutputStream(
                contentType = ContentType.Application.OctetStream,
                status = HttpStatusCode.OK,
                contentLength = size
            ) {
                SocketFileStreamer.streamFromOffset(localPath, 0L) { buffer, length ->
                    write(buffer, 0, length)
                }
            }
        }.onFailure { error ->
            server.onLog("GET /api/v1/bulletin/file failed", error)
            call.respond(HttpStatusCode.InternalServerError, "bulletin_file_failed")
        }
    }

    post("/api/v1/web/post-bulletin") {
        if (!WebIntakeGuard.check(call, loopbackOnly = true)) return@post
        cancellableCatching {
            val body = call.receiveBoundedText()
            val jsonObj = server.json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            val url = jsonObj?.get("url")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.orEmpty().trim()
            val title = jsonObj?.get("title")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.orEmpty().trim()
            val text = jsonObj?.get("text")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.orEmpty().trim()

            val payload = when {
                text.isNotBlank() -> text
                url.isNotBlank() && title.isNotBlank() -> "$title\n$url"
                url.isNotBlank() -> url
                title.isNotBlank() -> title
                else -> ""
            }
            if (payload.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    """{"status":"error","message":"url_or_text_required"}"""
                )
                return@cancellableCatching
            }

            withContext(Dispatchers.IO) {
                FileApexServices.bulletinSyncEngine.ingestSharedText(payload)
            }
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }.onFailure { error ->
            server.onLog("POST /api/v1/web/post-bulletin failed", error)
            val errMsg = error.message ?: AppI18n.t("could_not_post_bulletin")
            val safeMsg = server.json.encodeToString(errMsg)
            call.respondText(
                """{"status":"error","message":$safeMsg}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
}
