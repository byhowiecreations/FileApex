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

/**
 * Chain push and pull. Records are signed, so what arrives is checked on its own; the caller is still required to be a
 * pinned peer for now (a device behind a changed TLS key fetches through a signed-record route in a later step).
 */
internal fun Route.registerChainRoutes(server: FileApexServer) {
    post("/api/v1/membership/chain") {
        cancellableCatching {
            val from = server.bridgedPeer(call)?.deviceId.orEmpty()
            val hub = FileApexServices.membershipHub
            when {
                from.isEmpty() -> call.respond(HttpStatusCode.Unauthorized, "authentication_required")
                hub == null -> call.respond(HttpStatusCode.ServiceUnavailable, "membership_unavailable")
                else -> {
                    val body = call.receiveBoundedText(limit = MAX_CHAIN_BODY)
                    call.respondText(ChainRouteHandlers.push(hub.chainReplicator, from, body, server.json), ContentType.Application.Json)
                }
            }
        }.onFailure { error ->
            server.onLog("POST /api/v1/membership/chain failed", error)
            call.respond(HttpStatusCode.BadRequest, "chain_push_failed")
        }
    }
    post("/api/v1/membership/chain/join") {
        cancellableCatching {
            val from = server.bridgedPeer(call)?.deviceId.orEmpty()
            val hub = FileApexServices.membershipHub
            when {
                from.isEmpty() -> call.respond(HttpStatusCode.Unauthorized, "authentication_required")
                hub == null -> call.respond(HttpStatusCode.ServiceUnavailable, "membership_unavailable")
                else -> {
                    val body = call.receiveBoundedText(limit = MAX_CHAIN_BODY)
                    call.respondText(ChainRouteHandlers.join(hub.chainTick, from, server.bridgedPeer(call)?.pin.orEmpty(), body, hub.chainRoster(), server.json), ContentType.Application.Json)
                }
            }
        }.onFailure { error ->
            server.onLog("POST /api/v1/membership/chain/join failed", error)
            call.respond(HttpStatusCode.BadRequest, "chain_join_failed")
        }
    }
    get("/api/v1/membership/chain") {
        cancellableCatching {
            val from = server.bridgedPeer(call)?.deviceId.orEmpty()
            val hub = FileApexServices.membershipHub
            when {
                from.isEmpty() -> call.respond(HttpStatusCode.Unauthorized, "authentication_required")
                hub == null -> call.respond(HttpStatusCode.ServiceUnavailable, "membership_unavailable")
                else -> call.respondText(ChainRouteHandlers.pull(hub.chainReplicator, server.json, withProfiles = server.bridgedPeer(call)?.revokedOnly != true, since = call.request.queryParameters["since"].orEmpty()), ContentType.Application.Json)
            }
        }.onFailure { error ->
            server.onLog("GET /api/v1/membership/chain failed", error)
            call.respond(HttpStatusCode.InternalServerError, "chain_pull_failed")
        }
    }
}

private const val MAX_CHAIN_BODY = 512L * 1024
