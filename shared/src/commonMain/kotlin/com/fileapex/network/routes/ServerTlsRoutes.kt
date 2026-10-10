package com.fileapex.network.routes

import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexServer
import com.fileapex.security.tls.TlsAnnouncementOutcome
import com.fileapex.security.tls.TlsPinAnnouncement
import com.fileapex.security.tls.TlsPinAnnouncements
import com.fileapex.util.TimeUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

internal fun Route.registerTlsRoutes(server: FileApexServer) {
    post("/api/v1/tls/pin") {
        runCatching {
            val body = call.receiveBoundedText(limit = 16L * 1024)
            val announcement = server.json.decodeFromString(TlsPinAnnouncement.serializer(), body)
            val repository = FileApexServices.deviceRepository
            val localId = server.identityProvider().deviceId
            val now = TimeUtils.now()
            when (TlsPinAnnouncements.accept(repository, announcement, localId, now)) {
                TlsAnnouncementOutcome.UnknownPeer,
                TlsAnnouncementOutcome.SenderKeyMismatch -> call.respond(HttpStatusCode.Forbidden, "tls_pin_not_trusted")
                TlsAnnouncementOutcome.Undecryptable,
                TlsAnnouncementOutcome.Invalid -> call.respond(HttpStatusCode.BadRequest, "tls_pin_invalid")
                else -> {
                    val peer = repository.getDevice(announcement.senderDeviceId)
                    val reply = peer?.let { TlsPinAnnouncements.build(localId, it, now) }
                    if (reply == null) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respondText(
                            text = server.json.encodeToString(TlsPinAnnouncement.serializer(), reply),
                            contentType = ContentType.Application.Json
                        )
                    }
                }
            }
        }.onFailure { error ->
            server.onLog("POST /api/v1/tls/pin failed", error)
            call.respond(HttpStatusCode.InternalServerError, "tls_pin_failed")
        }
    }
}
