package com.fileapex.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberGoogleDriveAuthLauncher(
    onResult: (granted: Boolean, errorMessage: String?) -> Unit
): () -> Unit = { onResult(false, "Google Drive is not part of the F-Droid build") }
