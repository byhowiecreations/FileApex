package com.fileapex.domain.reset

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.DeviceIdentityMarkers
import com.fileapex.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object ClusterResetManager {
    suspend fun executeLeaveClusterAndReset(): Boolean = withContext(Dispatchers.IO) {
        // Step A: Cluster Broadcast to online peers
        runCatching { broadcastClusterRemoval() }

        // Step B: Cryptographic Erasure
        runCatching { eraseCryptographicKeys() }

        // Step C: Database Purge
        runCatching { purgeDatabases() }

        // Step D: State Reset & Restart
        runCatching { resetStateAndRestart() }

        true
    }

    private suspend fun broadcastClusterRemoval() {
        val identity = runCatching { loadLocalIdentity() }.getOrNull() ?: return
        val localId = identity.deviceId
        val version = FileApexServices.deviceRepository.nextMembershipVersion()
        val hash = DeviceIdentityMarkers.fingerprint(localId)
        val removal = RemovedDeviceRecord(
            deviceId = localId,
            publicKeyHash = hash,
            lastKnownIp = "",
            port = identity.sharePort,
            clusterVersion = version,
            removedAt = version,
            membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL
        )
        val peers = runCatching {
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
                            runCatching {
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
        runCatching { ServerLifecycleManager.stop() }
        ClusterResetPlatform.stateResetAndRestart()
    }
}
