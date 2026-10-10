package com.fileapex.network.routes

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardE2ee
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.domain.notifications.NotificationAction
import com.fileapex.domain.notifications.NotificationCommand
import com.fileapex.domain.notifications.NotificationCommandRequest
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.domain.notifications.NotificationDebugLog
import com.fileapex.domain.notifications.NotificationCompanionGrant
import com.fileapex.domain.notifications.NotificationEventRequest
import com.fileapex.domain.notifications.NotificationInbox
import com.fileapex.domain.notifications.NotificationPayload
import com.fileapex.domain.notifications.NotificationSnapshot
import com.fileapex.domain.notifications.NotificationSnapshotRequest
import com.fileapex.domain.notifications.NotificationSnapshotResponse
import com.fileapex.domain.notifications.NotificationSyncFeature
import com.fileapex.domain.notifications.NotificationSyncStatus
import com.fileapex.domain.notifications.PhoneSettingsPage
import com.fileapex.domain.notifications.PhoneSettingsPromptRequest
import com.fileapex.domain.notifications.sanitized
import com.fileapex.network.FileApexServer
import com.fileapex.platform.dismissPhoneNotifications
import com.fileapex.platform.isNotificationAccessGranted
import com.fileapex.platform.replyToPhoneNotification
import com.fileapex.platform.showDevicePopup
import com.fileapex.platform.showPhoneSettingsPrompt
import com.fileapex.platform.snapshotPhoneNotifications
import com.fileapex.platform.supportsDevicePopups
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_EVENT_BODY_CHARS = 64 * 1024
private const val MAX_FIELD_CHARS = 1_000
private const val MAX_COMMAND_KEYS = 50
private const val MAX_REPLY_CHARS = 4_000

/** The sender must be a device this install paired with, and its key must match the one on record. */
private suspend fun verifiedPairedSender(senderDeviceId: String, senderPublicKey: String?): PairedDeviceEntity? {
    val device = FileApexServices.deviceRepository.getDevice(senderDeviceId.trim()) ?: return null
    if (senderPublicKey == null) return device
    val expected = ClipboardShareCoordinator.resolvePeerPublicKey(device).trim()
    return device.takeIf { expected.isNotEmpty() && expected == senderPublicKey.trim() }
}

