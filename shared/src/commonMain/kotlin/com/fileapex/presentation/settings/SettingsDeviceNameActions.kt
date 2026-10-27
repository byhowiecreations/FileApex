package com.fileapex.presentation.settings

import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.domain.device.DeviceNameCoordinator
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.fileapex.presentation.SettingsUiState
import kotlinx.coroutines.CoroutineScope

internal class SettingsDeviceNameActions(
    private val _uiState: MutableStateFlow<SettingsUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val settings = FileApexServices.settings

    fun setDeviceNameDraft(value: String) {
        val filtered = filterDraft(value)
        _uiState.update { it.copy(deviceNameDraft = filtered, deviceNameError = null) }
    }

    fun saveDeviceName() {
        val draft = _uiState.value.deviceNameDraft.trim()
        DeviceNameCoordinator.validate(draft)?.let { message ->
            _uiState.update { it.copy(deviceNameError = message) }
            return
        }
        _uiState.update {
            it.copy(deviceNameSaveBusy = true, deviceNameError = null, deviceNameStatus = null)
        }
        viewModelScope.launch {
            cancellableCatching {
                DeviceNameCoordinator.saveLocalBroadcastName(draft)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        deviceNameSaveBusy = false,
                        deviceNameStatus = AppI18n.t("device_name_saved", draft),
                        deviceNameError = null
                    )
                }
                refreshDeviceNameClusterSync()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        deviceNameSaveBusy = false,
                        deviceNameError = UserFacingErrors.message(error, "rename_failed")
                    )
                }
            }
        }
    }

    fun refreshDeviceNameClusterSync() {
        if (!settings.googleAccountLinkEnabled.value) {
            _uiState.update { it.copy(deviceNameClusterSync = "") }
            return
        }
        val selfId = loadLocalIdentity().deviceId
        val cluster = GoogleLinkCoordinator.cloudRecordFor(selfId)?.deviceName.orEmpty().trim()
        _uiState.update { it.copy(deviceNameClusterSync = cluster) }
    }

    companion object {
        internal fun filterDraft(value: String): String = buildString {
            for (ch in value) {
                if (ch.isLetterOrDigit() || ch == ' ' || ch == '-') append(ch)
            }
        }
    }
}
