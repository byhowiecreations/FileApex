package com.fileapex.domain.presence

import com.fileapex.cloud.drive.DriveRelayCoordinator
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexMdnsBrowser
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.isActiveLanConnectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Event-driven LAN revalidation — mDNS re-register, cloud presence publish, and peer sweep
 * after network transitions. Retries until DHCP assigns a LAN IP (callbacks often fire early).
 */
object PresenceNetworkRevalidator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onLanNetworkTransition() {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            runCatching {
                revalidateAfterNetworkTransition()
            }.onFailure { error ->
                println("PresenceNetworkRevalidator: transition revalidation failed — ${error.message}")
            }
        }
    }

    private suspend fun revalidateAfterNetworkTransition() {
        GoogleLinkCoordinator.invalidatePublishedPresenceCache()

        if (ServerLifecycleManager.isRunning) {
            val identity = loadLocalIdentity()
            BackgroundPresenceServices.onShareServerStarted(identity.sharePort, identity.deviceId)
        }
        FileApexMdnsBrowser.requestProbe()

        for (delayMs in LanPresenceTiming.NETWORK_TRANSITION_RETRY_DELAYS_MS) {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            if (!isActiveLanConnectivity()) {
                continue
            }
            runCatching { GoogleLinkCoordinator.publishSelfPresenceIfLinked() }
            FileApexServices.presenceMonitor.runSingleShotRevalidation()
            runCatching { FileApexServices.transferQueue.drainEligible() }.onFailure { error ->
                println("PresenceNetworkRevalidator: queue drain failed — ${error.message}")
            }
            return
        }

        DriveRelayCoordinator.onLeftLocalNetwork()
        FileApexServices.presenceMonitor.runSingleShotRevalidation()
        runCatching { FileApexServices.transferQueue.drainEligible() }.onFailure { error ->
            println("PresenceNetworkRevalidator: queue drain failed — ${error.message}")
        }
    }

    fun ensureRegistered() {
        registerLanNetworkTransitionListener()
    }
}

/** Platform registers ConnectivityManager / interface watchers → [PresenceNetworkRevalidator]. */
expect fun registerLanNetworkTransitionListener()
