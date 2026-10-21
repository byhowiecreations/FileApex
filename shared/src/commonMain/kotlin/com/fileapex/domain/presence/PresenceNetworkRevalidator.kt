package com.fileapex.domain.presence

import com.fileapex.cloud.drive.DriveRelayCoordinator
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexMdnsBrowser
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Event-driven LAN revalidation, mDNS re-register, cloud presence publish, and peer sweep
 * after network transitions. Retries until DHCP assigns a LAN IP (callbacks often fire early).
 */
object PresenceNetworkRevalidator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var revalidation: Job? = null

    fun onLanNetworkTransition() {
        if (!FileApexServices.isDatabaseReady()) return
        com.fileapex.iroh.IrohRuntime.onNetworkChanged()
        // onAvailable and onLinkPropertiesChanged arrive back to back; the later one restarts the sequence.
        revalidation?.cancel()
        revalidation = scope.launch {
            cancellableCatching {
                revalidateAfterNetworkTransition()
            }.onFailure { error ->
                println("PresenceNetworkRevalidator: transition revalidation failed - ${error.message}")
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
            cancellableCatching { GoogleLinkCoordinator.publishSelfPresenceIfLinked() }
            GoogleLinkCoordinator.refreshCloudRegistry()
            FileApexServices.presenceMonitor.runSingleShotRevalidation()
            cancellableCatching { FileApexServices.transferQueue.drainEligible() }.onFailure { error ->
                println("PresenceNetworkRevalidator: queue drain failed - ${error.message}")
            }
            return
        }

        // No LAN: stop announcing. The next transition onto a LAN registers the service again.
        com.fileapex.network.FileApexMdnsAdvertiser.stop()
        DriveRelayCoordinator.onLeftLocalNetwork()
        FileApexServices.presenceMonitor.runSingleShotRevalidation()
        cancellableCatching { FileApexServices.transferQueue.drainEligible() }.onFailure { error ->
            println("PresenceNetworkRevalidator: queue drain failed - ${error.message}")
        }
    }

    fun ensureRegistered() {
        registerLanNetworkTransitionListener()
    }
}

/** Platform registers ConnectivityManager / interface watchers → [PresenceNetworkRevalidator]. */
expect fun registerLanNetworkTransitionListener()
