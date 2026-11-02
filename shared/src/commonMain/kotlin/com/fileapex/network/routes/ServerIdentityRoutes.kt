package com.fileapex.network.routes

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.network.FileApexServer
import com.fileapex.network.RenameDeviceRequest
import com.fileapex.network.respondRenamed
import com.fileapex.network.TransferTransactionJournal
import com.fileapex.util.cancellableCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

internal fun Route.registerIdentityRoutes(server: FileApexServer) {
    registerSecurePairingRoute(server)
    get("/api/v1/identity") {
        cancellableCatching {
            server.respondSelfPeerState(call)
        }.onFailure { error ->
            server.onLog("GET /api/v1/identity failed", error)
            call.respond(HttpStatusCode.InternalServerError, "identity_failed")
        }
    }

    post("/api/v1/identity/rename") {
        cancellableCatching {
            val body = call.receiveBoundedText()
            val request = server.json.decodeFromString(RenameDeviceRequest.serializer(), body)
            val trimmed = request.deviceName.trim()
            if (server.denyRename(call, request, trimmed)) return@cancellableCatching
            withContext(Dispatchers.IO) {
                com.fileapex.domain.device.DeviceNameCoordinator.applyRemoteRename(
                    assignedName = trimmed,
                    renamedByDeviceId = request.renamedByDeviceId,
                    renamedByDeviceName = request.renamedByDeviceName
                )
            }
            server.onLog("Local device renamed to $trimmed via cluster request", null)
            call.respondRenamed(server.json, trimmed)
        }.onFailure { error ->
            server.onLog("POST /api/v1/identity/rename failed", error)
            call.respond(HttpStatusCode.InternalServerError, "rename_failed")
        }
    }

    post("/api/v1/auth/verify-pin") {
        runCatching {
            if (server.pairingThrottle.hitAndCheck(call.request.local.remoteAddress.trim(), com.fileapex.util.TimeUtils.now())) {
                call.respond(HttpStatusCode.TooManyRequests, "slow_down")
                return@runCatching
            }
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@runCatching
            }
            call.respond(HttpStatusCode.OK)
        }.onFailure { error ->
            server.onLog("POST /api/v1/auth/verify-pin failed", error)
            call.respond(HttpStatusCode.InternalServerError, "verify_pin_failed")
        }
    }

    get("/api/v1/devices") {
        cancellableCatching {
            val devices = withContext(Dispatchers.IO) {
                server.onListDevices()
            }
            call.respondText(
                text = server.json.encodeToString(
                    ListSerializer(PairedDeviceEntity.serializer()),
                    devices
                ),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/devices failed", error)
            call.respond(HttpStatusCode.InternalServerError, "devices_failed")
        }
    }
}
