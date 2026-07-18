package com.omninode.presentation

import androidx.lifecycle.ViewModel
import com.omninode.di.OmniNodeServices
import com.omninode.update.AppUpdateCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val googleAccountLinkEnabled: Boolean = false,
    val fileTransferNotificationsEnabled: Boolean = false,
    val pinRequiredEnabled: Boolean = false,
    val devicePin: String = "",
    val pinError: String? = null,
    val autoUpdateEnabled: Boolean = false
)

class SettingsViewModel : ViewModel() {
    private val settings = OmniNodeServices.settings
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            googleAccountLinkEnabled = settings.googleAccountLinkEnabled.value,
            fileTransferNotificationsEnabled = settings.fileTransferNotificationsEnabled.value,
            pinRequiredEnabled = settings.pinRequiredEnabled.value,
            devicePin = settings.devicePin.value,
            autoUpdateEnabled = settings.autoUpdateEnabled.value
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setGoogleAccountLink(enabled: Boolean) {
        settings.setGoogleAccountLinkEnabled(enabled)
        _uiState.update { it.copy(googleAccountLinkEnabled = enabled) }
    }

    fun setFileTransferNotifications(enabled: Boolean) {
        settings.setFileTransferNotificationsEnabled(enabled)
        _uiState.update { it.copy(fileTransferNotificationsEnabled = enabled) }
    }

    fun setPinRequired(enabled: Boolean) {
        if (enabled && settings.devicePin.value.length < 4) {
            _uiState.update {
                it.copy(pinError = "Set a 4–8 digit PIN before enabling")
            }
            return
        }
        settings.setPinRequiredEnabled(enabled)
        _uiState.update {
            it.copy(pinRequiredEnabled = enabled, pinError = null)
        }
    }

    fun setDevicePin(pinValue: String) {
        settings.setDevicePin(pinValue)
        val pin = settings.devicePin.value
        if (settings.pinRequiredEnabled.value && pin.length < 4) {
            settings.setPinRequiredEnabled(false)
            _uiState.update {
                it.copy(
                    devicePin = pin,
                    pinRequiredEnabled = false,
                    pinError = "PIN required turned off — PIN must be 4–8 digits"
                )
            }
            return
        }
        _uiState.update { it.copy(devicePin = pin, pinError = null) }
    }

    fun setAutoUpdate(enabled: Boolean) {
        settings.setAutoUpdateEnabled(enabled)
        _uiState.update { it.copy(autoUpdateEnabled = enabled) }
        if (enabled) {
            AppUpdateCoordinator.onAutoUpdateEnabled()
        }
    }

    fun dismissPinError() {
        _uiState.update { it.copy(pinError = null) }
    }
}
