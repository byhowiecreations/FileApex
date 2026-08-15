package com.fileapex.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.fileapex.cloud.drive.DesktopDriveGrantRuntime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object DesktopDriveOAuthCallbacks {
    private val _codes = MutableSharedFlow<OAuthCodeResult>(extraBufferCapacity = 4)
    val codes: SharedFlow<OAuthCodeResult> = _codes.asSharedFlow()

    fun emit(result: OAuthCodeResult) {
        _codes.tryEmit(result)
    }
}

@Composable
actual fun rememberGoogleDriveAuthLauncher(
    onResult: (granted: Boolean, errorMessage: String?) -> Unit
): () -> Unit {
    LaunchedEffect(Unit) {
        DesktopDriveGrantRuntime.results.collect { (granted, error) ->
            onResult(granted, error)
        }
    }

    return remember {
        {
            if (!DesktopDriveGrantRuntime.startBrowser(force = true)) {
                onResult(false, "Could not open Google Drive authorization")
            }
        }
    }
}
