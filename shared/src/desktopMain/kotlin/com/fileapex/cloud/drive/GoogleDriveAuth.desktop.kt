package com.fileapex.cloud.drive

import com.fileapex.cloud.desktopOAuthClientId
import com.fileapex.cloud.desktopOAuthClientSecret
import com.fileapex.di.FileApexServices
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.util.prefs.Preferences
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

actual object GoogleDriveAuth {
    private val driveNode = Preferences.userRoot().node("com.fileapex.drive")
    private val prefs = com.fileapex.data.settings.DesktopSettingsSecrets.sealedNode(driveNode, setOf(KEY_ACCESS, KEY_REFRESH))

    actual fun hasGrant(): Boolean =
        prefs.getBoolean(KEY_VERIFIED, false) && hasStoredAccess()

    actual fun hasStoredAccess(): Boolean {
        val scope = prefs.getString(KEY_SCOPE, "")
        if (scope.isNotBlank() && scope != DRIVE_FILE_SCOPE) return false
        return prefs.getString(KEY_REFRESH, "").isNotBlank() || prefs.getString(KEY_ACCESS, "").isNotBlank()
    }

    actual suspend fun accessToken(): String {
        val access = prefs.getString(KEY_ACCESS, "")
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val stale = expiresAt > 0L && System.currentTimeMillis() >= expiresAt - 60_000L
        if (access.isNotBlank() && !stale) return access
        if (refreshOnUnauthorized()) {
            return prefs.getString(KEY_ACCESS, "").ifBlank { error("Drive access token missing") }
        }
        error(com.fileapex.i18n.AppI18n.t("drive_signin_expired"))
    }

    actual suspend fun persistGrant(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long
    ) {
        prefs.putString(KEY_ACCESS, accessToken)
        if (refreshToken.isNotBlank()) prefs.putString(KEY_REFRESH, refreshToken)
        prefs.putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
        prefs.putString(KEY_SCOPE, DRIVE_FILE_SCOPE)
        driveNode.flush()
        if (refreshToken.isBlank()) {
            driveLog("persistGrant has no refresh token - Drive posts will fail after the access token expires")
        }
    }

    actual fun markAccessVerified() {
        prefs.putBoolean(KEY_VERIFIED, true)
        runCatching { driveNode.flush() }
    }

    actual suspend fun refreshOnUnauthorized(): Boolean {
        val refresh = prefs.getString(KEY_REFRESH, "")
        if (refresh.isBlank()) return false
        val clientId = desktopOAuthClientId()
        val clientSecret = desktopOAuthClientSecret()
        val body = buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(enc(refresh))
            append("&client_id=").append(enc(clientId))
            if (clientSecret.isNotBlank()) {
                append("&client_secret=").append(enc(clientSecret))
            }
        }
        val response = FileApexServices.httpClient.post("https://oauth2.googleapis.com/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            driveLogError("token refresh failed (${response.status})")
            return false
        }
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return false
        val newRefresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        persistGrant(
            accessToken = access,
            refreshToken = newRefresh.ifBlank { refresh },
            expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1000L
        )
        return true
    }

    actual fun clearGrant() {
        prefs.remove(KEY_ACCESS)
        prefs.remove(KEY_REFRESH)
        prefs.remove(KEY_EXPIRES_AT)
        prefs.remove(KEY_SCOPE)
        prefs.remove(KEY_VERIFIED)
        runCatching { driveNode.flush() }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_epoch_ms"
    private const val KEY_SCOPE = "oauth_scope"
    private const val KEY_VERIFIED = "drive_access_verified"
}
