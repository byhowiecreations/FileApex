package com.fileapex.domain.presence

import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.fileapex.util.cancellableCatching

object PresenceBackgroundWake {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onRemoteWakeSignal(sourceDeviceId: String?) {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            cancellableCatching {
                FileApexServices.presenceMonitor.onBackgroundWakeSignal(sourceDeviceId)
            }.onFailure { error ->
                println("PresenceBackgroundWake: wake handling failed - ${error.message}")
            }
        }
    }
}
