package com.fileapex.domain.presence

import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** SSOT entry for background wake signals (FCM data, future push channels). */
object PresenceBackgroundWake {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onRemoteWakeSignal(sourceDeviceId: String?) {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            runCatching {
                FileApexServices.presenceMonitor.onBackgroundWakeSignal(sourceDeviceId)
            }.onFailure { error ->
                println("PresenceBackgroundWake: wake handling failed — ${error.message}")
            }
        }
    }
}
