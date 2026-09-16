package com.fileapex.network.routes

import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexServer
import com.fileapex.network.SocketFileStreamer
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.defaultDownloadsDir
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
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
    post("/api/v1/notes/send") {
        runCatching {
            val body = call.receiveText()
            val record = server.json.decodeFromString(com.fileapex.data.note.NoteRecord.serializer(), body)
            FileApexServices.noteRepository.addNote(
                record.copy(isMine = false, attachmentLocalPath = null)
            )
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }.onFailure { error ->
            server.onLog("POST /api/v1/notes/send failed", error)
            call.respond(HttpStatusCode.InternalServerError, "note_failed")
        }
    }

    post("/api/v1/notes/attachment") {
        runCatching {
            val noteId = call.request.queryParameters["noteId"].orEmpty().trim()
            val fileName = call.request.queryParameters["fileName"].orEmpty().trim()
            if (noteId.isBlank() || fileName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "note_attachment_missing")
                return@runCatching
            }
            val expectedLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (expectedLength != null &&
                expectedLength > com.fileapex.cloud.drive.DriveRelayPolicy.NOTES_LAN_ATTACHMENT_MAX_BYTES
            ) {
                call.respond(HttpStatusCode.PayloadTooLarge, "note_attachment_too_large")
                return@runCatching
            }
            val dest = UniqueFileNames.resolveInDirectory(defaultDownloadsDir(), fileName)
            val channel = call.receiveChannel()
            val received = server.receiveUploadBytes(channel, dest, startOffset = 0L, expectedLength)
            val complete = expectedLength == null || received == expectedLength
            if (!complete || received <= 0L ||
                received > com.fileapex.cloud.drive.DriveRelayPolicy.NOTES_LAN_ATTACHMENT_MAX_BYTES
            ) {
                SocketFileStreamer.deleteQuietly(dest)
                val status = if (received > com.fileapex.cloud.drive.DriveRelayPolicy.NOTES_LAN_ATTACHMENT_MAX_BYTES) {
                    HttpStatusCode.PayloadTooLarge
                } else {
                    HttpStatusCode.BadRequest
                }
                call.respond(status, "note_attachment_rejected")
                return@runCatching
            }
            FileApexServices.noteRepository.setAttachmentLocalPath(noteId, dest)
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }.onFailure { error ->
            server.onLog("POST /api/v1/notes/attachment failed", error)
            call.respond(HttpStatusCode.InternalServerError, "note_attachment_failed")
        }
    }

    post("/api/v1/notes/delete") {
        runCatching {
            val body = call.receiveText()
            val jsonObj = server.json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            val noteId = jsonObj?.get("noteId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            val driveFileId = jsonObj?.get("driveFileId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val checksum = jsonObj?.get("checksum")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val attachmentName = jsonObj?.get("attachmentName")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            if (noteId.isNotBlank() || !driveFileId.isNullOrBlank() || !checksum.isNullOrBlank()) {
                FileApexServices.noteRepository.applyRemoteRetract(
                    noteId,
                    driveFileId,
                    checksum,
                    attachmentName
                )
            }
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }.onFailure { error ->
            server.onLog("POST /api/v1/notes/delete failed", error)
            call.respond(HttpStatusCode.InternalServerError, "delete_failed")
        }
    }

    post("/api/v1/bulletin/sync/batch") {
        runCatching {
            val body = call.receiveText()
            val batch = server.json.decodeFromString(
                com.fileapex.data.bulletin.BulletinSyncBatch.serializer(),
                body
            )
            val isPaired = FileApexServices.deviceRepositoryOrNull()?.getDevice(batch.originDeviceId) != null
            if (!isPaired) {
                call.respond(HttpStatusCode.Unauthorized, "unauthorized_peer")
                return@runCatching
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
        runCatching {
            val from = call.request.queryParameters["from"]?.trim().orEmpty().ifEmpty {
                call.request.headers["X-FileApex-Device-Id"]?.trim().orEmpty()
            }
            if (from.isNotBlank() && FileApexServices.deviceRepositoryOrNull()?.getDevice(from) == null) {
                call.respond(HttpStatusCode.Unauthorized, "unauthorized_peer")
                return@runCatching
            }
            val messageId = call.request.queryParameters["messageId"].orEmpty().trim()
            val fileName = call.request.queryParameters["fileName"].orEmpty().trim()
            if (messageId.isBlank() || fileName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "bulletin_file_missing")
                return@runCatching
            }
            val message = FileApexServices.bulletinBoardRepository.getMessage(messageId)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, "bulletin_message_missing")
                    return@runCatching
                }
            val meta = FileApexServices.bulletinBoardRepository.decodeFileMetadata(message)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, "bulletin_file_metadata_missing")
                    return@runCatching
                }
            val localPath = meta.localPath?.takeIf { it.isNotBlank() }
                ?: run {
                    call.respond(HttpStatusCode.NotFound, "bulletin_file_unavailable")
                    return@runCatching
                }
            val source = Path(localPath)
            if (!SystemFileSystem.exists(source)) {
                call.respond(HttpStatusCode.NotFound, "bulletin_file_missing_on_disk")
                return@runCatching
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
        runCatching {
            val body = call.receiveText()
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
                return@runCatching
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
