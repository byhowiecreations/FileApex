package com.fileapex.platform

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.fileapex.cloud.interactiveGoogleIdToken
import kotlinx.coroutines.launch

@Composable
actual fun rememberGoogleSignInLauncher(
    onResult: (idToken: String?, email: String?, errorMessage: String?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context) {
        {
            val activity = context as? Activity
            if (activity == null) {
                onResult(null, null, "Google sign-in requires an Activity context")
            } else {
                scope.launch {
                    runCatching {
                        interactiveGoogleIdToken(activity)
                    }.onSuccess { (idToken, email) ->
                        onResult(idToken, email, null)
                    }.onFailure { error ->
                        onResult(null, null, googleSignInErrorMessage(error))
                    }
                }
            }
        }
    }
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
        raw.contains("canceled", ignoreCase = true) ||
            raw.contains("cancelled", ignoreCase = true) ->
            "Sign-in cancelled"
        else -> raw.ifBlank { "Google sign-in failed" }
    }
}
