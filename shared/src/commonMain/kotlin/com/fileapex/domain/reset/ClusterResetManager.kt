package com.fileapex.domain.reset

import com.fileapex.di.FileApexServices
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

object ClusterResetManager {
    private val outcomeFlow = MutableStateFlow<Boolean?>(null)

    /** Result of a wipe that finished inside the running app: true wiped, false incomplete; null when none is pending. */
    val outcome: StateFlow<Boolean?> = outcomeFlow.asStateFlow()

    private val runningFlow = MutableStateFlow(false)

    /** True from the moment a wipe starts until its result is known; the first-run questions wait for it. */
    val inProgress: StateFlow<Boolean> = runningFlow.asStateFlow()

    fun acknowledgeOutcome() {
        outcomeFlow.value = null
    }

    /** NonCancellable: leaving the settings page while the wipe runs must not stop it half done. */
    suspend fun executeLeaveClusterAndReset(): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        runningFlow.value = true
        try {
            runLeaveClusterAndReset()
        } finally {
            runningFlow.value = false
        }
    }

    private suspend fun runLeaveClusterAndReset(): Boolean {
        cancellableCatching { FileApexServices.membershipHub?.chainTick?.leave() }

        // The Tailscale login belongs to the cluster identity being wiped.
        cancellableCatching { com.fileapex.tailscale.TailscaleNodeRuntime.clearForWipe() }
            .onFailure { println("ClusterResetManager: Tailscale was not cleared (${it::class.simpleName})") }

        // Step B: Cryptographic Erasure
        runCatching { eraseCryptographicKeys() }

        // Step C: Database Purge
        cancellableCatching { purgeDatabases() }
        if (ClusterResetPlatform.keepsAppRunning && !rosterIsEmpty()) {
            cancellableCatching { purgeDatabases() }
            if (!rosterIsEmpty()) {
                outcomeFlow.value = false
                return false
            }
        }

        renewTlsKeyAfterWipe(ClusterResetPlatform.keepsAppRunning) { com.fileapex.security.tls.TlsIdentityRenewal.renew() }

        // Step D: State Reset & Restart
        runCatching { resetStateAndRestart() }
        if (ClusterResetPlatform.keepsAppRunning) {
            // A first launch has the share server up; the next pairing code reads its TLS key and port.
            cancellableCatching { ServerLifecycleManager.ensureRunning() }
            outcomeFlow.value = true
        }

        return true
    }

    /** A desktop keeps its TLS key file through the wipe, so it takes a new key; a platform that restarts erased the file with its key store. */
    internal suspend fun renewTlsKeyAfterWipe(keepsAppRunning: Boolean, renew: suspend () -> Unit) {
        if (!keepsAppRunning) return
        cancellableCatching { renew() }.onFailure { println("ClusterResetManager: no new TLS key (${it::class.simpleName})") }
    }

    private suspend fun rosterIsEmpty(): Boolean =
        cancellableCatching { FileApexServices.deviceRepository.listDevices().isEmpty() }.getOrDefault(false)

    private fun eraseCryptographicKeys() {
        runCatching { FileApexServices.settings.setClipboardPrivateKeyBase64("") }
        runCatching { FileApexServices.settings.setDiagnosticsPrivateKeyBase64("") }
        ClusterResetPlatform.eraseKeystoreAndSecureStorage()
        forgetHeldKeys()
    }

    /** The cluster key is also cached in memory; a platform that keeps running after the wipe must not keep using it. */
    internal fun forgetHeldKeys() {
        runCatching { com.fileapex.cloud.seal.ClusterRecordKeys.forget() }
    }

    private suspend fun purgeDatabases() {
        FileApexServices.purgeDatabases()
        DeviceSessionManager.clearAllSessions()
        FileApexServices.presenceMonitor.clearOnlineSnapshot()
    }

    private fun resetStateAndRestart() {
        runCatching { FileApexServices.settings.resetToDefaults() }
        runCatching { FileApexServices.settings.markThemeDefaultDecided() }
        cancellableCatching { ServerLifecycleManager.stop() }
        ClusterResetPlatform.stateResetAndRestart()
    }
}
