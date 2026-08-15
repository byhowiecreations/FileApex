package com.fileapex.platform

import androidx.compose.runtime.Composable

/**
 * Incremental Google authorization for Drive (`drive.file` — files FileApex creates).
 * Identity sign-in stays separate.
 */
@Composable
expect fun rememberGoogleDriveAuthLauncher(
    onResult: (granted: Boolean, errorMessage: String?) -> Unit
): () -> Unit
