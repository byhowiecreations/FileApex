package com.omninode.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.omninode.cloud.DesktopAuthCoordinator
import com.omninode.cloud.googleWebClientId
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object DesktopOAuthCallbacks {
    private val _codes = MutableSharedFlow<OAuthCodeResult>(extraBufferCapacity = 4)
    val codes: SharedFlow<OAuthCodeResult> = _codes.asSharedFlow()

    fun emit(result: OAuthCodeResult) {
        _codes.tryEmit(result)
    }
}

data class OAuthCodeResult(
    val code: String?,
    val state: String?,
    val error: String?
)

@Composable
actual fun rememberGoogleSignInLauncher(
    onResult: (idToken: String?, email: String?, errorMessage: String?) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        DesktopOAuthCallbacks.codes.collect { result ->
            if (result.error != null) {
                onResult(null, null, result.error)
                return@collect
            }
            val code = result.code
            if (code.isNullOrBlank()) {
                onResult(null, null, "OAuth callback missing code")
                return@collect
            }
            runCatching {
                DesktopAuthCoordinator.exchangeCodeForIdToken(code, result.state)
            }.onSuccess { idToken ->
                onResult(idToken, null, null)
            }.onFailure { error ->
                onResult(null, null, error.message ?: "Token exchange failed")
            }
        }
    }

    return remember {
        {
            val clientId = googleWebClientId()
            if (clientId.isBlank()) {
                onResult(
                    null,
                    null,
                    "Set omninode.google.web.client.id in gradle.properties (Google Web OAuth client ID)"
                )
            } else {
                scope.launch {
                    runCatching {
                        val url = DesktopAuthCoordinator.beginAuthorizationUrl(clientId)
                        if (!Desktop.isDesktopSupported() ||
                            !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                        ) {
                            error("No browser available for Google sign-in")
                        }
                        Desktop.getDesktop().browse(URI(url))
                    }.onFailure { error ->
                        onResult(null, null, error.message ?: "Could not open browser")
                    }
                }
            }
        }
    }
}
