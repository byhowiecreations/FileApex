package com.fileapex.platform

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.fileapex.cloud.googleWebClientId
import kotlinx.coroutines.launch

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

@Composable
actual fun rememberGoogleSignInLauncher(
    onResult: (idToken: String?, email: String?, errorMessage: String?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                scope.launch {
                    runCatching {
                        val activity = context as? Activity
                            ?: error("Google sign-in requires an Activity context")
                        val manager = CredentialManager.create(context)
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId(clientId)
                            .setAutoSelectEnabled(false)
                            .build()
                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()
                        val response = try {
                            manager.getCredential(activity, request)
                        } catch (_: NoCredentialException) {
                            val signInOption = GetSignInWithGoogleOption.Builder(clientId).build()
                            val fallback = GetCredentialRequest.Builder()
                                .addCredentialOption(signInOption)
                                .build()
                            manager.getCredential(activity, fallback)
                        }
                        val credential = response.credential
                        if (credential is CustomCredential &&
                            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        ) {
                            val google = GoogleIdTokenCredential.createFrom(credential.data)
                            onResult(google.idToken, google.id, null)
                        } else {
                            onResult(null, null, "Unexpected credential type")
                        }
                    }.onFailure { error ->
                        if (error is GetCredentialCancellationException) {
                            onResult(null, null, "Sign-in cancelled")
                        } else {
                            onResult(null, null, googleSignInErrorMessage(error))
                        }
                    }
                }
            }
        }
    }
}
