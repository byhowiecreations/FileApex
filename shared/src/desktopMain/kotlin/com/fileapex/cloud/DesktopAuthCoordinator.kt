package com.fileapex.cloud

import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexHttpClientFactory
import com.fileapex.platform.DesktopOAuthCallbacks
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
 * Uses PKCE + loopback redirect (http://127.0.0.1) per Google native-app policy.
 * Prefer a GCC **Desktop** OAuth client (no client_secret). Web clients require client_secret.
 */
object DesktopAuthCoordinator {
    data class PendingAuth(
        val codeVerifier: String,
        val state: String,
        val redirectUri: String
    )

    @Volatile
    private var pending: PendingAuth? = null

    /** Last loopback redirect used — required as Firebase signInWithIdp requestUri. */
    @Volatile
    private var lastOAuthRedirectUri: String = DESKTOP_OAUTH_LOOPBACK_REDIRECT_URI

    fun oauthRedirectUriForFirebase(): String = lastOAuthRedirectUri

    /** Null when Mac Google Sign-In credentials are present; otherwise a user-facing setup message. */
    fun macGoogleSignInSetupError(): String? {
        if (desktopOAuthClientId().isBlank()) {
            return "Google OAuth client ID is missing — rebuild after adding json/google-services.json"
        }
        if (desktopOAuthClientSecret().isNotBlank()) return null
        if (GeneratedDesktopCloudConfig.DESKTOP_CLIENT_ID.isNotBlank()) return null
        return MAC_OAUTH_SETUP_MESSAGE
    }

    fun beginAuthorizationUrl(oauthClientId: String): String {
        val verifier = randomUrlSafe(64)
        val challenge = sha256Base64Url(verifier)
        val state = randomUrlSafe(24)
        val redirectUri = DesktopOAuthLoopbackServer.start { result ->
            DesktopOAuthCallbacks.emit(result)
        }
        lastOAuthRedirectUri = redirectUri
        pending = PendingAuth(codeVerifier = verifier, state = state, redirectUri = redirectUri)
        return buildString {
            append("https://accounts.google.com/o/oauth2/v2/auth")
            append("?client_id=").append(oauthClientId.encodeUrl())
            append("&redirect_uri=").append(redirectUri.encodeUrl())
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
        val clientId = desktopOAuthClientId()
        val clientSecret = desktopOAuthClientSecret()
        val redirectUri = pendingAuth.redirectUri
        lastOAuthRedirectUri = redirectUri
        pending = null
        val client = FileApexServices.httpClient
        val json = FileApexHttpClientFactory.defaultJson
        val tokenBody = buildString {
            append("code=${code.encodeUrl()}")
            append("&client_id=${clientId.encodeUrl()}")
            if (clientSecret.isNotBlank()) {
                append("&client_secret=${clientSecret.encodeUrl()}")
            }
            append("&redirect_uri=${redirectUri.encodeUrl()}")
            append("&grant_type=authorization_code")
            append("&code_verifier=${pendingAuth.codeVerifier.encodeUrl()}")
        }
        val response = client.post("https://oauth2.googleapis.com/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(tokenBody)
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            if (clientSecret.isBlank() && body.contains("client_secret is missing", ignoreCase = true)) {
                error(MAC_OAUTH_SETUP_MESSAGE)
            }
            error("Token exchange failed (${response.status}): $body")
        }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return obj["id_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Token response missing id_token")
    }

    fun cancelPending() {
        pending = null
        DesktopOAuthLoopbackServer.stop()
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

    private const val MAC_OAUTH_SETUP_MESSAGE =
        "Mac Google Sign-In is not configured. In GCP project fileapex-22813: " +
            "Credentials → Create OAuth client → Desktop app → Download JSON → save in json/ → rebuild. " +
            "See docs/gcp-mac-oauth-setup.md"
}

actual fun googleWebClientId(): String =
    GeneratedDesktopCloudConfig.WEB_CLIENT_ID.trim().ifBlank {
        System.getProperty("fileapex.google.web.client.id").orEmpty()
    }

internal fun googleWebClientSecret(): String =
    System.getenv("FILEAPEX_GOOGLE_WEB_CLIENT_SECRET")?.trim().orEmpty().ifBlank {
        GeneratedDesktopCloudConfig.WEB_CLIENT_SECRET.trim()
    }

/** Mac OAuth client — Desktop client when configured, otherwise Web client (requires client_secret). */
internal fun desktopOAuthClientId(): String =
    GeneratedDesktopCloudConfig.DESKTOP_CLIENT_ID.trim().ifBlank {
        googleWebClientId()
    }

internal fun desktopOAuthClientSecret(): String =
    System.getenv("FILEAPEX_GOOGLE_DESKTOP_CLIENT_SECRET")?.trim().orEmpty().ifBlank {
        GeneratedDesktopCloudConfig.DESKTOP_CLIENT_SECRET.trim()
    }.ifBlank {
        googleWebClientSecret()
    }

actual fun firebaseApiKey(): String =
    GeneratedDesktopCloudConfig.FIREBASE_API_KEY.trim().ifBlank {
        System.getProperty("fileapex.firebase.api.key").orEmpty()
    }

actual fun firebaseProjectId(): String =
    GeneratedDesktopCloudConfig.FIREBASE_PROJECT_ID.trim().ifBlank {
        System.getProperty("fileapex.firebase.project.id").orEmpty()
    }

actual fun currentPlatformLabel(): String = "desktop"
