package com.fileapex.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Trades the relay's custom token for this device's own Firebase session; the caller stores the result. */
internal object DesktopDeviceSignIn {
    suspend fun exchange(client: HttpClient, apiKey: String, customToken: String): Pair<String, String> {
        val response = client.post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("token", customToken); put("returnSecureToken", true) }.toString())
        }
        if (!response.status.isSuccess()) error("Firebase device sign-in failed (${response.status})")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val idToken = body["idToken"]?.jsonPrimitive?.contentOrNull ?: error("Firebase response missing idToken")
        val refresh = body["refreshToken"]?.jsonPrimitive?.contentOrNull ?: error("Firebase response missing refreshToken")
        return idToken to refresh
    }
}
