package com.fileapex.platform

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.fileapex.cloud.drive.driveGrantUserMessage
import com.fileapex.cloud.drive.DRIVE_FILE_SCOPE
import com.fileapex.cloud.drive.GoogleDriveAuth
import com.fileapex.cloud.drive.GoogleDriveClient
import com.fileapex.cloud.exchangeGoogleServerAuthCode
import com.fileapex.cloud.googleWebClientId
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "FileApexDriveAuth"

@Composable
actual fun rememberGoogleDriveAuthLauncher(
    onResult: (granted: Boolean, errorMessage: String?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val activity = context as? Activity
        if (activity == null || activityResult.resultCode != Activity.RESULT_OK) {
            onResult(false, "Drive authorization cancelled")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                val result = Identity.getAuthorizationClient(activity)
                    .getAuthorizationResultFromIntent(activityResult.data)
                completeDriveGrant(result)
            }.onSuccess {
                onResult(true, null)
            }.onFailure { error ->
                GoogleDriveAuth.clearGrant()
                Log.e(TAG, "Drive grant after consent failed", error)
                onResult(false, driveGrantUserMessage(error))
            }
        }
    }

    return remember(context) {
        {
            val activity = context as? Activity
            if (activity == null) {
                onResult(false, "Google Drive authorization requires an Activity")
            } else {
                scope.launch {
                    runCatching {
                        val clientId = googleWebClientId()
                        val request = AuthorizationRequest.builder()
                            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                            .setOptOutIncludingGrantedScopes(true)
                            .requestOfflineAccess(clientId, /* forceCodeForRefreshToken */ true)
                            .build()
                        val result = Identity.getAuthorizationClient(activity)
                            .authorize(request)
                            .await()
                        logAuthorizationResult(result)
                        if (result.hasResolution()) {
                            val sender = result.pendingIntent?.intentSender
                                ?: error("Drive authorization is missing a resolution")
                            resolutionLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        } else {
                            completeDriveGrant(result)
                            onResult(true, null)
                        }
                    }.onFailure { error ->
                        GoogleDriveAuth.clearGrant()
                        Log.e(TAG, "Drive authorize failed", error)
                        onResult(false, driveGrantUserMessage(error))
                    }
                }
            }
        }
    }
}

private fun logAuthorizationResult(result: AuthorizationResult) {
    val scopes = result.grantedScopes.orEmpty().joinToString(",") { it.toString() }
    Log.i(
        TAG,
        "authorize hasResolution=${result.hasResolution()} " +
            "hasToken=${!result.accessToken.isNullOrBlank()} " +
            "hasAuthCode=${!result.serverAuthCode.isNullOrBlank()} " +
            "scopes=[$scopes]"
    )
}

private suspend fun completeDriveGrant(result: AuthorizationResult) {
    persistAuthorizationResult(result)
    runCatching {
        GoogleDriveClient.verifyRelayAccess()
    }.getOrElse { error ->
        GoogleDriveAuth.clearGrant()
        throw error
    }
    GoogleDriveAuth.markAccessVerified()
}

private suspend fun persistAuthorizationResult(result: AuthorizationResult) {
    val access = result.accessToken.orEmpty()
    val serverAuthCode = result.serverAuthCode.orEmpty()
    if (serverAuthCode.isNotBlank()) {
        exchangeServerAuthCode(serverAuthCode, access)
        return
    }
    if (access.isBlank()) {
        error("Drive authorization returned no access token")
    }
    GoogleDriveAuth.persistGrant(
        accessToken = access,
        refreshToken = "",
        expiresAtEpochMs = System.currentTimeMillis() + 3_500_000L
    )
}

private suspend fun exchangeServerAuthCode(serverAuthCode: String, fallbackAccess: String) {
    val tokens = runCatching { exchangeGoogleServerAuthCode(serverAuthCode) }.getOrElse { error ->
        if (fallbackAccess.isNotBlank()) {
            GoogleDriveAuth.persistGrant(fallbackAccess, "", System.currentTimeMillis() + 3_500_000L)
            return
        }
        throw error
    }
    val access = tokens.accessToken.ifBlank { fallbackAccess }
    if (access.isBlank()) {
        error("Drive token exchange returned no access token")
    }
    GoogleDriveAuth.persistGrant(
        accessToken = access,
        refreshToken = tokens.refreshToken,
        expiresAtEpochMs = System.currentTimeMillis() + tokens.expiresInSec * 1000L
    )
}
