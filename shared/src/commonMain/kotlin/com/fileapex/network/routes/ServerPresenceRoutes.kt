package com.fileapex.network.routes

import com.fileapex.data.bulletin.BulletinPeerBackoff
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.DigestAnswer
import com.fileapex.domain.presence.PresenceDigestMessage
import com.fileapex.domain.presence.PresenceDigests
import com.fileapex.network.FileApexServer
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

private val digestGap = BulletinPeerBackoff(
    initialDelayMs = PresenceDigests.MIN_ANSWER_GAP_MS,
    maxDelayMs = PresenceDigests.MIN_ANSWER_GAP_MS
)

internal fun Route.registerPresenceRoutes(server: FileApexServer) {
    post("/api/v1/presence/digest") {
        cancellableCatching {
            val body = call.receiveBoundedText(limit = 16L * 1024)
            val message = server.json.decodeFromString(PresenceDigestMessage.serializer(), body)
            val repository = FileApexServices.deviceRepository
            val now = TimeUtils.now()
            val sender = repository.getDevice(message.senderDeviceId)
            if (sender == null || sender.isRemoved) {
                call.respond(HttpStatusCode.Forbidden, "digest_denied")
                return@cancellableCatching
            }
            if (!digestGap.canAttempt(sender.deviceId, now)) {
                call.respond(HttpStatusCode.TooManyRequests, "digest_rate")
                return@cancellableCatching
            }
            digestGap.onFailure(sender.deviceId, now)
            val members = repository.listDevices().filter { !it.isRemoved }.map { it.deviceId }.toSet()
            val answer = PresenceDigests.answer(
                message = message,
                sender = sender,
                localDeviceId = server.identityProvider().deviceId,
                members = members,
                verifiedAtMs = FileApexServices.presenceMonitor.verifiedEpochsSnapshot(),
                nowMs = now
            )
            when (answer) {
                DigestAnswer.Denied -> {
                    server.onLog("presence digest denied for ${sender.deviceId}", null)
                    call.respond(HttpStatusCode.Forbidden, "digest_denied")
                }
                DigestAnswer.Invalid -> {
                    server.onLog("presence digest ask invalid from ${sender.deviceId}", null)
                    call.respond(HttpStatusCode.BadRequest, "digest_invalid")
                }
                is DigestAnswer.Reply -> call.respondText(
                    text = server.json.encodeToString(PresenceDigestMessage.serializer(), answer.message),
                    contentType = ContentType.Application.Json
                )
            }
        }.onFailure { error ->
            server.onLog("POST /api/v1/presence/digest failed", error)
            call.respond(HttpStatusCode.InternalServerError, "digest_failed")
        }
    }
}
