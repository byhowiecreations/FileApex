package com.fileapex.presentation.settings

import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.fileapex.data.settings.DriveRelayMaxMb
import com.fileapex.presentation.SettingsUiState
import kotlinx.coroutines.CoroutineScope

internal class SettingsDriveActions(
    private val _uiState: MutableStateFlow<SettingsUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val settings = FileApexServices.settings

    fun setGoogleDriveRelayEnabled(enabled: Boolean) {
        settings.setGoogleDriveRelayEnabled(enabled)
        _uiState.update { it.copy(googleDriveRelayEnabled = enabled, googleDriveAuthError = null) }
        com.fileapex.cloud.drive.DriveRelayCoordinator.applySchedulerFromSettings()
        if (enabled) {
            settings.setCellularReceivePromptAcknowledged(true)
            settings.setCellularSendPromptAcknowledged(true)
            if (com.fileapex.cloud.drive.GoogleDriveAuth.hasGrant()) {
                com.fileapex.platform.DriveRelayNotifier.onDriveEnabledAndGranted()
            }
        }
    }

    fun setDriveRelayMaxMb(limit: DriveRelayMaxMb) {
        settings.setDriveRelayMaxMb(limit)
        _uiState.update { it.copy(driveRelayMaxMb = limit) }
    }

    fun setDriveRelayEncryptionEnabled(enabled: Boolean) {
        settings.setDriveRelayEncryptionEnabled(enabled)
        _uiState.update { it.copy(driveRelayEncryptionEnabled = enabled) }
    }

    fun setDrivePurgeAfter72Hours(enabled: Boolean) {
        settings.setDrivePurgeAfter72Hours(enabled)
        _uiState.update { it.copy(drivePurgeAfter72Hours = enabled) }
    }

    fun purgeDriveRelayNow() {
        if (_uiState.value.drivePurgeNowBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(drivePurgeNowBusy = true, drivePurgeNowMessage = null) }
            cancellableCatching { com.fileapex.cloud.drive.DriveRelayCoordinator.purgeRelayNow() }
                .onSuccess { deleted ->
                    _uiState.update {
                        it.copy(
                            drivePurgeNowBusy = false,
                            drivePurgeNowMessage = AppI18n.t("deleted_relay_files", deleted)
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            drivePurgeNowBusy = false,
                            drivePurgeNowMessage = AppI18n.t("could_not_delete_relay")
                        )
                    }
                    println("SettingsViewModel: Drive purge failed - ${error.message}")
                }
        }
    }

    fun onGoogleDriveAuthResult(granted: Boolean, errorMessage: String?) {
        if (granted) {
            setGoogleDriveRelayEnabled(true)
        } else {
            settings.setGoogleDriveRelayEnabled(false)
            _uiState.update {
                it.copy(
                    googleDriveRelayEnabled = false,
                    googleDriveAuthError = errorMessage
                )
            }
        }
    }
}
