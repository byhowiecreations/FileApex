package com.fileapex.domain.reset

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        // Step A: Cluster Broadcast to online peers
        cancellableCatching { broadcastClusterRemoval() }

        // The Tailscale login belongs to the cluster identity being wiped.
        cancellableCatching { com.fileapex.tailscale.TailscaleNodeRuntime.clearForWipe() }
            .onFailure { println("ClusterResetManager: Tailscale was not cleared (${it::class.simpleName})") }

        // Step B: Cryptographic Erasure
        runCatching { eraseCryptographicKeys() }

        // Step C: Database Purge
        cancellableCatching { purgeDatabases() }
        cancellableCatching { FileApexServices.deviceRepository.forgetDroppedCandidates() }
        if (ClusterResetPlatform.keepsAppRunning && !rosterIsEmpty()) {
            cancellableCatching { purgeDatabases() }
            if (!rosterIsEmpty()) {
                outcomeFlow.value = false
                return false
            }
        }

        // Step D: State Reset & Restart
        runCatching { resetStateAndRestart() }
        if (ClusterResetPlatform.keepsAppRunning) {
            // A first launch has the share server up; the next pairing code reads its TLS key and port.
            cancellableCatching { ServerLifecycleManager.ensureRunning() }
            outcomeFlow.value = true
        }

        return true
    }

    private suspend fun broadcastClusterRemoval() {
        val identity = runCatching { loadLocalIdentity() }.getOrNull() ?: return
        val localId = identity.deviceId
        val version = FileApexServices.deviceRepository.nextMembershipVersion()
        val hash = com.fileapex.security.IdentityBinding.localHash(localId)
        val removal = RemovedDeviceRecord(
            deviceId = localId,
            publicKeyHash = hash,
            lastKnownIp = "",
            port = identity.sharePort,
            clusterVersion = version,
            removedAt = version,
            membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL
        )
        val peers = cancellableCatching {
            FileApexServices.deviceRepository.listDevices().filter {
                it.deviceId.isNotBlank() && it.deviceId != localId && !it.isRemoved
            }
        }.getOrDefault(emptyList())

        if (peers.isNotEmpty()) {
            coroutineScope {
                peers.map { peer ->
                    async(Dispatchers.IO) {
                        val host = peer.lastKnownIp.trim()
                        if (!NetworkUtils.isUsableLanIpv4(host)) return@async
                        withTimeoutOrNull(2000L) {
                            cancellableCatching {
                                FileApexServices.client.postClusterRemove(
                                    host = host,
                                    port = peer.port,
                                    record = removal
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun rosterIsEmpty(): Boolean =
        cancellableCatching { FileApexServices.deviceRepository.listDevices().isEmpty() }.getOrDefault(false)

    private fun eraseCryptographicKeys() {
        runCatching { FileApexServices.settings.setClipboardPrivateKeyBase64("") }
        runCatching { FileApexServices.settings.setDiagnosticsPrivateKeyBase64("") }
        ClusterResetPlatform.eraseKeystoreAndSecureStorage()
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
