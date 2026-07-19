package com.omninode.platform

import androidx.compose.runtime.Composable

/**
 * Launches native Google sign-in and returns a Google ID token for Firebase Auth.
 * Android: Credential Manager. Desktop: browser OAuth → omni://oauth-callback.
 */
@Composable
expect fun rememberGoogleSignInLauncher(
    onResult: (idToken: String?, email: String?, errorMessage: String?) -> Unit
): () -> Unit
