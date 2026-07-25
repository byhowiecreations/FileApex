package com.fileapex.domain.presence

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexMdnsBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Event-driven LAN revalidation — single-shot mDNS probe + peer sweep after network transitions.
 * Avoids idle timers; complements FCM for cloud-linked devices.
 */
object PresenceNetworkRevalidator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onLanNetworkTransition() {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            runCatching {
                GoogleLinkCoordinator.invalidatePublishedPresenceCache()
                FileApexMdnsBrowser.requestProbe()
                FileApexServices.presenceMonitor.runSingleShotRevalidation()
            }.onFailure { error ->
                println("PresenceNetworkRevalidator: transition revalidation failed — ${error.message}")
            }
        }
    }

    fun ensureRegistered() {
        registerLanNetworkTransitionListener()
    }
}

/** Platform registers ConnectivityManager / interface watchers → [PresenceNetworkRevalidator]. */
expect fun registerLanNetworkTransitionListener()
