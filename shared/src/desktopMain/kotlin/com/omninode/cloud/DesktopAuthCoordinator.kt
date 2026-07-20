package com.omninode.cloud

import com.omninode.di.OmniNodeServices
import com.omninode.network.OmniHttpClientFactory
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Desktop OAuth / Google identity surface.
 * Keeps PKCE browser flow out of [CloudAuthBackend] (Firestore + token session SSOT).
 */
object DesktopAuthCoordinator {
    data class PendingAuth(
        val codeVerifier: String,
        val state: String
    )

    @Volatile
    private var pending: PendingAuth? = null

    fun beginAuthorizationUrl(webClientId: String): String {
        val verifier = randomUrlSafe(64)
        val challenge = sha256Base64Url(verifier)
        val state = randomUrlSafe(24)
        pending = PendingAuth(codeVerifier = verifier, state = state)
        return buildString {
            append("https://accounts.google.com/o/oauth2/v2/auth")
            append("?client_id=").append(webClientId.encodeUrl())
            append("&redirect_uri=").append(OAUTH_REDIRECT_URI.encodeUrl())
            append("&response_type=code")
            append("&scope=").append("openid%20email%20profile")
            append("&code_challenge=").append(challenge.encodeUrl())
            append("&code_challenge_method=S256")
            append("&state=").append(state.encodeUrl())
            append("&access_type=online")
            append("&prompt=select_account")
        }
    }

    suspend fun exchangeCodeForIdToken(code: String, state: String?): String {
        val pendingAuth = pending ?: error("No pending OAuth request")
        if (state != null && state != pendingAuth.state) {
            error("OAuth state mismatch")
        }
        pending = null
        val client = OmniNodeServices.httpClient
        val json = OmniHttpClientFactory.defaultJson
        val response = client.post("https://oauth2.googleapis.com/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "code=${code.encodeUrl()}" +
                    "&client_id=${googleWebClientId().encodeUrl()}" +
                    "&redirect_uri=${OAUTH_REDIRECT_URI.encodeUrl()}" +
                    "&grant_type=authorization_code" +
                    "&code_verifier=${pendingAuth.codeVerifier.encodeUrl()}"
            )
        }
        if (!response.status.isSuccess()) {
            error("Token exchange failed (${response.status}): ${response.bodyAsText()}")
        }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return obj["id_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Token response missing id_token")
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8)
}

actual fun googleWebClientId(): String =
    DesktopCloudIds.WEB_CLIENT_ID.ifBlank {
        System.getProperty("omninode.google.web.client.id").orEmpty()
    }

actual fun firebaseApiKey(): String =
    DesktopCloudIds.API_KEY.ifBlank {
        System.getProperty("omninode.firebase.api.key") ?: DEFAULT_API_KEY
    }

actual fun firebaseProjectId(): String =
    DesktopCloudIds.PROJECT_ID.ifBlank {
        System.getProperty("omninode.firebase.project.id") ?: DEFAULT_PROJECT_ID
    }

actual fun currentPlatformLabel(): String = "desktop"

private const val DEFAULT_API_KEY = "REDACTED_API_KEY"
private const val DEFAULT_PROJECT_ID = "omninode-502915"
