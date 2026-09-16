package com.fileapex.network.routes

import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardSendRequest
import com.fileapex.domain.clipboard.ClipboardSendResponse
import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexServer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

internal fun Route.registerClipboardRoutes(server: FileApexServer) {
    get("/api/v1/clipboard/status") {
        runCatching {
            val settings = FileApexServices.settings
            val enabled = settings.clipboardSharingEnabled.value
            val identity = server.identityProvider()
            val response = com.fileapex.domain.clipboard.ClipboardStatusResponse(
                sharingEnabled = enabled,
                deviceId = identity.deviceId,
                deviceName = identity.deviceName
            )
            call.respondText(
                text = server.json.encodeToString(com.fileapex.domain.clipboard.ClipboardStatusResponse.serializer(), response),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/clipboard/status failed", error)
            call.respond(HttpStatusCode.InternalServerError, "clipboard_status_failed")
        }
    }

    post("/api/v1/clipboard/opt-in-request") {
        runCatching {
            val body = call.receiveText()
            val request = server.json.decodeFromString(com.fileapex.domain.clipboard.ClipboardOptInRequest.serializer(), body)
            val settings = FileApexServices.settings
            if (!settings.clipboardSharingEnabled.value && !settings.clipboardOptInPromptShown.value) {
                request.pendingPayload?.let {
                    com.fileapex.domain.clipboard.ClipboardPendingOptInStore.setPending(it)
                }
                withContext(Dispatchers.Main) {
                    com.fileapex.platform.notifyClipboardOptInRequested(request.senderDeviceName)
                }
            }
            call.respond(HttpStatusCode.OK, "ok")
        }.onFailure { error ->
            server.onLog("POST /api/v1/clipboard/opt-in-request failed", error)
            call.respond(HttpStatusCode.InternalServerError, "opt_in_request_failed")
        }
    }

    get("/api/v1/clipboard/current") {
        runCatching {
            val settings = FileApexServices.settings
            if (!settings.clipboardSharingEnabled.value) {
                call.respondText("clipboard_disabled", status = HttpStatusCode.Forbidden)
                return@runCatching
            }
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@runCatching
            }
            val text = withContext(Dispatchers.Main) {
                com.fileapex.platform.PlatformClipboard.getSystemClipboardText().orEmpty()
            }
            call.respondText(
                text = """{"status":"ok","content":${server.json.encodeToString(text)}}""",
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/clipboard/current failed", error)
            call.respond(HttpStatusCode.InternalServerError, "clipboard_pull_failed")
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
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@runCatching
            }
            val body = call.receiveText()
            val request = server.json.decodeFromString(ClipboardSendRequest.serializer(), body)
            if (request.ciphertext.isBlank() || request.senderPublicKey.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "clipboard_ciphertext_required")
                return@runCatching
            }
            withContext(Dispatchers.Main) {
                com.fileapex.domain.clipboard.ClipboardShareCoordinator.applyInbound(
                    senderDeviceId = request.senderDeviceId,
                    senderDeviceName = request.senderDeviceName,
                    senderPublicKey = request.senderPublicKey,
                    ciphertext = request.ciphertext,
                    capturedAtEpochMs = request.capturedAtEpochMs
                )
            }
            val response = ClipboardSendResponse(
                status = "ok",
                recipientDeviceName = server.identityProvider().deviceName
            )
            call.respondText(
                text = server.json.encodeToString(ClipboardSendResponse.serializer(), response),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            val message = error.message.orEmpty()
            when {
                message.contains("clipboard_disabled") ->
                    call.respondText("clipboard_disabled", status = HttpStatusCode.Forbidden)
                message.contains("clipboard_expired") ->
                    call.respond(HttpStatusCode.BadRequest, "clipboard_expired")
                message.contains("clipboard_ciphertext_required") ||
                    message.contains("empty_text") ->
                    call.respond(HttpStatusCode.BadRequest, "clipboard_ciphertext_required")
                else -> {
                    server.onLog("POST /api/v1/clipboard/send failed", error)
                    call.respond(HttpStatusCode.InternalServerError, "clipboard_failed")
                }
            }
        }
    }

    get("/") {
        call.respondText(FileApexServer.webShareHtml(), ContentType.Text.Html)
    }

    get("/share") {
        call.respondText(FileApexServer.webShareHtml(), ContentType.Text.Html)
    }

    post("/api/v1/web/send-clipboard") {
        runCatching {
            val body = call.receiveText()
            val jsonObj = server.json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
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

            val devices = withContext(Dispatchers.IO) { server.onListDevices() }
            val targetDevice = devices.firstOrNull { it.deviceId == targetDeviceId }
            if (targetDevice == null) {
                val missing = server.json.encodeToString(AppI18n.t("web_share_target_not_found"))
                call.respondText(
                    """{"status":"error","message":$missing}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
                return@runCatching
            }

            val result = com.fileapex.domain.clipboard.ClipboardShareCoordinator.sendPlaintextToDevice(
                deviceId = targetDeviceId,
                text = text
            )
            val respJson = server.json.encodeToString(ClipboardSendResponse.serializer(), result)
            call.respondText(respJson, ContentType.Application.Json)
        }.onFailure { error ->
            server.onLog("POST /api/v1/web/send-clipboard failed", error)
            val errMsg = error.message ?: AppI18n.t("web_share_failed")
            val safeMsg = server.json.encodeToString(errMsg)
            call.respondText(
                """{"status":"error","message":$safeMsg}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
}
