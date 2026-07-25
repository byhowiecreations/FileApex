package com.fileapex.cloud

import com.fileapex.domain.presence.LanPresenceTiming
import com.fileapex.network.ServerLifecycleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * **10-minute** Firestore cloud presence heartbeat while the share server is active.
 *
 * Distinct from the **20-minute** Android FGS recovery watchdog
 * ([com.fileapex.util.TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS] / [ServiceWatchdogScheduler]).
 *
 * Started/stopped with [com.fileapex.domain.presence.BackgroundPresenceServices].
 */
object CloudPresenceHeartbeat {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    fun start() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                if (ServerLifecycleManager.isRunning) {
                    runCatching { GoogleLinkCoordinator.publishScheduledPresenceHeartbeat() }
                        .onFailure { error ->
                            println("CloudPresenceHeartbeat: publish failed — ${error.message}")
                        }
                }
                delay(LanPresenceTiming.FIRESTORE_PRESENCE_HEARTBEAT_MS)
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
