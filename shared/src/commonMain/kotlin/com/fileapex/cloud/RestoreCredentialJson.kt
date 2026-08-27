package com.fileapex.cloud

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * WebAuthn JSON for Credential Manager restore keys.
 * RP id is Google's restore-credential host so we do not need Digital Asset Links on a FileApex site.
 */
internal object RestoreCredentialJson {
    const val RP_ID = "restore-credential.android.com"
    const val RP_NAME = "FileApex"

    const val USER_ID_MAX_BYTES = 64

    fun creationOptions(userId: String, email: String, challengeB64: String): String {
        val handle = base64Url(userIdPayload(userId, email))
        val name = email.trim().ifBlank { userId }
        return buildJsonObject {
            put("challenge", challengeB64)
            put("timeout", 180_000)
            put("attestation", "none")
            putJsonObject("rp") {
                put("id", RP_ID)
                put("name", RP_NAME)
            }
            putJsonObject("user") {
                put("id", handle)
                put("name", name)
                put("displayName", name)
            }
            putJsonArray("pubKeyCredParams") {
                add(alg(-7))
                add(alg(-257))
            }
            putJsonObject("authenticatorSelection") {
                put("authenticatorAttachment", "platform")
                put("residentKey", "required")
                put("requireResidentKey", true)
                put("userVerification", "required")
            }
        }.toString()
    }

    fun requestOptions(challengeB64: String): String = buildJsonObject {
        put("challenge", challengeB64)
        put("timeout", 180_000)
        put("userVerification", "required")
        put("rpId", RP_ID)
    }.toString()

    fun userIdPayload(uid: String, email: String): ByteArray {
        val trimmedUid = uid.trim()
        val trimmedEmail = email.trim()
        val combined = buildString {
            append(trimmedUid)
            if (trimmedEmail.isNotEmpty()) {
                append('\n')
                append(trimmedEmail)
            }
        }.encodeToByteArray()
        return if (combined.size <= USER_ID_MAX_BYTES) combined else trimmedUid.encodeToByteArray()
    }

    fun userHandleFromAssertion(authenticationResponseJson: String): String? =
        handlePayload(authenticationResponseJson)?.substringBefore('\n')?.trim()?.ifBlank { null }

    fun emailFromAssertion(authenticationResponseJson: String): String? {
        val raw = handlePayload(authenticationResponseJson) ?: return null
        val sep = raw.indexOf('\n')
        if (sep < 0) return null
        return raw.substring(sep + 1).trim().ifBlank { null }
    }

    private fun handlePayload(authenticationResponseJson: String): String? {
        val root = runCatching {
            Json.parseToJsonElement(authenticationResponseJson).jsonObject
        }.getOrNull() ?: return null
        val handleB64 = root.string("userHandle")
            ?: root.obj("response")?.string("userHandle")
            ?: return null
        val raw = decodeBase64Url(handleB64) ?: return null
        return raw.decodeToString().trim().ifBlank { null }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun base64Url(bytes: ByteArray): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64Url(value: String): ByteArray? {
        val stripped = value.trim()
        if (stripped.isEmpty()) return null
        val noPad = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)
        return runCatching { noPad.decode(stripped) }.getOrNull()
            ?: runCatching { padded.decode(stripped) }.getOrNull()
    }

    private fun alg(alg: Int) = buildJsonObject {
        put("type", "public-key")
        put("alg", alg)
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.jsonObject

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
}
