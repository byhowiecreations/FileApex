package com.omninode.domain.pairing

import com.omninode.di.OmniNodeServices
import com.omninode.domain.presence.LanPresenceTiming
import com.omninode.network.ServerLifecycleManager
import com.omninode.platform.isActiveLanConnectivity
import com.omninode.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Low-frequency LAN [SELF_METADATA] broadcaster for QR-paired and Firebase-linked nodes alike.
 *
 * Runs only while the share server is active and this device is on Wi-Fi/Ethernet with a
 * routable LAN address — no idle health polling.
 */
object LanSelfMetadataBroadcaster {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var broadcastJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (broadcastJob?.isActive == true) return
            broadcastJob = scope.launch {
                while (isActive) {
                    if (shouldBroadcast()) {
                        runCatching {
                            OmniNodeServices.pairingCoordinator.broadcastSelfIdentity()
                        }.onFailure { error ->
                            println(
                                "LanSelfMetadataBroadcaster: broadcast failed — ${error.message}"
                            )
                        }
                    }
                    delay(LanPresenceTiming.SELF_METADATA_BROADCAST_MS)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            broadcastJob?.cancel()
            broadcastJob = null
        }
    }

    private fun shouldBroadcast(): Boolean {
        if (!OmniNodeServices.isDatabaseReady()) return false
        if (!ServerLifecycleManager.isRunning) return false
        if (!isActiveLanConnectivity()) return false
        return NetworkUtils.isUsableLanIpv4(NetworkUtils.preferredLanIpv4())
    }
}
