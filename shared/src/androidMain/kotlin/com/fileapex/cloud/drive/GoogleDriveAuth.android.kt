package com.fileapex.cloud.drive

import android.content.Context
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import com.fileapex.shared.BuildConfig
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

actual object GoogleDriveAuth {
    actual fun hasGrant(): Boolean {
        val prefs = prefs() ?: return false
        if (!prefs.getBoolean(KEY_VERIFIED, false)) return false
        return hasStoredAccess()
    }

    actual fun hasStoredAccess(): Boolean {
        val prefs = prefs() ?: return false
        val scope = prefs.getString(KEY_SCOPE, "").orEmpty()
        if (scope.isNotBlank() && scope != DRIVE_FILE_SCOPE) return false
        return !prefs.getString(KEY_REFRESH, "").isNullOrBlank() ||
            !prefs.getString(KEY_ACCESS, "").isNullOrBlank()
    }

    actual suspend fun accessToken(): String {
        val prefs = prefs() ?: error("Drive auth storage not ready")
        val access = prefs.getString(KEY_ACCESS, "").orEmpty()
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val stale = expiresAt > 0L && System.currentTimeMillis() >= expiresAt - 60_000L
        if (access.isNotBlank() && !stale) return access
        if (refreshOnUnauthorized()) {
            return prefs.getString(KEY_ACCESS, "").orEmpty()
                .ifBlank { error("Drive access token missing") }
        }
        error("Google Drive sign-in expired. Tap Grant Access again.")
    }

    actual suspend fun persistGrant(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long
    ) {
        val prefs = prefs() ?: return
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .apply {
                if (refreshToken.isNotBlank()) putString(KEY_REFRESH, refreshToken)
            }
            .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
            .putString(KEY_SCOPE, DRIVE_FILE_SCOPE)
            .apply()
        if (refreshToken.isBlank()) {
            driveLog("persistGrant has no refresh token - silent Google Identity reauth will mint the next access token")
        }
    }

    actual fun markAccessVerified() {
        prefs()?.edit()?.putBoolean(KEY_VERIFIED, true)?.apply()
    }

    actual suspend fun refreshOnUnauthorized(): Boolean {
        if (refreshWithStoredToken()) return true
        return refreshWithGoogleIdentity()
    }

    private suspend fun refreshWithStoredToken(): Boolean {
        val prefs = prefs() ?: return false
        val refresh = prefs.getString(KEY_REFRESH, "").orEmpty()
        if (refresh.isBlank()) {
            driveLog("no Drive refresh token - trying silent Google Identity reauth")
            return false
        }
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        val clientSecret = BuildConfig.GOOGLE_WEB_CLIENT_SECRET
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

    /**
     * Identity Authorization already granted Drive — mint a new access token without UI.
     * Android often never stores a refresh token; this is the working refresh path.
     */
    private suspend fun refreshWithGoogleIdentity(): Boolean {
        val context = androidAppContextOrNull() ?: return false
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) return false
        return runCatching {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .setOptOutIncludingGrantedScopes(true)
                .requestOfflineAccess(clientId, false)
                .build()
            val result = Identity.getAuthorizationClient(context)
                .authorize(request)
                .await()
            if (result.hasResolution()) {
                driveLog("silent Drive reauth needs user consent")
                return@runCatching false
            }
            val access = result.accessToken.orEmpty()
            if (access.isBlank()) {
                driveLog("silent Drive reauth returned no access token")
                return@runCatching false
            }
            val existingRefresh = prefs()?.getString(KEY_REFRESH, "").orEmpty()
            persistGrant(
                accessToken = access,
                refreshToken = existingRefresh,
                expiresAtEpochMs = System.currentTimeMillis() + 3_500_000L
            )
            driveLog("silent Drive reauth stored a fresh access token")
            true
        }.getOrElse { error ->
            driveLogError("silent Drive reauth failed", error)
            false
        }
    }

    actual fun clearGrant() {
        prefs()?.edit()?.clear()?.apply()
    }

    private fun prefs() = androidAppContextOrNull()
        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private const val PREFS_NAME = "fileapex_drive_oauth"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_epoch_ms"
    private const val KEY_SCOPE = "oauth_scope"
    private const val KEY_VERIFIED = "drive_access_verified"
}
