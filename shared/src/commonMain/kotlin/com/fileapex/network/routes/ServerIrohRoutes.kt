package com.fileapex.network.routes

import com.fileapex.iroh.AddressAnswer
import com.fileapex.iroh.IrohAddressMessage
import com.fileapex.iroh.IrohRuntime
import com.fileapex.network.FileApexServer
import com.fileapex.util.cancellableCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

internal fun Route.registerIrohRoutes(server: FileApexServer) {
    post("/api/v1/iroh/address") {
        cancellableCatching {
            val body = call.receiveBoundedText(limit = 8L * 1024)
            val message = server.json.decodeFromString(IrohAddressMessage.serializer(), body)
            when (val answer = IrohRuntime.answerExchange(message)) {
                null -> call.respond(HttpStatusCode.NotFound, "iroh_off")
                AddressAnswer.Denied -> call.respond(HttpStatusCode.Forbidden, "iroh_denied")
                AddressAnswer.Invalid -> call.respond(HttpStatusCode.BadRequest, "iroh_invalid")
                is AddressAnswer.Reply -> call.respondText(
                    text = server.json.encodeToString(IrohAddressMessage.serializer(), answer.message),
                    contentType = ContentType.Application.Json
                )
            }
        }.onFailure { error ->
            server.onLog("POST /api/v1/iroh/address failed", error)
            call.respond(HttpStatusCode.InternalServerError, "iroh_failed")
        }
    }
}