internal fun Route.registerNotificationRoutes(server: FileApexServer) {
    get("/api/v1/notifications/status") {
        val status = NotificationSyncStatus(
            supported = NotificationSyncFeature.ENABLED && currentPlatformLabel() == "Android",
            enabled = FileApexServices.settings.notificationBroadcastEnabled.value,
            accessGranted = isNotificationAccessGranted()
        )
        call.respondText(
            text = server.json.encodeToString(NotificationSyncStatus.serializer(), status),
            contentType = ContentType.Application.Json
        )
    }

    post("/api/v1/notifications/event") {
        if (!NotificationSyncFeature.ENABLED) {
            call.respond(HttpStatusCode.NotFound, "notifications_unavailable")
            return@post
        }
        if (!server.isPeerPinAccepted(server.providedPin(call))) {
            call.respond(HttpStatusCode.Forbidden, "pin_required")
            return@post
        }
        val body = call.receiveBoundedText()
        if (body.length > MAX_EVENT_BODY_CHARS) {
            call.respond(HttpStatusCode.PayloadTooLarge, "notification_too_large")
            return@post
        }
        try {
            val request = server.json.decodeFromString(NotificationEventRequest.serializer(), body)
            if (request.ciphertext.isBlank() || request.senderPublicKey.isBlank() || request.senderDeviceId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "notification_ciphertext_required")
                return@post
            }
            if (verifiedPairedSender(request.senderDeviceId, request.senderPublicKey) == null) {
                call.respond(HttpStatusCode.Forbidden, "unknown_sender")
                return@post
            }
            if (!NotificationCompanion.isCompanion(request.senderDeviceId)) {
                NotificationDebugLog.log("event dropped, not companion: from=${NotificationDebugLog.short(request.senderDeviceId)} companion=${NotificationDebugLog.short(NotificationCompanion.desiredDeviceId())}")
                call.respond(HttpStatusCode.Conflict, "not_companion")
                return@post
            }
            val plaintext = withContext(Dispatchers.Default) {
                ClipboardE2ee.decrypt(
                    ciphertextBase64 = request.ciphertext,
                    localDeviceId = loadLocalIdentity().deviceId,
                    peerDeviceId = request.senderDeviceId,
                    peerPublicKeyBase64 = request.senderPublicKey
                ).decodeToString()
            }
            val payload = server.json.decodeFromString(NotificationPayload.serializer(), plaintext)
            NotificationInbox.apply(payload.sanitized().copy(sourceDeviceId = request.senderDeviceId.trim()))
            if (!payload.remove && supportsDevicePopups() && FileApexServices.settings.deviceNotificationPopups.value) {
                showDevicePopup(
                    title = payload.conversation.ifBlank { payload.title }.ifBlank { payload.appLabel },
                    text = payload.lines.lastOrNull() ?: payload.text
                )
            }
            call.respond(HttpStatusCode.OK, "ok")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Request boundary: only the failure is logged, never the notification text.
            server.onLog("POST /api/v1/notifications/event rejected", error)
            call.respond(HttpStatusCode.BadRequest, "notification_invalid")
        }
    }

    post("/api/v1/notifications/command") {
        if (!NotificationSyncFeature.ENABLED || currentPlatformLabel() != "Android") {
            call.respond(HttpStatusCode.NotFound, "notifications_unavailable")
            return@post
        }
        if (!server.isPeerPinAccepted(server.providedPin(call))) {
            call.respond(HttpStatusCode.Forbidden, "pin_required")
            return@post
        }
        val body = call.receiveBoundedText()
        if (body.length > MAX_EVENT_BODY_CHARS) {
            call.respond(HttpStatusCode.PayloadTooLarge, "command_too_large")
            return@post
        }
        try {
            val request = server.json.decodeFromString(NotificationCommandRequest.serializer(), body)
            if (request.ciphertext.isBlank() || request.senderPublicKey.isBlank() || request.senderDeviceId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "command_ciphertext_required")
                return@post
            }
            if (verifiedPairedSender(request.senderDeviceId, request.senderPublicKey) == null) {
                call.respond(HttpStatusCode.Forbidden, "unknown_sender")
                return@post
            }
            val settings = FileApexServices.settings
            if (!settings.notificationBroadcastEnabled.value) {
                call.respond(HttpStatusCode.Conflict, "broadcast_off")
                return@post
            }
            val plaintext = withContext(Dispatchers.Default) {
                ClipboardE2ee.decrypt(
                    ciphertextBase64 = request.ciphertext,
                    localDeviceId = loadLocalIdentity().deviceId,
                    peerDeviceId = request.senderDeviceId,
                    peerPublicKeyBase64 = request.senderPublicKey
                ).decodeToString()
            }
            val command = server.json.decodeFromString(NotificationCommand.serializer(), plaintext)
            val allowed = settings.notificationBroadcastApps.value
            val keys = command.keys.take(MAX_COMMAND_KEYS).map { it.take(MAX_FIELD_CHARS) }
            when (command.action) {
                NotificationAction.DISMISS -> {
                    if (!settings.notificationBroadcastSyncDismissal.value) {
                        call.respond(HttpStatusCode.Conflict, "dismissal_off")
                    } else {
                        dismissPhoneNotifications(keys, allowed)
                        call.respond(HttpStatusCode.OK, "ok")
                    }
                }
                NotificationAction.REPLY -> {
                    val key = keys.singleOrNull()
                    val text = command.text.take(MAX_REPLY_CHARS)
                    if (key == null || text.isBlank() || !replyToPhoneNotification(key, text, allowed)) {
                        call.respond(HttpStatusCode.Conflict, "reply_unavailable")
                    } else {
                        call.respond(HttpStatusCode.OK, "ok")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Request boundary: only the failure is logged, never the reply text.
            server.onLog("POST /api/v1/notifications/command failed", error)
            call.respond(HttpStatusCode.BadRequest, "command_invalid")
        }
    }

    post("/api/v1/notifications/snapshot") {
        if (!NotificationSyncFeature.ENABLED || currentPlatformLabel() != "Android") {
            call.respond(HttpStatusCode.NotFound, "notifications_unavailable")
            return@post
        }
        if (!server.isPeerPinAccepted(server.providedPin(call))) {
            call.respond(HttpStatusCode.Forbidden, "pin_required")
            return@post
        }
        val body = call.receiveBoundedText()
        if (body.length > MAX_EVENT_BODY_CHARS) {
            call.respond(HttpStatusCode.PayloadTooLarge, "snapshot_request_too_large")
            return@post
        }
        try {
            val request = server.json.decodeFromString(NotificationSnapshotRequest.serializer(), body)
            if (request.senderPublicKey.isBlank() || request.senderDeviceId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "snapshot_sender_required")
                return@post
            }
            if (verifiedPairedSender(request.senderDeviceId, request.senderPublicKey) == null) {
                call.respond(HttpStatusCode.Forbidden, "unknown_sender")
                return@post
            }
            val settings = FileApexServices.settings
            if (!settings.notificationBroadcastEnabled.value || !isNotificationAccessGranted()) {
                call.respond(HttpStatusCode.Conflict, "broadcast_off")
                return@post
            }
            if (!NotificationCompanionGrant.allows(request.senderDeviceId)) {
                call.respond(HttpStatusCode.Conflict, "not_companion")
                return@post
            }
            val items = withContext(Dispatchers.Default) {
                snapshotPhoneNotifications(settings.notificationBroadcastApps.value)
            }
            val plaintext = server.json.encodeToString(NotificationSnapshot.serializer(), NotificationSnapshot(items))
            val ciphertext = withContext(Dispatchers.Default) {
                ClipboardE2ee.encrypt(
                    plaintext = plaintext.encodeToByteArray(),
                    localDeviceId = loadLocalIdentity().deviceId,
                    peerDeviceId = request.senderDeviceId,
                    peerPublicKeyBase64 = request.senderPublicKey
                )
            }
            call.respondText(
                text = server.json.encodeToString(NotificationSnapshotResponse.serializer(), NotificationSnapshotResponse(ciphertext)),
                contentType = ContentType.Application.Json
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Request boundary: only the failure is logged, never notification content.
            server.onLog("POST /api/v1/notifications/snapshot failed", error)
            call.respond(HttpStatusCode.BadRequest, "snapshot_invalid")
        }
    }

    post("/api/v1/notifications/prompt") {
        if (!server.isPeerPinAccepted(server.providedPin(call))) {
            call.respond(HttpStatusCode.Forbidden, "pin_required")
            return@post
        }
        val body = call.receiveBoundedText()
        if (body.length > MAX_EVENT_BODY_CHARS) {
            call.respond(HttpStatusCode.PayloadTooLarge, "prompt_too_large")
            return@post
        }
        try {
            val request = server.json.decodeFromString(PhoneSettingsPromptRequest.serializer(), body)
            if (verifiedPairedSender(request.senderDeviceId, senderPublicKey = null) == null) {
                call.respond(HttpStatusCode.Forbidden, "unknown_sender")
                return@post
            }
            val allowed = when (request.page) {
                PhoneSettingsPage.BROADCAST_NOTIFICATIONS -> NotificationSyncFeature.ENABLED
                PhoneSettingsPage.CLIPBOARD -> true
                else -> false
            }
            if (!allowed) {
                call.respond(HttpStatusCode.NotFound, "prompt_unavailable")
                return@post
            }
            showPhoneSettingsPrompt(request.page, request.senderDeviceName.take(MAX_FIELD_CHARS))
            call.respond(HttpStatusCode.OK, "ok")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            server.onLog("POST /api/v1/notifications/prompt failed", error)
            call.respond(HttpStatusCode.BadRequest, "prompt_invalid")
        }
    }
}
