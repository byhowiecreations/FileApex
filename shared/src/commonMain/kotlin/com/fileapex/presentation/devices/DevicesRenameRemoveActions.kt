package com.fileapex.presentation.devices

import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.platform.purgeDirectShareTarget
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class DevicesRenameRemoveActions(
    private val _uiState: MutableStateFlow<DevicesUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val repository = FileApexServices.deviceRepository
    private val presence = FileApexServices.presenceMonitor

    fun beginRename(deviceId: String) {
        _uiState.update { it.copy(renameTargetId = deviceId) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(renameTargetId = null) }
    }

    fun confirmRename(deviceId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = AppI18n.t("name_cannot_be_empty")) }
            return
        }
        _uiState.update {
            it.copy(
                renameTargetId = null,
                statusMessage = AppI18n.t("updating_device_name"),
                errorMessage = null
            )
        }
        if (com.fileapex.domain.demo.DemoModeState.isDemoModeActive.value &&
            com.fileapex.domain.demo.DemoModeState.isDemoDeviceId(deviceId)
        ) {
            com.fileapex.domain.demo.DemoModeState.renameDemoDevice(deviceId, trimmed)
            _uiState.update { it.copy(statusMessage = AppI18n.t("renamed_synced", trimmed)) }
            return
        }
        viewModelScope.launch {
            cancellableCatching {
                if (deviceId == LocalIdentity.LOCAL_DEVICE_ID) {
                    com.fileapex.domain.device.DeviceNameCoordinator.saveLocalBroadcastName(trimmed)
                    _uiState.update {
                        it.copy(
                            localDeviceName = trimmed,
                            statusMessage = AppI18n.t("renamed_synced", trimmed)
                        )
                    }
                } else {
                    com.fileapex.domain.device.DeviceNameCoordinator.renamePeerDevice(deviceId, trimmed)
                    _uiState.update {
                        it.copy(statusMessage = AppI18n.t("renamed_synced", trimmed))
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = UserFacingErrors.message(error, "rename_failed"))
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        if (com.fileapex.domain.demo.DemoModeState.isDemoModeActive.value &&
            com.fileapex.domain.demo.DemoModeState.isDemoDeviceId(deviceId)
        ) {
            val demoRow = com.fileapex.domain.demo.DemoModeState.demoDeviceRows.value.firstOrNull { it.deviceId == deviceId }
            val name = demoRow?.deviceName ?: deviceId
            com.fileapex.domain.demo.DemoModeState.removeDemoDevice(deviceId)
            _uiState.update { it.copy(statusMessage = AppI18n.t("device_removed_restore", name)) }
            return
        }
        viewModelScope.launch {
            val device = repository.getDevice(deviceId)
            if (device == null) {
                _uiState.update {
                    it.copy(errorMessage = AppI18n.t("device_not_in_list"))
                }
                return@launch
            }
            cancellableCatching {
                val removed = repository.removePermanently(deviceId)
                check(removed) { "Could not remove device" }
                DeviceSessionManager.clearSession(deviceId)
                purgeDirectShareTarget(deviceId)
                FileApexServices.pairingCoordinator.broadcastDeviceRemoval(device)
                GoogleLinkCoordinator.publishRemovedPeer(deviceId)
                presence.refreshOnlineSnapshot()
            }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            statusMessage = AppI18n.t("device_removed_restore", device.deviceName),
                            errorMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = UserFacingErrors.message(error, "remove_failed"))
                    }
                }
            )
        }
    }
}
