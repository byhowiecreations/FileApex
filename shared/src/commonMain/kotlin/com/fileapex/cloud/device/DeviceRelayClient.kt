package com.fileapex.cloud.device

import com.fileapex.util.cancellableCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal sealed interface RelayAnswer {
    data class Ok(val body: JsonObject) : RelayAnswer
    data class Refused(val status: Int, val reason: String) : RelayAnswer
    data object Unreachable : RelayAnswer
}

internal data class AdmitEntry(val deviceId: String, val publicKeyHash: String, val version: Long)

internal data class RevokeEntry(val deviceId: String, val version: Long)

internal interface DeviceRelay {
    suspend fun relayKey(): String?
    suspend fun enrol(deviceId: String, ts: Long, nonce: String, tag: String): RelayAnswer
    suspend fun sync(admit: List<AdmitEntry>, revoke: List<RevokeEntry>): RelayAnswer
}

/**
 * Calls the per-device identity endpoints of the FileApex relay (`/api/device/...`): the relay key, enrol, admit and
 * revoke. Plan: docs/planning/per-device-firebase-identity-plan.md.
 */
internal class DeviceRelayClient(
    private val httpClient: () -> HttpClient,
    private val idToken: suspend () -> String?,
    private val base: String = DEFAULT_BASE
) : DeviceRelay {
    override suspend fun relayKey(): String? = cancellableCatching {
        val response = httpClient().get("$base/key")
        if (response.status.value != 200) return@cancellableCatching null
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["key"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    override suspend fun enrol(deviceId: String, ts: Long, nonce: String, tag: String): RelayAnswer =
        post(
            "enrol",
            buildJsonObject {
                put("deviceId", deviceId)
                put("ts", ts)
                put("nonce", nonce)
                put("tag", tag)
            }
        )

    override suspend fun sync(admit: List<AdmitEntry>, revoke: List<RevokeEntry>): RelayAnswer =
        post(
            "sync",
            buildJsonObject {
                put(
                    "admit",
                    JsonArray(
                        admit.map {
                            buildJsonObject { put("deviceId", it.deviceId); put("publicKeyHash", it.publicKeyHash); put("version", it.version) }
                        }
                    )
                )
                put("revoke", JsonArray(revoke.map { buildJsonObject { put("deviceId", it.deviceId); put("version", it.version) } }))
            }
        )

    private suspend fun post(action: String, body: JsonObject): RelayAnswer {
        val token = idToken() ?: return RelayAnswer.Unreachable
        return cancellableCatching {
            val response = httpClient().post("$base/$action") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val text = response.bodyAsText()
            val parsed = cancellableCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            if (response.status.value == 200) {
                RelayAnswer.Ok(parsed)
            } else {
                RelayAnswer.Refused(response.status.value, parsed["error"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }.getOrElse { RelayAnswer.Unreachable }
    }

    companion object {
        const val DEFAULT_BASE = "https://fileapex.pages.dev/api/device"
    }
}
