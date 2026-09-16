package com.fileapex.network.routes

import com.fileapex.network.FileApexServer
import com.fileapex.network.ResumeOffsetResponse
import com.fileapex.network.SocketFileStreamer
import com.fileapex.network.TransferResumeProtocol
import com.fileapex.network.TransferTransactionJournal
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.notifyFilesReceived
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal fun Route.registerFileRoutes(server: FileApexServer) {
    get("/api/v1/files/list") {
        runCatching {
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@runCatching
            }
            val rawPath = call.request.queryParameters["path"].orEmpty().trim()
            val sharedRoot = server.identityProvider().rootPath
            val pathStr = if (rawPath.isBlank() || rawPath == "/" || rawPath == "\\") {
                sharedRoot
            } else {
                rawPath
            }
            if (!server.isPathAllowed(pathStr)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            val listing = withContext(Dispatchers.IO) {
                server.localFiles.listDirectory(pathStr)
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
                text = server.json.encodeToString(items),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/files/list failed", error)
            call.respond(HttpStatusCode.InternalServerError, "list_failed")
        }
    }

    get("/api/v1/files/stream") {
        runCatching {
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@runCatching
            }
            val rawPath = call.request.queryParameters["path"].orEmpty().trim()
            if (rawPath.isBlank()) {
                return@runCatching call.respond(HttpStatusCode.BadRequest)
            }
            val pathStr = if (rawPath == "/" || rawPath == "\\") {
                server.identityProvider().rootPath
            } else {
                rawPath
            }
            if (!server.isPathAllowed(pathStr)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            val filePath = Path(pathStr)

            val fileMetadata = SystemFileSystem.metadataOrNull(filePath)
            if (SystemFileSystem.exists(filePath) && fileMetadata?.isDirectory != true) {
                val fileSize = fileMetadata?.size?.coerceAtLeast(0L) ?: 0L
                val offset = TransferResumeProtocol.parseByteOffset(
                    queryOffset = call.request.queryParameters[TransferResumeProtocol.OFFSET_QUERY],
                    rangeHeader = call.request.headers[HttpHeaders.Range]
                )
                if (offset > fileSize) {
                    call.response.header(HttpHeaders.ContentRange, "bytes */$fileSize")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                    return@runCatching
                }
                val remaining = (fileSize - offset).coerceAtLeast(0L)
                val partial = offset > 0L
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                if (partial) {
                    val endInclusive = (fileSize - 1L).coerceAtLeast(offset)
                    call.response.header(
                        HttpHeaders.ContentRange,
                        "bytes $offset-$endInclusive/$fileSize"
                    )
                }
                if (remaining == 0L) {
                    call.respond(if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK)
                    return@runCatching
                }
                call.respondOutputStream(
                    contentType = ContentType.Application.OctetStream,
                    status = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    contentLength = remaining
                ) {
                    SocketFileStreamer.streamFromOffset(pathStr, offset) { buffer, length ->
                        write(buffer, 0, length)
                    }
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }.onFailure { error ->
            server.onLog("GET /api/v1/files/stream failed", error)
            call.respond(HttpStatusCode.InternalServerError, "stream_failed")
        }
    }

    get("/api/v1/files/resume") {
        runCatching {
            val preferredPathStr = call.request.queryParameters["targetPath"]
                ?: return@runCatching call.respond(HttpStatusCode.BadRequest)
            if (!server.isPathAllowed(preferredPathStr)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            val expectedSize = call.request.queryParameters[TransferResumeProtocol.EXPECTED_SIZE_QUERY]
                ?.toLongOrNull()
                ?: 0L
            val txId = call.request.queryParameters[TransferResumeProtocol.TRANSACTION_ID_QUERY].orEmpty()
            val senderId = call.request.queryParameters["from"]
                ?: call.request.queryParameters[TransferResumeProtocol.SENDER_DEVICE_ID_QUERY]
                ?: ""
            val snapshot = TransferResumeProtocol.inspectIncoming(
                preferredPath = preferredPathStr,
                expectedSize = expectedSize,
                transactionId = txId,
                senderDeviceId = senderId
            )
            call.respondText(
                text = server.json.encodeToString(ResumeOffsetResponse.serializer(), snapshot),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/files/resume failed", error)
            call.respond(HttpStatusCode.InternalServerError, "resume_failed")
        }
    }

    post("/api/v1/files/upload") {
        runCatching {
            // Browse/list/stream stay PIN-gated. Direct send (upload) is allowed
            // regardless of peer browse-lock state so Multi Copy / Send File work.
            val preferredPathStr = call.request.queryParameters["targetPath"]
                ?: return@runCatching call.respond(HttpStatusCode.BadRequest)
            if (!server.isPathAllowed(preferredPathStr)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            val txId = call.request.queryParameters[TransferResumeProtocol.TRANSACTION_ID_QUERY].orEmpty()
            val txTimestamp = call.request.queryParameters[TransferResumeProtocol.TIMESTAMP_QUERY]?.toLongOrNull()
                ?: com.fileapex.util.TimeUtils.now()
            val senderId = call.request.queryParameters["from"]
                ?: call.request.queryParameters[TransferResumeProtocol.SENDER_DEVICE_ID_QUERY]
                ?: ""
            if (txId.isNotBlank() && TransferTransactionJournal.findCompleted(txId, senderId, 0L) != null) {
                server.onLog("TransferLog: upload skipped (already completed) txId=$txId sender=$senderId path=$preferredPathStr", null)
                call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.Created)
                return@runCatching
            }
            // Never overwrite an existing file — collide like Finder/Files: name (1).ext
            val targetPathStr = UniqueFileNames.resolve(preferredPathStr)
            if (!server.isPathAllowed(targetPathStr)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            val partPath = SocketFileStreamer.partPathFor(targetPathStr)
            val sessionLength = call.request.headers["Content-Length"]?.toLongOrNull()
            val offset = TransferResumeProtocol.parseByteOffset(
                queryOffset = call.request.queryParameters[TransferResumeProtocol.OFFSET_QUERY],
                rangeHeader = null
            ).let { fromQuery ->
                if (fromQuery > 0L) {
                    fromQuery
                } else {
                    TransferResumeProtocol.parseContentRangeStart(
                        call.request.headers[HttpHeaders.ContentRange]
                    )
                }
            }
            val totalSize = TransferResumeProtocol.parseTotalSize(
                queryTotal = call.request.queryParameters[TransferResumeProtocol.TOTAL_SIZE_QUERY],
                contentRange = call.request.headers[HttpHeaders.ContentRange],
                sessionLength = sessionLength,
                offset = offset
            )
            val existingPart = SocketFileStreamer.fileLength(partPath)
            if (offset > existingPart) {
                server.onLog(
                    "upload resume gap path=$partPath offset=$offset existing=$existingPart",
                    null
                )
                call.respond(HttpStatusCode.BadRequest, "resume_offset_invalid")
                return@runCatching
            }
            val channel = call.receiveChannel()
            val uploadFileName = targetPathStr.substringAfterLast('/').substringAfterLast('\\')
            com.fileapex.domain.transfer.TransferActivityGuard.beginTransfer(fileName = uploadFileName)
            val received = try {
                server.receiveUploadBytes(channel, partPath, offset, sessionLength)
            } finally {
                com.fileapex.domain.transfer.TransferActivityGuard.endTransfer()
            }
            val totalReceived = offset + received
            val complete = when {
                received <= 0L && offset == 0L -> false
                totalSize != null -> totalReceived == totalSize
                sessionLength != null -> received == sessionLength
                else -> received > 0L
            }
            if (!complete) {
                val reason = if (totalReceived <= 0L) "upload_empty" else "upload_incomplete"
                server.onLog(
                    "upload paused path=$partPath offset=$offset session=$received" +
                        (totalSize?.let { " total=$it" } ?: "") +
                        " reason=$reason",
                    null
                )
                if (totalReceived <= 0L) {
                    SocketFileStreamer.deleteQuietly(partPath)
                }
                call.respond(HttpStatusCode.BadRequest, reason)
                return@runCatching
            }
            val finalPath = SocketFileStreamer.finalizePart(partPath, targetPathStr)
            if (txId.isNotBlank()) {
                TransferTransactionJournal.recordCompleted(
                    transactionId = txId,
                    senderDeviceId = senderId,
                    targetPath = targetPathStr,
                    finalPath = finalPath,
                    byteSize = totalReceived,
                    timestampEpochMs = txTimestamp
                )
            }
            server.onLog(
                "TransferLog: [txId=$txId] sender=$senderId path=$finalPath bytes=$totalReceived timestamp=$txTimestamp" +
                    (if (offset > 0L) " resumedFrom=$offset" else ""),
                null
            )
            call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.Created)
            val receivedName = finalPath
                .substringAfterLast('/')
                .substringAfterLast('\\')
            if (receivedName.isNotBlank()) {
                val uploadFile = java.io.File(finalPath)
                if (com.fileapex.update.BulletinApkUpdatePolicy.shouldAutoUpdateDirectFile(
                        receivedName,
                        uploadFile.length(),
                        uploadFile.lastModified(),
                        transactionId = txId
                    )
                ) {
                    val version = com.fileapex.update.BulletinApkUpdatePolicy.extractVersionFromApkName(receivedName) ?: "v0.0.0"
                    server.serverScope.launch {
                        kotlinx.coroutines.delay(200)
                        com.fileapex.update.BulletinApkUpdateCoordinator.triggerDirectApkInstall(
                            localPath = finalPath,
                            version = version,
                            fileName = receivedName,
                            transactionId = txId,
                            transactionTimestampEpochMs = txTimestamp,
                            senderDeviceId = senderId
                        )
                    }
                } else {
                    notifyFilesReceived(listOf(receivedName))
                }
            }
        }.onFailure { error ->
            server.onLog("POST /api/v1/files/upload failed", error)
            call.respond(HttpStatusCode.InternalServerError, "upload_failed")
        }
    }

    post("/api/v1/files/mkdir") {
        runCatching {
            val pathStr = call.request.queryParameters["targetPath"]
                ?: return@runCatching call.respond(HttpStatusCode.BadRequest, "Missing targetPath")
            if (!server.isPathAllowed(pathStr)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            val dir = java.io.File(pathStr)
            dir.mkdirs()
            call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.Created)
        }.onFailure { error ->
            server.onLog("POST /api/v1/files/mkdir failed", error)
            call.respond(HttpStatusCode.InternalServerError, "mkdir_failed")
        }
    }
}
