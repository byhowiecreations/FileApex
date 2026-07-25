package com.fileapex.platform

import androidx.compose.runtime.Composable

@Composable
actual fun FileApexBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop uses window chrome / Esc; system predictive-back is Android-only.
}
