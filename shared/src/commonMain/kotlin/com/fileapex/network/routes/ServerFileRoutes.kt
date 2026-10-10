package com.fileapex.network.routes

import com.fileapex.network.FileApexServer
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.network.InFlightSegmentBytes
import com.fileapex.network.RangeLedger
import com.fileapex.network.ReceiverCancelRegistry
import com.fileapex.network.ResumeOffsetResponse
import com.fileapex.network.SegmentStateResponse
import com.fileapex.network.SocketFileStreamer
import com.fileapex.network.TransferCapabilities
import com.fileapex.network.TransferRanges
import com.fileapex.network.TransferResumeProtocol
import com.fileapex.network.TransferRuntime
import com.fileapex.network.TransferTransactionJournal
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.notifyFilesReceived
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private const val MAX_LIST_PAGE = 5_000

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
            // Paged listing: encode one slice instead of the whole directory so a huge folder never
            // becomes a single multi-MB response. Clients without `limit` (older builds) get everything.
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIST_PAGE)
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val dirCount = listing.directories.size
            val total = dirCount + listing.files.size
            val items = if (limit == null) {
                listing.directories + listing.files
            } else {
                val end = minOf(total.toLong(), offset.toLong() + limit).toInt()
                if (offset >= total) {
                    emptyList()
                } else {
                    buildList(end - offset) {
                        for (i in offset until end) {
                            add(if (i < dirCount) listing.directories[i] else listing.files[i - dirCount])
                        }
                    }
                }
            }
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
                val requestedLength = call.request.queryParameters[TransferResumeProtocol.LENGTH_QUERY]
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                val toEnd = (fileSize - offset).coerceAtLeast(0L)
                val remaining = requestedLength?.coerceAtMost(toEnd) ?: toEnd
                val partial = offset > 0L || remaining < fileSize
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                if (partial && remaining > 0L) {
                    val endInclusive = offset + remaining - 1L
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
                    withContext(TransferRuntime.inbound) {
                        SocketFileStreamer.streamFromOffset(pathStr, offset, byteLimit = remaining) { buffer, length ->
                            write(buffer, 0, length)
                        }
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
            // Folder backup (backup=1) replaces the earlier copy of a changed file in place and, arriving
            // in bulk, skips the per-file "received" alert. Every other upload never overwrites an
            // existing file: it collides like Finder/Files, name (1).ext.
            val backup = call.request.queryParameters["backup"] == "1"
            val targetPathStr = if (backup) preferredPathStr else UniqueFileNames.resolve(preferredPathStr)
            if (!server.isPathAllowed(targetPathStr)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            val partPath = SocketFileStreamer.partPathFor(targetPathStr)
            val cancelKey = txId.ifBlank { partPath }
            if (ReceiverCancelRegistry.isCancelled(cancelKey)) {
                call.respond(HttpStatusCode.Gone, ReceiverCancelRegistry.CANCELLED_BODY)
                return@runCatching
            }
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
            val receivedOrCancelled = try {
                receiveCancelable(cancelKey) {
                    server.receiveUploadBytes(channel, partPath, offset, sessionLength)
                }
            } finally {
                com.fileapex.domain.transfer.TransferActivityGuard.endTransfer()
            }
            if (receivedOrCancelled == null) {
                discardCancelledPart(partPath)
                call.respond(HttpStatusCode.Gone, ReceiverCancelRegistry.CANCELLED_BODY)
                return@runCatching
            }
            val received: Long = receivedOrCancelled
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
            val finalPath = SocketFileStreamer.finalizePart(partPath, targetPathStr, overwrite = backup)
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
            if (!backup) announceReceivedFile(server, finalPath, txId, txTimestamp, senderId)
        }.onFailure { error ->
            server.onLog("POST /api/v1/files/upload failed", error)
            call.respond(HttpStatusCode.InternalServerError, "upload_failed")
        }
    }

    post("/api/v1/files/delete") {
        runCatching {
            val path = call.request.queryParameters["targetPath"]
                ?: return@runCatching call.respond(HttpStatusCode.BadRequest)
            if (!server.isDeletablePath(path)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            com.fileapex.platform.trashLocalEntriesQuietly(listOf(path))
            server.onLog("Remote delete: $path", null)
            call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
        }.onFailure { error ->
            server.onLog("POST /api/v1/files/delete failed", error)
            call.respond(HttpStatusCode.InternalServerError, "delete_failed")
        }
    }

    get("/api/v1/files/capabilities") {
        call.respondText(
            text = server.json.encodeToString(
                TransferCapabilities.serializer(),
                TransferCapabilities(rangedStream = true, segmentedUpload = true, backupSync = true)
            ),
            contentType = ContentType.Application.Json
        )
    }

    get("/api/v1/files/segments") {
        runCatching {
            val target = resolveSegmentTarget(server, call) ?: return@runCatching
            if (ReceiverCancelRegistry.isCancelled(target.txId.ifBlank { target.partPath })) {
                call.respond(HttpStatusCode.Gone, ReceiverCancelRegistry.CANCELLED_BODY)
                return@runCatching
            }
            val prepare = call.request.queryParameters[TransferResumeProtocol.PREPARE_QUERY] == "1"
            if (target.txId.isNotBlank() &&
                TransferTransactionJournal.findCompleted(target.txId, target.senderId, target.totalSize) != null
            ) {
                call.respondText(
                    text = server.json.encodeToString(
                        SegmentStateResponse.serializer(),
                        SegmentStateResponse(complete = true)
                    ),
                    contentType = ContentType.Application.Json
                )
                return@runCatching
            }
            val ranges = withContext(TransferRuntime.inbound) {
                if (prepare && !ActiveSegmentWriters.isActive(target.partPath)) {
                    RangeLedger.prepare(target.partPath, target.totalSize)
                } else {
                    RangeLedger.read(target.partPath, target.totalSize)
                }
            }
            call.respondText(
                text = server.json.encodeToString(
                    SegmentStateResponse.serializer(),
                    SegmentStateResponse(
                        complete = false,
                        ranges = ranges,
                        inFlightBytes = InFlightSegmentBytes.sum(target.partPath)
                    )
                ),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/files/segments failed", error)
            call.respond(HttpStatusCode.InternalServerError, "segments_failed")
        }
    }

    post("/api/v1/files/upload-segment") {
        runCatching {
            val target = resolveSegmentTarget(server, call) ?: return@runCatching
            val cancelKey = target.txId.ifBlank { target.partPath }
            if (ReceiverCancelRegistry.isCancelled(cancelKey)) {
                call.respond(HttpStatusCode.Gone, ReceiverCancelRegistry.CANCELLED_BODY)
                return@runCatching
            }
            val offset = call.request.queryParameters[TransferResumeProtocol.OFFSET_QUERY]?.toLongOrNull() ?: -1L
            val length = call.request.queryParameters[TransferResumeProtocol.LENGTH_QUERY]?.toLongOrNull() ?: -1L
            val sessionLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (offset < 0L || length <= 0L || offset + length > target.totalSize ||
                (sessionLength != null && sessionLength != length)
            ) {
                call.respond(HttpStatusCode.BadRequest, "segment_range_invalid")
                return@runCatching
            }
            val channel = call.receiveChannel()
            ActiveSegmentWriters.enter(target.partPath)
            com.fileapex.domain.transfer.TransferActivityGuard.beginTransfer(fileName = target.fileName)
            val receivedOrCancelled = try {
                receiveCancelable(cancelKey) {
                    server.receiveSegmentBytes(channel, target.partPath, target.totalSize, offset, length)
                }
            } finally {
                com.fileapex.domain.transfer.TransferActivityGuard.endTransfer()
                ActiveSegmentWriters.exit(target.partPath)
            }
            if (receivedOrCancelled == null) {
                discardCancelledPart(target.partPath)
                call.respond(HttpStatusCode.Gone, ReceiverCancelRegistry.CANCELLED_BODY)
                return@runCatching
            }
            val received: Long = receivedOrCancelled
            if (received != length) {
                server.onLog(
                    "segment paused path=${target.partPath} offset=$offset got=$received want=$length",
                    null
                )
                call.respond(HttpStatusCode.BadRequest, "segment_incomplete")
                return@runCatching
            }
            call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
        }.onFailure { error ->
            server.onLog("POST /api/v1/files/upload-segment failed", error)
            call.respond(HttpStatusCode.InternalServerError, "segment_failed")
        }
    }

    post("/api/v1/files/upload-complete") {
        runCatching {
            val target = resolveSegmentTarget(server, call) ?: return@runCatching
            val txTimestamp = call.request.queryParameters[TransferResumeProtocol.TIMESTAMP_QUERY]?.toLongOrNull()
                ?: com.fileapex.util.TimeUtils.now()
            val finalPath = segmentFinalizeMutex.withLock {
                if (target.txId.isNotBlank() &&
                    TransferTransactionJournal.findCompleted(target.txId, target.senderId, target.totalSize) != null
                ) {
                    return@withLock ""
                }
                withContext(TransferRuntime.inbound) {
                    val ranges = RangeLedger.read(target.partPath, target.totalSize)
                    val onDisk = SocketFileStreamer.fileLength(target.partPath)
                    if (!TransferRanges.covers(ranges, target.totalSize) || onDisk != target.totalSize) {
                        null
                    } else {
                        val finalized = SocketFileStreamer.finalizePart(target.partPath, target.resolvedPath)
                        RangeLedger.delete(target.partPath)
                        if (target.txId.isNotBlank()) {
                            TransferTransactionJournal.recordCompleted(
                                transactionId = target.txId,
                                senderDeviceId = target.senderId,
                                targetPath = target.resolvedPath,
                                finalPath = finalized,
                                byteSize = target.totalSize,
                                timestampEpochMs = txTimestamp
                            )
                        }
                        finalized
                    }
                }
            }
            when {
                finalPath == null -> {
                    call.respond(HttpStatusCode.Conflict, "segments_missing")
                }
                finalPath.isEmpty() -> {
                    call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.Created)
                }
                else -> {
                    server.onLog(
                        "TransferLog: [txId=${target.txId}] sender=${target.senderId} path=$finalPath " +
                            "bytes=${target.totalSize} timestamp=$txTimestamp segmented=true",
                        null
                    )
                    call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.Created)
                    announceReceivedFile(server, finalPath, target.txId, txTimestamp, target.senderId)
                }
            }
        }.onFailure { error ->
            server.onLog("POST /api/v1/files/upload-complete failed", error)
            call.respond(HttpStatusCode.InternalServerError, "complete_failed")
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

private class SegmentTarget(
    val resolvedPath: String,
    val partPath: String,
    val totalSize: Long,
    val txId: String,
    val senderId: String,
    val fileName: String
)

private val segmentFinalizeMutex = Mutex()

/** Parts with an upload-segment request still writing; prepare must not delete them. */
private object ActiveSegmentWriters {
    private val counts = HashMap<String, Int>()

    fun enter(partPath: String) = synchronized(counts) {
        counts[partPath] = (counts[partPath] ?: 0) + 1
    }

    fun exit(partPath: String) = synchronized(counts) {
        val next = (counts[partPath] ?: 1) - 1
        if (next <= 0) counts.remove(partPath) else counts[partPath] = next
    }

    fun isActive(partPath: String): Boolean = synchronized(counts) { counts.containsKey(partPath) }
}

private suspend fun resolveSegmentTarget(server: FileApexServer, call: ApplicationCall): SegmentTarget? {
    val preferred = call.request.queryParameters["targetPath"]?.takeIf { it.isNotBlank() }
    val totalSize = call.request.queryParameters[TransferResumeProtocol.TOTAL_SIZE_QUERY]?.toLongOrNull()
    if (preferred == null || totalSize == null || totalSize <= 0L) {
        call.respond(HttpStatusCode.BadRequest, "segment_target_invalid")
        return null
    }
    if (!server.isPathAllowed(preferred)) {
        call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
        return null
    }
    val resolved = UniqueFileNames.resolve(preferred)
    if (!server.isPathAllowed(resolved)) {
        call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
        return null
    }
    val senderId = call.request.queryParameters["from"]
        ?: call.request.queryParameters[TransferResumeProtocol.SENDER_DEVICE_ID_QUERY]
        ?: ""
    return SegmentTarget(
        resolvedPath = resolved,
        partPath = SocketFileStreamer.segmentPartPathFor(resolved),
        totalSize = totalSize,
        txId = call.request.queryParameters[TransferResumeProtocol.TRANSACTION_ID_QUERY].orEmpty(),
        senderId = senderId,
        fileName = resolved.substringAfterLast('/').substringAfterLast('\\')
    )
}

private fun announceReceivedFile(
    server: FileApexServer,
    finalPath: String,
    txId: String,
    txTimestamp: Long,
    senderId: String
) {
    val receivedName = finalPath
        .substringAfterLast('/')
        .substringAfterLast('\\')
    if (receivedName.isBlank()) return
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

/**
 * Runs an inbound body write so the receiving user can cancel it from the live banner or queue.
 * Returns null when the user cancelled (the key is then remembered so the sender stops retrying).
 */
private suspend fun <T> receiveCancelable(cancelKey: String, block: suspend () -> T): T? {
    val handle = Job(currentCoroutineContext()[Job])
    val unregister = TransferActivityGuard.registerCancelable(handle)
    try {
        return withContext(handle) { block() }
    } catch (cancelled: CancellationException) {
        if (handle.isCancelled && currentCoroutineContext().isActive) {
            ReceiverCancelRegistry.mark(cancelKey)
            return null
        }
        throw cancelled
    } finally {
        unregister()
        handle.complete()
    }
}

private fun discardCancelledPart(partPath: String) {
    SocketFileStreamer.deleteQuietly(partPath)
    SocketFileStreamer.deleteQuietly(RangeLedger.ledgerPathFor(partPath))
}
