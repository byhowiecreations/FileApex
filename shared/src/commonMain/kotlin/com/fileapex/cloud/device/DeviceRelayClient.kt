package com.fileapex.cloud.device

import com.fileapex.membership.ChainRecord
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

/** What the relay holds of the account's membership chain: its root genesis hash, and the seq and hash of its head. */
internal data class RelayChainHead(val root: String, val seq: Long, val head: String)

internal interface DeviceRelay {
    suspend fun relayKey(): String?
    suspend fun enrol(deviceId: String, ts: Long, nonce: String, tag: String, clipboardPublicKey: String, records: List<ChainRecord>): RelayAnswer
    suspend fun chainHead(): RelayChainHead?
    /** [startOver]: replace an older setup of this account (needs a Google sign-in from the last five minutes). */
    suspend fun uploadChain(records: List<ChainRecord>, startOver: Boolean = false): RelayAnswer
}

/**
 * Calls the per-device identity endpoints of the FileApex relay (`/api/device/...`): the relay key, enrol and the signed
 * membership chain the relay decides by. Plans: docs/planning/per-device-firebase-identity-plan.md,
 * docs/planning/one-trust-path-plan.md.
 */
internal class DeviceRelayClient(
    private val httpClient: () -> HttpClient,
    private val idToken: suspend () -> String?,
    private val base: String = DEFAULT_BASE
) : DeviceRelay {
    override suspend fun relayKey(): String? = cancellableCatching {
        val response = httpClient().get("$base/key")
        if (response.status.value != 200) {
            com.fileapex.platform.Diagnostics.log("DeviceIdentity: relay key answered ${response.status.value}")
            return@cancellableCatching null
        }
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["key"]?.jsonPrimitive?.contentOrNull
    }.onFailure { com.fileapex.platform.Diagnostics.log("DeviceIdentity: relay key failed ${it::class.simpleName}") }.getOrNull()

    /**
     * [clipboardPublicKey] lets a device with no cloud record enrol (the relay prefers the key in the record), and
     * [records] carry the chain so a device the relay does not know yet is found on it.
     */
    override suspend fun enrol(deviceId: String, ts: Long, nonce: String, tag: String, clipboardPublicKey: String, records: List<ChainRecord>): RelayAnswer =
        post(
            "enrol",
            buildJsonObject {
                put("deviceId", deviceId)
                put("ts", ts)
                put("nonce", nonce)
                put("tag", tag)
                put("clipboardPublicKey", clipboardPublicKey)
                put("records", recordsJson(records))
            }
        )

    override suspend fun chainHead(): RelayChainHead? {
        val token = idToken() ?: return null
        return cancellableCatching {
            val response = httpClient().get("$base/chain") { header(HttpHeaders.Authorization, "Bearer $token") }
            if (response.status.value != 200) return@cancellableCatching null
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            RelayChainHead(
                root = body["root"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                seq = body["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                head = body["head"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.getOrNull()
    }

    override suspend fun uploadChain(records: List<ChainRecord>, startOver: Boolean): RelayAnswer =
        post("chain", buildJsonObject { put("records", recordsJson(records)); if (startOver) put("startOver", true) })

    /** Field by field: [ChainRecord] keeps its companion private, so its generated serializer cannot be looked up from here. */
    internal fun recordsJson(records: List<ChainRecord>): JsonArray = JsonArray(
        records.map { r ->
            buildJsonObject {
                put("seq", r.seq); put("parentHash", r.parentHash); put("kind", r.kind.name); put("actor", r.actor)
                put("actorKey", r.actorKey); put("subject", r.subject); put("subjectKey", r.subjectKey); put("tlsPin", r.tlsPin)
                put("reason", r.reason); put("signature", r.signature)
            }
        }
    )

    private suspend fun post(action: String, body: JsonObject): RelayAnswer {
        val token = idToken() ?: return RelayAnswer.Unreachable.also { com.fileapex.platform.Diagnostics.log("DeviceIdentity: relay $action has no token") }
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
        }.getOrElse { com.fileapex.platform.Diagnostics.log("DeviceIdentity: relay $action failed ${it::class.simpleName}"); RelayAnswer.Unreachable }.also { answer ->
            // Outcome and the relay's short reason only: never a token, an id or a key.
            if (answer !is RelayAnswer.Ok) com.fileapex.platform.Diagnostics.log("DeviceIdentity: relay $action answered $answer")
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://fileapex.pages.dev/api/device"
    }
}
