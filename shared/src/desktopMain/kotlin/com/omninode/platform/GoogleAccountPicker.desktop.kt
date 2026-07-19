package com.omninode.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberGoogleAccountPicker(onPicked: (email: String?) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            // Desktop: no system Google account picker; enable without an email for now.
            onPicked("")
        }
    }
}
