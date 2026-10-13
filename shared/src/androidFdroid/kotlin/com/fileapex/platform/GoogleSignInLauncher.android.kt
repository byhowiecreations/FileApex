package com.fileapex.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberGoogleSignInLauncher(
    onResult: (idToken: String?, email: String?, errorMessage: String?) -> Unit
): () -> Unit = { onResult(null, null, "Google sign-in is not part of the F-Droid build") }
