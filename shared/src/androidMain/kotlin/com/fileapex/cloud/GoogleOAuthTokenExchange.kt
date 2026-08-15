package com.fileapex.cloud

import com.fileapex.di.FileApexServices
import com.fileapex.shared.BuildConfig
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class GoogleOAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresInSec: Long
)

internal suspend fun exchangeGoogleServerAuthCode(serverAuthCode: String): GoogleOAuthTokenResponse {
    val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    val clientSecret = BuildConfig.GOOGLE_WEB_CLIENT_SECRET
    val body = buildString {
        append("code=").append(URLEncoder.encode(serverAuthCode, Charsets.UTF_8.name()))
        append("&client_id=").append(URLEncoder.encode(clientId, Charsets.UTF_8.name()))
        if (clientSecret.isNotBlank()) {
            append("&client_secret=").append(URLEncoder.encode(clientSecret, Charsets.UTF_8.name()))
        }
        append("&grant_type=authorization_code")
    }
    val response = FileApexServices.httpClient.post("https://oauth2.googleapis.com/token") {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(body)
    }
    if (!response.status.isSuccess()) {
        error("Google token exchange failed (${response.status}): ${response.bodyAsText()}")
    }
    val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    return GoogleOAuthTokenResponse(
        accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        idToken = obj["id_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        expiresInSec = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
    )
}

internal fun emailFromGoogleIdToken(idToken: String): String? {
    val payload = idToken.split('.').getOrNull(1) ?: return null
    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
    val json = runCatching {
        String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull() ?: return null
    val obj = Json.parseToJsonElement(json).jsonObject
    return obj["email"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
}
