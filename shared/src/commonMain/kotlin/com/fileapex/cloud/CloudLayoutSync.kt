package com.fileapex.cloud

import com.fileapex.di.FileApexServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Publishes [DeviceOrderCoordinator] changes to Firestore when sync is enabled. */
object CloudLayoutSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun publishIfLinked(deviceOrderIds: List<String>, updatedAtEpochMs: Long) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!FileApexServices.settings.syncLayoutEnabled.value) return
        val uid = FileApexServices.settings.googleAccountUid.value.trim()
        if (uid.isEmpty()) return
        scope.launch {
            runCatching {
                CloudAuthBackend.patchUserLayout(
                    uid = uid,
                    layout = CloudUserLayout(
                        deviceOrderIds = deviceOrderIds,
                        updatedAtEpochMs = updatedAtEpochMs
                    )
                )
            }.onFailure { error ->
                println("CloudLayoutSync: publish failed — ${error.message}")
            }
        }
    }
}
