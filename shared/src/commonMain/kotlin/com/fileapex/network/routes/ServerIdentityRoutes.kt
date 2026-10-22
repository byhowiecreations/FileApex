package com.fileapex.network.routes

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.network.FileApexServer
import com.fileapex.network.RenameDeviceRequest
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
            call.respond(HttpStatusCode.OK)
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

    // The removal records this node holds, for a peer whose fingerprint differs from ours (see RemovalFingerprint).
    get("/api/v1/devices/removed") {
        cancellableCatching {
            val records = withContext(Dispatchers.IO) {
                server.removalRecords()
            }
            call.respondText(
                text = server.json.encodeToString(
                    ListSerializer(com.fileapex.domain.pairing.RemovedDeviceRecord.serializer()),
                    records
                ),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/devices/removed failed", error)
            call.respond(HttpStatusCode.InternalServerError, "removed_failed")
        }
    }

    get("/api/v1/cluster/introductions") {
        cancellableCatching {
            val recipient = call.request.queryParameters["for"]?.trim().orEmpty()
            if (recipient.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "recipient_required")
                return@cancellableCatching
            }
            val sealed = withContext(Dispatchers.IO) { server.onListIntroductions(recipient) }
            call.respondText(
                text = server.json.encodeToString(
                    ListSerializer(com.fileapex.security.SealedIntroduction.serializer()),
                    sealed
                ),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/cluster/introductions failed", error)
            call.respond(HttpStatusCode.InternalServerError, "introductions_failed")
        }
    }

    post("/api/v1/devices/merge") {
        cancellableCatching {
            if (!server.isPeerPinAccepted(server.providedPin(call))) {
                call.respond(HttpStatusCode.Forbidden, "pin_required")
                return@cancellableCatching
            }
            val body = call.receiveBoundedText()
            if (body.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Empty cluster sync payload")
                return@cancellableCatching
            }
            val request = runCatching {
                server.json.decodeFromString(ClusterSyncRequest.serializer(), body)
            }.getOrElse { decodeError ->
                server.onLog("Invalid cluster sync JSON payload", decodeError)
                call.respond(HttpStatusCode.BadRequest, "Invalid sync payload")
                return@cancellableCatching
            }

            val localId = server.identityProvider().deviceId
            val safeRequest = if (request.removedDevices.any { it.deviceId == localId } &&
                !server.isRosterMember(server.requesterDeviceId(call))
            ) {
                server.onLog("Ignored removal of this device from a sender outside the roster", null)
                request.copy(removedDevices = request.removedDevices.filter { it.deviceId != localId })
            } else {
                request
            }

            withContext(Dispatchers.IO) {
                server.onClusterMerge(safeRequest, server.bridgedPeer(call)?.deviceId)
            }

            val currentRoster = withContext(Dispatchers.IO) {
                server.onListDevices()
            }
            // The sender reads this to learn whether it missed a removal; a peer that proved who it is gets it.
            if (server.authenticatedPeerId(call) != null) {
                runCatching { server.removalFingerprint() }.getOrNull()?.let { value ->
                    call.response.headers.append(com.fileapex.network.PeerHttpHeaders.REMOVALS, value)
                }
            }
            call.respondText(
                text = server.json.encodeToString(
                    ListSerializer(PairedDeviceEntity.serializer()),
                    currentRoster
                ),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        }.onFailure { error ->
            server.onLog("POST /api/v1/devices/merge failed", error)
            call.respond(HttpStatusCode.InternalServerError, "merge_failed")
        }
    }

    val handleClusterRemove: suspend io.ktor.server.application.ApplicationCall.() -> Unit = {
        cancellableCatching {
            val body = receiveBoundedText()
            if (body.isBlank()) {
                respond(HttpStatusCode.BadRequest, "Empty removal payload")
                return@cancellableCatching
            }
            val record = runCatching {
                server.json.decodeFromString(com.fileapex.domain.pairing.RemovedDeviceRecord.serializer(), body)
            }.getOrElse { decodeError ->
                server.onLog("Invalid removal JSON payload", decodeError)
                respond(HttpStatusCode.BadRequest, "Invalid removal payload")
                return@cancellableCatching
            }
            val localId = server.identityProvider().deviceId
            if (record.deviceId == localId) {
                // Only a current roster member may tell this device it was removed: the revocation gate skips this route.
                if (!server.isRosterMember(server.requesterDeviceId(this))) {
                    server.onLog("Cluster revocation for local device ignored (sender is not in the roster)", null)
                    respond(HttpStatusCode.OK, "revocation_ignored")
                    return@cancellableCatching
                }
                val applied = withContext(Dispatchers.IO) {
                    server.onClusterSelfRemoved(record)
                }
                server.onLog(
                    "Cluster revocation for local device " + if (applied) "applied" else "ignored (stale or unversioned)",
                    null
                )
                respond(HttpStatusCode.OK, if (applied) "revoked_acknowledged" else "revocation_ignored")
                return@cancellableCatching
            }

            withContext(Dispatchers.IO) {
                server.onClusterPeerRemoved(record)
            }
            respond(HttpStatusCode.OK)
        }.onFailure { error ->
            server.onLog("POST cluster/remove failed", error)
            respond(HttpStatusCode.InternalServerError, "cluster_remove_failed")
        }
    }

    post("/api/v1/cluster/remove") { call.handleClusterRemove() }
    post("/cluster/remove") { call.handleClusterRemove() }
}
