package com.fileapex.platform

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.fileapex.cloud.GoogleIdentityScopes
import com.fileapex.cloud.emailFromGoogleIdToken
import com.fileapex.cloud.exchangeGoogleServerAuthCode
import com.fileapex.cloud.googleWebClientId
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
actual fun rememberGoogleSignInLauncher(
    onResult: (idToken: String?, email: String?, errorMessage: String?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val activity = context as? Activity
        if (activity == null || activityResult.resultCode != Activity.RESULT_OK) {
            onResult(null, null, "Sign-in cancelled")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                val result = Identity.getAuthorizationClient(activity)
                    .getAuthorizationResultFromIntent(activityResult.data)
                completeIdentitySignIn(result)
            }.onSuccess { (idToken, email) ->
                onResult(idToken, email, null)
            }.onFailure { error ->
                onResult(null, null, googleSignInErrorMessage(error))
            }
        }
    }

    return remember(context) {
        {
            val clientId = googleWebClientId()
            if (clientId.isBlank()) {
                onResult(
                    null,
                    null,
                    "Set fileapex.google.web.client.id in gradle.properties (Google Web OAuth client ID)"
                )
            } else {
                val activity = context as? Activity
                if (activity == null) {
                    onResult(null, null, "Google sign-in requires an Activity context")
                } else {
                    scope.launch {
                        runCatching {
                            val request = AuthorizationRequest.builder()
                                .setRequestedScopes(
                                    GoogleIdentityScopes.identity.map { Scope(it) }
                                )
                                .setOptOutIncludingGrantedScopes(true)
                                .requestOfflineAccess(clientId, false)
                                .build()
                            val result = Identity.getAuthorizationClient(activity)
                                .authorize(request)
                                .await()
                            if (result.hasResolution()) {
                                val sender = result.pendingIntent?.intentSender
                                    ?: error("Google sign-in is missing a resolution")
                                resolutionLauncher.launch(
                                    IntentSenderRequest.Builder(sender).build()
                                )
                            } else {
                                val (idToken, email) = completeIdentitySignIn(result)
                                onResult(idToken, email, null)
                            }
                        }.onFailure { error ->
                            onResult(null, null, googleSignInErrorMessage(error))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun completeIdentitySignIn(result: AuthorizationResult): Pair<String, String?> {
    val account = result.toGoogleSignInAccount()
    val fromAccount = account?.idToken.orEmpty()
    if (fromAccount.isNotBlank()) {
        val email = account?.email?.ifBlank { null } ?: emailFromGoogleIdToken(fromAccount)
        return fromAccount to email
    }
    val serverAuthCode = result.serverAuthCode.orEmpty()
    if (serverAuthCode.isBlank()) {
        error("Google authorization returned no ID token")
    }
    val tokens = exchangeGoogleServerAuthCode(serverAuthCode)
    val idToken = tokens.idToken.ifBlank { error("Google token response missing id_token") }
    val email = account?.email?.ifBlank { null } ?: emailFromGoogleIdToken(idToken)
    return idToken to email
}

private fun googleSignInErrorMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("28444") ||
            raw.contains("Developer console is not set up correctly", ignoreCase = true) ->
            "Google Sign-In is not configured for this installed build. In Firebase Console " +
                "(fileapex-22813): Project settings → Android com.fileapex → add your release " +
                "SHA-1 fingerprint, enable Google under Authentication, re-download " +
                "google-services.json to json/, then rebuild and reinstall."
        else -> raw.ifBlank { "Google sign-in failed" }
    }
}
