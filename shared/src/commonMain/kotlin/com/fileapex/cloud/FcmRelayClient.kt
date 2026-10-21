package com.fileapex.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sends one silent FCM data message through the FileApex relay (`POST /api/wake`), which checks the caller's
 * Firebase ID token, rate limits and holds the Firebase service-account key, so no build carries that key.
 */
internal class FcmRelayClient(
    private val httpClient: () -> HttpClient,
    private val idToken: suspend () -> String?,
    private val endpoint: String = DEFAULT_ENDPOINT
) {
    suspend fun sendDataWake(targetToken: String, data: JsonObject): Boolean {
        val token = idToken() ?: return false
        val body = buildJsonObject {
            put("token", targetToken)
            put(
                "data",
                buildJsonObject {
                    data.forEach { (key, value) -> put(key, (value as? JsonPrimitive)?.content ?: value.toString()) }
                }
            )
        }
        val response = httpClient().post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            println("FcmRelayClient: relay refused the wake (${response.status}) - ${response.bodyAsText().take(120)}")
            return false
        }
        return true
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://fileapex.pages.dev/api/wake"
    }
}
