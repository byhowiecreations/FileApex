package com.fileapex.network.routes

import com.fileapex.network.FileApexServer
import com.fileapex.platform.encodeLocalMediaThumbnailJpeg
import com.fileapex.domain.preview.RemoteThumbnailPolicy
import com.fileapex.util.cancellableCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Serves downscaled JPEG thumbnails for authorized image, video, PDF, audio and APK files under shared roots. */
internal fun Route.registerFileThumbnailRoute(server: FileApexServer) {
    get("/api/v1/files/thumbnail") {
        cancellableCatching {
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@cancellableCatching
            }
            val rawPath = call.request.queryParameters["path"].orEmpty().trim()
            if (rawPath.isBlank()) {
                return@cancellableCatching call.respond(HttpStatusCode.BadRequest)
            }
            if (!server.isPathAllowed(rawPath)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@cancellableCatching
            }
            val filePath = Path(rawPath)
            val metadata = SystemFileSystem.metadataOrNull(filePath)
            if (!SystemFileSystem.exists(filePath) || metadata?.isDirectory == true) {
                return@cancellableCatching call.respond(HttpStatusCode.NotFound)
            }
            val requestedEdge = call.request.queryParameters["edge"]?.toIntOrNull()
                ?: RemoteThumbnailPolicy.DEFAULT_EDGE
            val clampedEdge = RemoteThumbnailPolicy.clampEdge(requestedEdge)
            val inferredMime = RemoteThumbnailPolicy.inferMimeType(filePath.name, "")
            if (RemoteThumbnailPolicy.kindOf(filePath.name, inferredMime, metadata?.size ?: 0L) == null) {
                return@cancellableCatching call.respond(HttpStatusCode.NotFound)
            }
            val bytes = withContext(Dispatchers.IO) {
                encodeLocalMediaThumbnailJpeg(rawPath, inferredMime, clampedEdge)
            }
            if (bytes == null || bytes.isEmpty()) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondBytes(bytes = bytes, contentType = ContentType.Image.JPEG)
            }
        }.onFailure { error ->
            server.onLog("GET /api/v1/files/thumbnail failed", error)
            call.respond(HttpStatusCode.InternalServerError, "thumbnail_failed")
        }
    }
}
