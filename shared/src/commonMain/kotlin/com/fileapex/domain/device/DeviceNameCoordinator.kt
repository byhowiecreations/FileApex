package com.fileapex.domain.device

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.identity.DeviceNamePeerLabelsStore
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.network.RenameOutcome
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching

object DeviceNameCoordinator {
    private val namePattern = Regex("^[A-Za-z0-9]+(?:[ \\-][A-Za-z0-9]+)*$")

    fun validate(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return AppI18n.t("device_name_empty")
        if (!namePattern.matches(trimmed)) return AppI18n.t("device_name_alphanumeric")
        return null
    }

    suspend fun saveLocalBroadcastName(name: String) {
        val trimmed = name.trim()
        validate(trimmed)?.let { error(it) }
        LocalDeviceNameStore.apply(trimmed)
        cancellableCatching {
            GoogleLinkCoordinator.publishUserRenamedDevice(LocalIdentity.LOCAL_DEVICE_ID, trimmed)
        }
        FileApexServices.pairingCoordinator.broadcastSelfIdentity(FileApexServices.presenceMonitor.onlineDeviceIds.value)
    }

    suspend fun applyRemoteRename(
        assignedName: String,
        renamedByDeviceId: String = "",
        renamedByDeviceName: String = ""
    ) {
        val trimmed = assignedName.trim()
        if (trimmed.isEmpty()) return
        LocalDeviceNameStore.apply(trimmed)
        val peerId = renamedByDeviceId.trim()
        if (peerId.isNotEmpty()) {
            DeviceNamePeerLabelsStore.record(
                peerDeviceId = peerId,
                peerDeviceName = renamedByDeviceName,
                assignedName = trimmed,
                updatedAtEpochMs = TimeUtils.now()
            )
        }
        cancellableCatching {
            GoogleLinkCoordinator.publishUserRenamedDevice(LocalIdentity.LOCAL_DEVICE_ID, trimmed)
        }
        FileApexServices.pairingCoordinator.broadcastSelfIdentity(FileApexServices.presenceMonitor.onlineDeviceIds.value)
    }

    suspend fun renamePeerDevice(deviceId: String, newName: String): RenameOutcome {
        val trimmed = newName.trim()
        validate(trimmed)?.let { error(it) }
        val peer = FileApexServices.deviceRepository.getDevice(deviceId)
            ?: error(AppI18n.t("device_not_found"))
        val self = loadLocalIdentity()
        val selfLabel = LocalDeviceNameStore.current().ifBlank { self.deviceName }
        val outcome = cancellableCatching {
            FileApexServices.client.cluster.postRemoteRename(
                host = peer.lastKnownIp,
                port = peer.port,
                newName = trimmed,
                renamedByDeviceId = self.deviceId,
                renamedByDeviceName = selfLabel
            )
        }.getOrDefault(RenameOutcome.NotDelivered)
        // The device's own answer decides the label; an older build or an unreachable one keeps what was typed.
        val label = (outcome as? RenameOutcome.Confirmed)?.name ?: trimmed
        FileApexServices.deviceRepository.rename(deviceId, label)
        cancellableCatching {
            GoogleLinkCoordinator.publishUserRenamedDevice(deviceId, label)
        }
        FileApexServices.presenceMonitor.refreshOnlineSnapshot()
        return outcome
    }
}
