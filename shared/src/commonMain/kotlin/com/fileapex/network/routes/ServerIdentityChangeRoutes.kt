package com.fileapex.network.routes

import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexServer
import com.fileapex.util.cancellableCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** A device whose id changed asks a member to continue under the new one; the member's user confirms with a code. */
internal fun Route.registerIdentityChangeRoutes(server: FileApexServer) {
    post(BridgeIdentity.CHANGE_PATH) {
        cancellableCatching {
            val peer = server.bridgedPeer(call)
            val inbox = FileApexServices.membershipHub?.identityChanges
            when {
                peer == null || peer.revokedOnly -> call.respond(HttpStatusCode.Unauthorized, "authentication_required")
                inbox == null -> call.respond(HttpStatusCode.ServiceUnavailable, "membership_unavailable")
                else -> {
                    val (status, text) = IdentityChangeRouteHandlers.receive(inbox, peer.deviceId, peer.pin, call.receiveBoundedText(limit = MAX_BODY), server.json)
                    call.respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(status))
                }
            }
        }.onFailure { error ->
            server.onLog("POST ${BridgeIdentity.CHANGE_PATH} failed", error)
            call.respond(HttpStatusCode.BadRequest, "identity_change_failed")
        }
    }
    get(BridgeIdentity.STATUS_PATH) {
        cancellableCatching {
            val peer = server.bridgedPeer(call)
            val inbox = FileApexServices.membershipHub?.identityChanges
            when {
                peer == null || peer.revokedOnly -> call.respond(HttpStatusCode.Unauthorized, "authentication_required")
                inbox == null -> call.respond(HttpStatusCode.ServiceUnavailable, "membership_unavailable")
                else -> call.respondText(
                    IdentityChangeRouteHandlers.status(inbox, call.request.queryParameters["oldId"].orEmpty(), peer.deviceId, call.request.queryParameters["nonce"].orEmpty(), server.json),
                    ContentType.Application.Json
                )
            }
        }.onFailure { error ->
            server.onLog("GET ${BridgeIdentity.STATUS_PATH} failed", error)
            call.respond(HttpStatusCode.InternalServerError, "identity_change_failed")
        }
    }
}

private const val MAX_BODY = 16L * 1024
