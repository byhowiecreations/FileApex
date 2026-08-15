package com.fileapex.cloud.drive

import com.fileapex.di.FileApexServices
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

actual object GoogleDriveClient {
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    private const val CHUNK_BYTES = 256 * 1024 * 8
    private const val DOWNLOAD_CHUNK_BYTES = 512 * 1024
    private const val MAX_RETRIES = 5

    private const val RELAY_FOLDER_NAME = "FileApex Relay"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    private val json = Json { ignoreUnknownKeys = true }
    private var cachedFolderId: String? = null
    private var cachedLogFileId: String? = null

    actual suspend fun uploadResumable(
        localAbsolutePath: String,
        fileName: String,
        mimeType: String
    ): DriveUploadedFile {
        val file = File(localAbsolutePath)
        require(file.isFile) { "File not found: $localAbsolutePath" }
        driveLog("resumable upload file=${file.name} size=${file.length()} path=$localAbsolutePath")
        val size = file.length()
        val hash = sha256Hex(file)
        val sessionUrl = authorized { token ->
            val folderId = resolveRelayFolderId(token)
            initiateResumable(token, fileName, mimeType, size, folderId)
        }
        val remoteId = authorized { token ->
            putChunks(sessionUrl, token, file, size)
        }
        return DriveUploadedFile(id = remoteId, sizeBytes = size, contentHash = hash)
    }

    actual suspend fun downloadToPath(
        driveFileId: String,
        destAbsolutePath: String,
        expectedSizeBytes: Long
    ) {
        val dest = File(destAbsolutePath)
        dest.parentFile?.mkdirs()
        var offset = if (dest.isFile) dest.length() else 0L
        if (expectedSizeBytes > 0L && offset > expectedSizeBytes) {
            dest.delete()
            offset = 0L
        }
        if (expectedSizeBytes > 0L && offset == expectedSizeBytes) {
            driveLog("download already complete bytes=$offset")
            return
        }
        driveLog("download start id=$driveFileId offset=$offset expected=$expectedSizeBytes")
        while (expectedSizeBytes <= 0L || offset < expectedSizeBytes) {
            val chunkEnd = if (expectedSizeBytes > 0L) {
                min(offset + DOWNLOAD_CHUNK_BYTES - 1, expectedSizeBytes - 1)
            } else {
                offset + DOWNLOAD_CHUNK_BYTES - 1
            }
            val wrote = authorized { token ->
                retryOnRateLimit {
                    writeDownloadChunk(token, driveFileId, dest, offset, chunkEnd)
                }
            }
            val next = dest.length()
            if (wrote <= 0L || next <= offset) break
            offset = next
            driveLog("download progress bytes=$offset expected=$expectedSizeBytes")
            if (expectedSizeBytes <= 0L && wrote < DOWNLOAD_CHUNK_BYTES) break
        }
        if (expectedSizeBytes > 0L && dest.length() != expectedSizeBytes) {
            throw DriveHttpException(
                0,
                "incomplete download have=${dest.length()} expected=$expectedSizeBytes"
            )
        }
        driveLog("download complete bytes=${dest.length()}")
    }

    actual suspend fun verifyRelayAccess() {
        authorized { token ->
            retryOnRateLimit {
                resolveRelayFolderId(token)
            }
        }
    }

    actual suspend fun purgeRelayFolder(): Int {
        return authorized { token ->
            val folderId = resolveRelayFolderId(token)
            val ids = listChildrenIds(token, folderId)
            var deleted = 0
            for (id in ids) {
                val response = FileApexServices.httpClient.delete("$FILES_URL/$id") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    driveApiTimeout()
                }
                when (response.status.value) {
                    401 -> throw DriveUnauthorizedException("Drive purge unauthorized")
                    404 -> deleted += 1
                    else -> {
                        if (!response.status.isSuccess()) {
                            throw DriveHttpException(response.status.value, response.bodyAsText())
                        }
                        deleted += 1
                    }
                }
            }
            cachedLogFileId = null
            createLogFile(token)
            deleted
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.driveApiTimeout(
        requestMs: Long = 45_000L,
        connectMs: Long = 15_000L
    ) {
        timeout {
            requestTimeoutMillis = requestMs
            connectTimeoutMillis = connectMs
            socketTimeoutMillis = requestMs
        }
    }
    actual suspend fun deleteFile(driveFileId: String) {
        if (driveFileId.isBlank()) return
        authorized { token ->
            val response = FileApexServices.httpClient.delete("$FILES_URL/$driveFileId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                driveApiTimeout()
            }
            if (response.status.value == 401) throw DriveUnauthorizedException("Drive delete unauthorized")
            if (response.status.value == 404) return@authorized
            if (!response.status.isSuccess()) {
                throw DriveHttpException(response.status.value, response.bodyAsText())
            }
        }
    }

    actual suspend fun loadLedger(ifNoneMatch: String?): DriveLedgerSnapshot {
        return authorized { token ->
            retryOnRateLimit {
                val logId = resolveLogFileId(token)
                val meta = getFileMetadata(token, logId, ifNoneMatch)
                if (meta.status == 304) {
                    return@retryOnRateLimit DriveLedgerSnapshot(
                        ledger = DriveLedger(),
                        etag = ifNoneMatch,
                        logFileId = logId,
                        notModified = true
                    )
                }
                if (meta.status == 404) {
                    cachedLogFileId = null
                    val created = createLogFile(token)
                    return@retryOnRateLimit DriveLedgerSnapshot(
                        DriveLedger(),
                        created.second,
                        created.first
                    )
                }
                val media = FileApexServices.httpClient.get("$FILES_URL/$logId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("alt", "media")
                    driveApiTimeout()
                }
                throwIfDriveFailed(media, "Drive ledger")
                if (media.status.value == 404) {
                    cachedLogFileId = null
                    val created = createLogFile(token)
                    return@retryOnRateLimit DriveLedgerSnapshot(
                        DriveLedger(),
                        created.second,
                        created.first
                    )
                }
                DriveLedgerSnapshot(
                    ledger = DriveLedgerCodec.parse(media.bodyAsText()),
                    etag = meta.etag,
                    logFileId = logId
                )
            }
        }
    }

    actual suspend fun saveLedger(
        ledger: DriveLedger,
        ifMatchEtag: String?
    ): DriveLedgerSnapshot {
        val markdown = DriveLedgerCodec.render(ledger)
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        return authorized { token ->
            retryOnRateLimit {
                val logId = resolveLogFileId(token)
                val match = ifMatchEtag?.takeIf { it.isNotBlank() }
                    ?: getFileMetadata(token, logId, ifNoneMatch = null).etag
                val patch = FileApexServices.httpClient.patch("$UPLOAD_URL/$logId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("uploadType", "media")
                    driveApiTimeout()
                    if (!match.isNullOrBlank()) {
                        header(HttpHeaders.IfMatch, match)
                    }
                    contentType(ContentType.parse("text/markdown"))
                    setBody(bytes)
                }
                when (patch.status.value) {
                    401 -> throw DriveUnauthorizedException("Drive ledger save unauthorized")
                    412 -> throw DriveHttpException(412, "Ledger etag mismatch")
                    429 -> throw DriveHttpException(429, patch.bodyAsText())
                }
                if (!patch.status.isSuccess()) {
                    throw DriveHttpException(patch.status.value, patch.bodyAsText())
                }
                val etag = patch.headers[HttpHeaders.ETag]
                    ?: getFileMetadata(token, logId, ifNoneMatch = null).etag
                DriveLedgerSnapshot(ledger, etag, logId)
            }
        }
    }

    private suspend fun writeDownloadChunk(
        token: String,
        driveFileId: String,
        dest: File,
        offset: Long,
        chunkEnd: Long
    ): Long {
        val response = FileApexServices.httpClient.get("$FILES_URL/$driveFileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("alt", "media")
            driveApiTimeout(requestMs = 90_000L, connectMs = 20_000L)
            header(HttpHeaders.Range, "bytes=$offset-$chunkEnd")
        }
        when (response.status.value) {
            401 -> throw DriveUnauthorizedException("Drive download unauthorized")
            404 -> throw DriveHttpException(404, "Drive file missing")
            416 -> return 0L
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.PartialContent) {
            throw DriveHttpException(response.status.value, response.bodyAsText())
        }
        if (response.status == HttpStatusCode.OK && offset > 0L) {
            dest.delete()
        }
        dest.parentFile?.mkdirs()
        val append = dest.isFile && dest.length() == offset &&
            response.status == HttpStatusCode.PartialContent
        var written = 0L
        java.io.FileOutputStream(dest, append).use { output ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0) {
                    output.write(buffer, 0, read)
                    written += read
                }
            }
        }
        return written
    }

    private suspend fun listChildrenIds(token: String, folderId: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val listed = FileApexServices.httpClient.get(FILES_URL) {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("q", "'$folderId' in parents and trashed=false")
                parameter("pageSize", "100")
                parameter("fields", "files(id),nextPageToken")
                if (!pageToken.isNullOrBlank()) {
                    parameter("pageToken", pageToken)
                }
                driveApiTimeout()
            }
            throwIfDriveFailed(listed, "Drive purge list")
            val obj = json.parseToJsonElement(listed.bodyAsText()).jsonObject
            obj["files"]?.jsonArray.orEmpty().forEach { element ->
                val id = element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                if (!id.isNullOrBlank()) ids += id
            }
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (!pageToken.isNullOrBlank())
        return ids
    }

    private suspend fun resolveRelayFolderId(token: String): String {
        cachedFolderId?.let { return it }
        val existing = findFileId(
            token,
            "name='$RELAY_FOLDER_NAME' and mimeType='$FOLDER_MIME' and trashed=false"
        )
        if (!existing.isNullOrBlank()) {
            cachedFolderId = existing
            return existing
        }
        val metadata = """{"name":"$RELAY_FOLDER_NAME","mimeType":"$FOLDER_MIME"}"""
        val create = FileApexServices.httpClient.post(FILES_URL) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            driveApiTimeout()
            setBody(metadata)
        }
        throwIfDriveFailed(create, "Drive folder create")
        val id = json.parseToJsonElement(create.bodyAsText())
            .jsonObject["id"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: throw DriveHttpException(create.status.value, "Drive folder missing id")
        cachedFolderId = id
        return id
    }

    private suspend fun resolveLogFileId(token: String): String {
        cachedLogFileId?.let { return it }
        val folderId = resolveRelayFolderId(token)
        val existing = findFileId(
            token,
            "name='${DriveLedgerCodec.LOG_FILE_NAME}' and '$folderId' in parents and trashed=false"
        )
        if (!existing.isNullOrBlank()) {
            cachedLogFileId = existing
            return existing
        }
        val created = createLogFile(token)
        return created.first
    }

    private suspend fun createLogFile(token: String): Pair<String, String?> {
        val folderId = resolveRelayFolderId(token)
        val metadata =
            """{"name":"${DriveLedgerCodec.LOG_FILE_NAME}","parents":[${jsonString(folderId)}]}"""
        val create = FileApexServices.httpClient.post(FILES_URL) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            driveApiTimeout()
            setBody(metadata)
        }
        throwIfDriveFailed(create, "Drive create")
        val id = json.parseToJsonElement(create.bodyAsText())
            .jsonObject["id"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: throw DriveHttpException(create.status.value, "Drive create missing file id")
        cachedLogFileId = id
        val seed = DriveLedgerCodec.render(DriveLedger()).toByteArray(Charsets.UTF_8)
        val media = FileApexServices.httpClient.patch("$UPLOAD_URL/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("uploadType", "media")
            driveApiTimeout()
            contentType(ContentType.parse("text/markdown"))
            setBody(seed)
        }
        throwIfDriveFailed(media, "Drive ledger seed")
        val etag = media.headers[HttpHeaders.ETag]
            ?: getFileMetadata(token, id, ifNoneMatch = null).etag
        return id to etag
    }

    private suspend fun findFileId(token: String, query: String): String? {
        val listed = FileApexServices.httpClient.get(FILES_URL) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("q", query)
            parameter("pageSize", "1")
            parameter("fields", "files(id)")
            driveApiTimeout()
        }
        throwIfDriveFailed(listed, "Drive list")
        return json.parseToJsonElement(listed.bodyAsText())
            .jsonObject["files"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private data class DriveMeta(val status: Int, val etag: String?)

    private suspend fun getFileMetadata(
        token: String,
        fileId: String,
        ifNoneMatch: String?
    ): DriveMeta {
        val response = FileApexServices.httpClient.get("$FILES_URL/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("fields", "id,headRevisionId")
            driveApiTimeout()
            if (!ifNoneMatch.isNullOrBlank()) {
                header(HttpHeaders.IfNoneMatch, ifNoneMatch)
            }
        }
        when (response.status.value) {
            401 -> throw DriveUnauthorizedException("Drive metadata unauthorized")
            429 -> throw DriveHttpException(429, response.bodyAsText())
            304 -> return DriveMeta(304, ifNoneMatch)
            404 -> return DriveMeta(404, null)
        }
        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status.value, response.bodyAsText())
        }
        val headerEtag = response.headers[HttpHeaders.ETag]
        val bodyEtag = json.parseToJsonElement(response.bodyAsText())
            .jsonObject["etag"]
            ?.jsonPrimitive
            ?.contentOrNull
        return DriveMeta(response.status.value, headerEtag ?: bodyEtag)
    }

    private suspend fun throwIfDriveFailed(response: HttpResponse, label: String) {
        val status = response.status.value
        if (status == 304 || status == 404) return
        if (response.status.isSuccess()) return
        val body = response.bodyAsText()
        driveLogError("$label HTTP $status ${body.take(180)}")
        if (isDriveAuthFailure(status, body)) {
            throw DriveUnauthorizedException("$label unauthorized")
        }
        if (status == 429) throw DriveHttpException(429, body.take(400))
        throw DriveHttpException(status, body.take(400))
    }

    private fun isDriveAuthFailure(status: Int, body: String): Boolean {
        if (status == 401) return true
        if (status != 403) return false
        return "Invalid Credentials" in body ||
            "invalid_token" in body ||
            "authError" in body ||
            "UNAUTHENTICATED" in body
    }

    private suspend fun <T> retryOnRateLimit(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: DriveHttpException) {
                if (error.status != 429 && error.status !in 500..599) throw error
                attempt += 1
                if (attempt > MAX_RETRIES) throw error
                delay(backoffMs(attempt))
            }
        }
    }

    private suspend fun initiateResumable(
        token: String,
        fileName: String,
        mimeType: String,
        size: Long,
        folderId: String
    ): String {
        val metadata =
            """{"name":${jsonString(fileName)},"parents":[${jsonString(folderId)}]}"""
        val response = FileApexServices.httpClient.post(UPLOAD_URL) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("uploadType", "resumable")
            header("X-Upload-Content-Type", mimeType)
            header("X-Upload-Content-Length", size.toString())
            contentType(ContentType.Application.Json)
            driveApiTimeout()
            setBody(metadata)
        }
        if (isDriveAuthFailure(response.status.value, "")) {
            throw DriveUnauthorizedException("Drive upload init unauthorized")
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            driveLogError("upload init HTTP ${response.status.value} ${body.take(180)}")
            if (isDriveAuthFailure(response.status.value, body)) {
                throw DriveUnauthorizedException("Drive upload init unauthorized")
            }
            throw DriveHttpException(response.status.value, body.take(400))
        }
        return response.headers[HttpHeaders.Location]
            ?: throw DriveHttpException(response.status.value, "Missing resumable session URL")
    }

    private suspend fun putChunks(sessionUrl: String, token: String, file: File, size: Long): String {
        var offset = 0L
        var uploadedId: String? = null
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (offset < size) {
                val toRead = min(CHUNK_BYTES.toLong(), size - offset).toInt()
                raf.seek(offset)
                raf.readFully(buffer, 0, toRead)
                val chunk = buffer.copyOf(toRead)
                val end = offset + toRead - 1
                var attempt = 0
                while (true) {
                    val response = FileApexServices.httpClient.put(sessionUrl) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(HttpHeaders.ContentLength, toRead.toString())
                        header(HttpHeaders.ContentRange, "bytes $offset-$end/$size")
                        contentType(ContentType.Application.OctetStream)
                        driveApiTimeout(requestMs = 120_000L, connectMs = 20_000L)
                        setBody(chunk)
                    }
                    when {
                        isDriveAuthFailure(response.status.value, "") ->
                            throw DriveUnauthorizedException("Drive chunk unauthorized")
                        response.status.value == 308 -> {
                            offset = parseNextOffset(response, offset + toRead)
                            break
                        }
                        response.status.isSuccess() -> {
                            uploadedId = json.parseToJsonElement(response.bodyAsText())
                                .jsonObject["id"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                            offset = size
                            break
                        }
                        response.status.value in 500..599 || response.status.value == 429 -> {
                            attempt += 1
                            if (attempt > MAX_RETRIES) {
                                throw DriveHttpException(response.status.value, response.bodyAsText())
                            }
                            delay(backoffMs(attempt))
                        }
                        else -> {
                            val body = response.bodyAsText()
                            driveLogError("chunk HTTP ${response.status.value} ${body.take(180)}")
                            if (isDriveAuthFailure(response.status.value, body)) {
                                throw DriveUnauthorizedException("Drive chunk unauthorized")
                            }
                            throw DriveHttpException(response.status.value, body.take(400))
                        }
                    }
                }
            }
        }
        return uploadedId ?: resolveUploadedId(token, file.name)
    }

    private fun parseNextOffset(response: HttpResponse, fallback: Long): Long {
        val range = response.headers["Range"] ?: return fallback
        val end = range.substringAfter("-").toLongOrNull() ?: return fallback
        return end + 1
    }

    private suspend fun resolveUploadedId(token: String, fileName: String): String {
        val listed = FileApexServices.httpClient.get(FILES_URL) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("q", "name=${jsonString(fileName)} and trashed=false")
            parameter("orderBy", "createdTime desc")
            parameter("pageSize", "1")
            parameter("fields", "files(id)")
            driveApiTimeout()
        }
        if (listed.status.isSuccess()) {
            val id = json.parseToJsonElement(listed.bodyAsText())
                .jsonObject["files"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.contentOrNull
            if (!id.isNullOrBlank()) return id
        }
        throw DriveHttpException(listed.status.value, "Uploaded Drive file id not found")
    }

    private suspend fun <T> authorized(block: suspend (token: String) -> T): T {
        val first = GoogleDriveAuth.accessToken()
        return try {
            block(first)
        } catch (_: DriveUnauthorizedException) {
            if (!GoogleDriveAuth.refreshOnUnauthorized()) {
                throw DriveUnauthorizedException("Drive token refresh failed")
            }
            block(GoogleDriveAuth.accessToken())
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = 1_000L shl (attempt - 1).coerceAtMost(4)
        return base.coerceAtMost(16_000L)
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
    }

    private fun jsonString(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
