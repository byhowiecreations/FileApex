package com.fileapex.network.routes

import com.fileapex.network.FileApexServer
import com.fileapex.network.LocalApiToken
import com.fileapex.network.LocalShareDevice
import com.fileapex.network.LocalShareResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private const val MAX_PATHS = 1000
private const val MAX_TEXT_CHARS = 100_000

@Serializable
private class SendBody(val paths: List<String> = emptyList(), val deviceIds: List<String> = emptyList())

@Serializable
private class BulletinBody(val text: String = "", val paths: List<String> = emptyList())

/** Only the running app's own Finder extensions: this machine, and the token from `local-api.json`. */
private suspend fun ApplicationCall.localCaller(): Boolean {
    if (!WebIntakeGuard.check(this, loopbackOnly = true)) return false
    if (LocalApiToken.matches(request.headers[LocalApiToken.HEADER])) return true
    respond(HttpStatusCode.Unauthorized, "local_token_required")
    return false
}

private fun usableFiles(paths: List<String>): Boolean =
    paths.size <= MAX_PATHS && paths.all { '\u0000' !in it && File(it).let { f -> f.isAbsolute && f.isFile && f.canRead() } }

internal fun Route.registerLocalExtensionRoutes(server: FileApexServer) {
    val api = server.localExtension ?: return

    get("/api/v1/local/devices") {
        if (!call.localCaller()) return@get
        call.respondText(
            server.json.encodeToString(ListSerializer(LocalShareDevice.serializer()), api.devices()),
            ContentType.Application.Json
        )
    }

    post("/api/v1/local/send") {
        if (!call.localCaller()) return@post
        val body = runCatching { server.json.decodeFromString(SendBody.serializer(), call.receiveBoundedText()) }.getOrNull()
        if (body == null || body.paths.isEmpty() || body.deviceIds.isEmpty() || !usableFiles(body.paths)) {
            call.respond(HttpStatusCode.BadRequest, "send_refused")
            return@post
        }
        call.respondText(server.json.encodeToString(LocalShareResult.serializer(), api.send(body.paths, body.deviceIds)), ContentType.Application.Json)
    }

    post("/api/v1/local/bulletin") {
        if (!call.localCaller()) return@post
        val body = runCatching { server.json.decodeFromString(BulletinBody.serializer(), call.receiveBoundedText()) }.getOrNull()
        val text = body?.text?.trim().orEmpty()
        if (body == null || (text.isEmpty() && body.paths.isEmpty()) || text.length > MAX_TEXT_CHARS || !usableFiles(body.paths)) {
            call.respond(HttpStatusCode.BadRequest, "bulletin_refused")
            return@post
        }
        call.respondText(server.json.encodeToString(LocalShareResult.serializer(), api.postBulletin(text, body.paths)), ContentType.Application.Json)
    }
}
