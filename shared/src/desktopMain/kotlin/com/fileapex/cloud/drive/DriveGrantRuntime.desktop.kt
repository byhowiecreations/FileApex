package com.fileapex.cloud.drive

import com.fileapex.cloud.DesktopAuthCoordinator
import com.fileapex.cloud.desktopOAuthClientId
import com.fileapex.di.FileApexServices
import com.fileapex.platform.DesktopDriveOAuthCallbacks
import com.fileapex.platform.DriveRelayNotifier
import com.fileapex.platform.OAuthCodeResult
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object DesktopDriveGrantRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installed = AtomicBoolean(false)
    private val browserInFlight = AtomicBoolean(false)
    private val _results = MutableSharedFlow<Pair<Boolean, String?>>(extraBufferCapacity = 4)
    val results: SharedFlow<Pair<Boolean, String?>> = _results.asSharedFlow()

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        scope.launch {
            DesktopDriveOAuthCallbacks.codes.collect { result ->
                browserInFlight.set(false)
                completeGrant(result)
            }
        }
    }

    fun startBrowser(force: Boolean = false): Boolean {
        if (!force && GoogleDriveAuth.hasGrant()) return false
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return false
        if (!browserInFlight.compareAndSet(false, true)) return true
        return runCatching {
            DesktopAuthCoordinator.macGoogleSignInSetupError()?.let { error(it) }
            val clientId = desktopOAuthClientId()
            if (clientId.isBlank()) error("Google OAuth client ID is missing")
            DesktopAuthCoordinator.cancelPending()
            val hint = FileApexServices.settings.googleAccountEmail.value
            val url = DesktopAuthCoordinator.beginDriveAuthorizationUrl(clientId, hint)
            if (!Desktop.isDesktopSupported() ||
                !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) {
                error("No browser available for Google Drive authorization")
            }
            Desktop.getDesktop().browse(URI(url))
            driveLog("opened Drive grant browser for off-LAN send")
            true
        }.getOrElse { error ->
            browserInFlight.set(false)
            driveLogError("could not open Drive grant browser", error)
            false
        }
    }

    private suspend fun completeGrant(result: OAuthCodeResult) {
        if (result.error != null) {
            _results.emit(false to result.error)
            return
        }
        val code = result.code
        if (code.isNullOrBlank()) {
            _results.emit(false to "OAuth callback missing code")
            return
        }
        runCatching {
            DesktopAuthCoordinator.exchangeCodeForDriveTokens(code, result.state)
        }.onSuccess {
            val settings = FileApexServices.settings
            settings.setGoogleDriveRelayEnabled(true)
            DriveRelayNotifier.onDriveEnabledAndGranted()
            DriveRelayCoordinator.applySchedulerFromSettings()
            runCatching { FileApexServices.transferQueue.scheduleDrain() }
            driveLog("Drive grant completed - draining off-LAN queue via Relay")
            _results.emit(true to null)
        }.onFailure { error ->
            driveLogError("Drive grant exchange failed", error)
            _results.emit(false to driveGrantUserMessage(error))
        }
    }
}

actual fun installDriveGrantRuntime() {
    DesktopDriveGrantRuntime.install()
}

actual fun startDriveGrantIfNeeded(): Boolean = DesktopDriveGrantRuntime.startBrowser()
