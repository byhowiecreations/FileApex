package com.fileapex.network.routes

import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.network.FileApexServer
import com.fileapex.platform.collectDeviceDiagnostics
import com.fileapex.platform.collectDeviceDiagnosticsFallback
import com.fileapex.platform.collectFastBatteryDiagnostics
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

internal fun Route.registerDiagnosticRoutes(server: FileApexServer) {
    get("/api/v1/health") {
        call.respondText("ok", ContentType.Text.Plain)
    }

    get("/api/v1/diagnostics") {
        if (!server.isPeerPinAccepted(server.providedPin(call))) {
            call.respond(HttpStatusCode.Forbidden, "pin_required")
            return@get
        }
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { collectDeviceDiagnostics() }
                .getOrElse { error ->
                    server.onLog("GET /api/v1/diagnostics collector failed - returning partial snapshot", error)
                    collectDeviceDiagnosticsFallback()
                }
        }
        runCatching {
            call.respondText(
                text = server.json.encodeToString(PeerDeviceDiagnostics.serializer(), snapshot),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/diagnostics encode failed", error)
            call.respond(HttpStatusCode.InternalServerError, "diagnostics_failed")
        }
    }

    get("/api/v1/battery") {
        if (!server.isPeerPinAccepted(server.providedPin(call))) {
            call.respond(HttpStatusCode.Forbidden, "pin_required")
            return@get
        }
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { collectFastBatteryDiagnostics() }
                .getOrElse {
                    BatteryDiagnostics(chargingState = "Not available")
                }
        }
        runCatching {
            call.respondText(
                text = server.json.encodeToString(BatteryDiagnostics.serializer(), snapshot),
                contentType = ContentType.Application.Json
            )
        }.onFailure { error ->
            server.onLog("GET /api/v1/battery encode failed", error)
            call.respond(HttpStatusCode.InternalServerError, "battery_failed")
        }
    }

    post("/api/v1/device/beep") {
        runCatching {
            com.fileapex.platform.triggerLocalLocatorSound()
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }.onFailure { error ->
            server.onLog("POST /api/v1/device/beep failed", error)
            call.respond(HttpStatusCode.InternalServerError, "beep_failed")
        }
    }

    post("/api/v1/device/alert") {
        runCatching {
            val body = call.receiveText()
            val title = runCatching {
                server.json.parseToJsonElement(body)
            }.getOrNull()?.let { elem ->
                (elem as? kotlinx.serialization.json.JsonObject)?.get("title")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            } ?: "FileApex Alert"
            val content = runCatching {
                server.json.parseToJsonElement(body)
            }.getOrNull()?.let { elem ->
                (elem as? kotlinx.serialization.json.JsonObject)?.get("text")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            } ?: body.ifBlank { "Notification" }

            com.fileapex.platform.notifyDirectAlert(
                sourceDeviceName = title,
                content = content
            )
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }.onFailure { error ->
            server.onLog("POST /api/v1/device/alert failed", error)
            call.respond(HttpStatusCode.InternalServerError, "alert_failed")
        }
    }
}
