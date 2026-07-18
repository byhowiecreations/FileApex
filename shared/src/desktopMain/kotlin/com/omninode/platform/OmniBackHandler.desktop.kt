package com.omninode.platform

import androidx.compose.runtime.Composable

@Composable
actual fun OmniBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop uses window chrome / Esc; system predictive-back is Android-only.
}
