package com.fileapex.cloud.device

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads claims out of a Firebase ID token the app already holds; the signature is the server's business. */
internal object IdTokenClaims {
    fun claim(token: String, name: String): String? {
        val payload = token.split('.').getOrNull(1) ?: return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            Json.parseToJsonElement(json).jsonObject[name]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    /** True when [token] belongs to the per-device Firebase user of [deviceId]. */
    fun isDeviceSession(token: String, deviceId: String): Boolean = claim(token, "deviceId") == deviceId
}
