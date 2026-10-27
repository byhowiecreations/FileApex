package com.fileapex.cloud.seal

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** When the cluster key changes, this device writes its own name and address again under the new key. */
object CloudRecordResealer {
    fun start(scope: CoroutineScope) {
        ClusterRecordKeys.onChanged = {
            scope.launch(Dispatchers.IO) {
                cancellableCatching {
                    val name = LocalDeviceNameStore.current().ifBlank { loadLocalIdentity().deviceName }
                    if (name.isNotBlank()) GoogleLinkCoordinator.publishUserRenamedDevice(LocalIdentity.LOCAL_DEVICE_ID, name)
                    GoogleLinkCoordinator.invalidatePublishedPresenceCache()
                    GoogleLinkCoordinator.publishScheduledPresenceHeartbeat()
                }.onFailure { println("CloudRecordResealer: rewrite failed - ${it::class.simpleName}") }
            }
        }
    }
}
