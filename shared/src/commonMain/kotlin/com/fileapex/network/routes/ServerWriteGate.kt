package com.fileapex.network.routes

import com.fileapex.network.FileApexServer
import com.fileapex.network.RenameDeviceRequest
import com.fileapex.network.SocketFileStreamer
import com.fileapex.util.PathUtils
import com.fileapex.di.FileApexServices
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * With a Browse PIN set, only the receive folder takes files without it. Writing anywhere else, replacing files
 * (backup) and deleting need the PIN, because a paired device is not the same as someone who knows the PIN.
 */
private fun FileApexServer.writeNeedsPin(absolutePath: String, backup: Boolean): Boolean {
    if (!FileApexServices.settings.pinRequiredEnabled.value) return false
    if (backup) return true
    val inbox = com.fileapex.platform.defaultDownloadsDir()
    if (inbox.isBlank() || PathUtils.normalize(inbox) == "/") return true
    return !PathUtils.isWithinRoot(PathUtils.normalize(absolutePath), inbox)
}

/** Answers 403 pin_required, or 507 when the disk would be left under the reserve, and returns true when the write must not go ahead. */
internal suspend fun FileApexServer.denyWrite(call: ApplicationCall, absolutePath: String, backup: Boolean = false): Boolean {
    if (writeNeedsPin(absolutePath, backup) && !isPeerPinAccepted(providedPin(call))) {
        call.respond(HttpStatusCode.Forbidden, "pin_required")
        return true
    }
    val incoming = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    if (SocketFileStreamer.hasRoomFor(absolutePath, incoming)) return false
    call.respond(HttpStatusCode.InsufficientStorage, "insufficient_storage")
    return true
}

internal fun Route.registerFileDeleteRoute(server: FileApexServer) {
    post("/api/v1/files/delete") {
        runCatching {
            val path = call.request.queryParameters["targetPath"]
                ?: return@runCatching call.respond(HttpStatusCode.BadRequest)
            if (!server.isDeletablePath(path)) {
                call.respond(HttpStatusCode.Forbidden, "Path outside shared root")
                return@runCatching
            }
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@runCatching
            }
            com.fileapex.platform.trashLocalEntriesQuietly(listOf(path))
            server.onLog("Remote delete: 1 item", null)
            call.respondText("ok", io.ktor.http.ContentType.Text.Plain, HttpStatusCode.OK)
        }.onFailure { error ->
            server.onLog("POST /api/v1/files/delete failed", error)
            call.respond(HttpStatusCode.InternalServerError, "delete_failed")
        }
    }
}

/** A rename is only taken from the paired device that proved who it is, under its own id and with a valid name. */
internal suspend fun FileApexServer.denyRename(call: ApplicationCall, request: RenameDeviceRequest, name: String): Boolean {
    val sender = authenticatedPeerId(call)
    val claimedBy = request.renamedByDeviceId.trim()
    val (status, reason) = when {
        sender == null || (claimedBy.isNotEmpty() && claimedBy != sender) -> HttpStatusCode.Forbidden to "peer_identity_mismatch"
        com.fileapex.domain.device.DeviceNameCoordinator.validate(name) != null -> HttpStatusCode.BadRequest to "invalid_name"
        else -> return false
    }
    call.respond(status, reason)
    return true
}
