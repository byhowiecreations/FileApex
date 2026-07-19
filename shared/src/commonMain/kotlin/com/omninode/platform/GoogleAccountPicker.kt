package com.omninode.platform

import androidx.compose.runtime.Composable

/**
 * Returns a function that launches the platform account picker (Android Google accounts).
 * [onPicked] receives the selected email, or null if the user cancelled.
 */
@Composable
expect fun rememberGoogleAccountPicker(onPicked: (email: String?) -> Unit): () -> Unit
