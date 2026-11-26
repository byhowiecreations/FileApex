package com.fileapex.network.routes

import com.fileapex.di.FileApexServices
import com.fileapex.domain.devices.DeviceIconStore
import com.fileapex.domain.devices.DeviceIconSyncPolicy
import com.fileapex.domain.devices.DeviceIconSyncRequest
import com.fileapex.network.FileApexServer
import com.fileapex.platform.decodeImageBytes
import com.fileapex.util.TimeUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Collaborators for [registerDeviceIconRoutes], separated so tests can verify the route with a real sealed store. */
interface DeviceIconSyncHost {
    suspend fun isVerifiedSender(senderDeviceId: String, senderPublicKey: String): Boolean
    suspend fun isKnownTarget(targetDeviceId: String): Boolean
    fun store(): DeviceIconStore
    fun acceptSyncedIcons(): Boolean
    fun nowMs(): Long = TimeUtils.now()

    object Default : DeviceIconSyncHost {
        override suspend fun isVerifiedSender(senderDeviceId: String, senderPublicKey: String): Boolean =
            verifiedPairedSender(senderDeviceId, senderPublicKey) != null

        override suspend fun isKnownTarget(targetDeviceId: String): Boolean {
            val target = FileApexServices.deviceRepository.getDevice(targetDeviceId)
            return target != null && !target.isRemoved
        }

        override fun store(): DeviceIconStore = FileApexServices.deviceIcons
        override fun acceptSyncedIcons(): Boolean = FileApexServices.settings.deviceIconSync.acceptFromPeers.value
    }
}

/**
 * A paired device tells this one which picture to show for another paired device. The sender must be paired here with a
 * matching key, the picture is size limited, decoded and re-encoded (so nothing but pixels is kept), and an older build
 * that has no such route simply answers 404.
 */
internal fun Route.registerDeviceIconRoutes(server: FileApexServer) {
    val host = server.deviceIconHost
    val lastAcceptedFromSender = ConcurrentHashMap<String, Long>()
    post("/api/v1/device-icon") {
        if (!server.isPeerPinAccepted(server.providedPin(call))) {
            call.respond(HttpStatusCode.Forbidden, "pin_required")
            return@post
        }
        try {
            val body = call.receiveBoundedText(DeviceIconSyncPolicy.MAX_BODY_BYTES)
            val request = server.json.decodeFromString(DeviceIconSyncRequest.serializer(), body)
            if (request.senderDeviceId.isBlank() || request.senderPublicKey.isBlank() ||
                !host.isVerifiedSender(request.senderDeviceId, request.senderPublicKey)
            ) {
                call.respond(HttpStatusCode.Forbidden, "unknown_sender")
                return@post
            }
            if (!host.acceptSyncedIcons()) {
                call.respond(HttpStatusCode.Forbidden, "icon_sync_disabled")
                return@post
            }
            val sender = request.senderDeviceId.trim()
            val now = host.nowMs()
            val verdict = DeviceIconSyncPolicy.check(
                request = request,
                targetKnown = host.isKnownTarget(request.targetDeviceId),
                lastAcceptedFromSenderMs = lastAcceptedFromSender[sender],
                nowMs = now
            )
            when (verdict) {
                is DeviceIconSyncPolicy.Verdict.Rejected -> {
                    val status = if (verdict.reason == "rate_limited") HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest
                    call.respond(status, verdict.reason)
                    return@post
                }
                DeviceIconSyncPolicy.Verdict.Clear -> host.store().remove(request.targetDeviceId)
                is DeviceIconSyncPolicy.Verdict.Set -> {
                    val bitmap = withContext(Dispatchers.Default) { decodeImageBytes(verdict.png, 512) }
                    if (bitmap == null || !DeviceIconSyncPolicy.dimensionsOk(bitmap.width, bitmap.height) ||
                        !host.store().save(request.targetDeviceId, bitmap)
                    ) {
                        call.respond(HttpStatusCode.BadRequest, "bad_image")
                        return@post
                    }
                }
            }
            lastAcceptedFromSender[sender] = now
            call.respond(HttpStatusCode.OK, "ok")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (tooLarge: RequestBodyTooLargeException) {
            call.respond(HttpStatusCode.PayloadTooLarge, "too_large")
        } catch (error: Exception) {
            // Request boundary: only the failure is logged, never the picture.
            server.onLog("POST /api/v1/device-icon rejected", error)
            call.respond(HttpStatusCode.BadRequest, "device_icon_invalid")
        }
    }
}
