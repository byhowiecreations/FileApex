package com.omninode.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.data.settings.UpdateCheckFrequency
import com.omninode.data.settings.UpdateCheckUnit
import com.omninode.di.OmniNodeServices
import com.omninode.update.AppUpdateCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val googleAccountLinkEnabled: Boolean = false,
    val googleAccountEmail: String = "",
    val fileTransferNotificationsEnabled: Boolean = false,
    val pinRequiredEnabled: Boolean = false,
    val devicePin: String = "",
    val pinError: String? = null,
    val autoUpdateEnabled: Boolean = false,
    val autoUpdateIntervalUnit: UpdateCheckUnit = UpdateCheckUnit.Days,
    val autoUpdateIntervalAmount: Int = 1,
    val autoUpdateAmountText: String = "1",
    val googleAccountError: String? = null
)

class SettingsViewModel : ViewModel() {
    private val settings = OmniNodeServices.settings
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            googleAccountLinkEnabled = settings.googleAccountLinkEnabled.value,
            googleAccountEmail = settings.googleAccountEmail.value,
            fileTransferNotificationsEnabled = settings.fileTransferNotificationsEnabled.value,
            pinRequiredEnabled = settings.pinRequiredEnabled.value,
            devicePin = settings.devicePin.value,
            autoUpdateEnabled = settings.autoUpdateEnabled.value,
            autoUpdateIntervalUnit = settings.autoUpdateIntervalUnit.value,
            autoUpdateIntervalAmount = settings.autoUpdateIntervalAmount.value,
            autoUpdateAmountText = settings.autoUpdateIntervalAmount.value.toString()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val updateStatusMessage: StateFlow<String?> = AppUpdateCoordinator.statusMessage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFileTransferNotifications(enabled: Boolean) {
        settings.setFileTransferNotificationsEnabled(enabled)
        _uiState.update { it.copy(fileTransferNotificationsEnabled = enabled) }
    }

    fun setPinRequired(enabled: Boolean) {
        settings.setPinRequiredEnabled(enabled)
        val pin = settings.devicePin.value
        _uiState.update {
            it.copy(
                pinRequiredEnabled = enabled,
                pinError = when {
                    enabled && pin.length < 4 -> "Enter a 4–8 digit PIN"
                    else -> null
                }
            )
        }
    }

    fun setDevicePin(pinValue: String) {
        settings.setDevicePin(pinValue)
        val pin = settings.devicePin.value
        if (settings.pinRequiredEnabled.value && pin.length < 4) {
            _uiState.update {
                it.copy(
                    devicePin = pin,
                    pinError = "PIN must be 4–8 digits"
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
        } else {
            AppUpdateCoordinator.onAutoUpdateDisabled()
        }
    }

    fun setAutoUpdateUnit(unit: UpdateCheckUnit) {
        val amount = UpdateCheckFrequency.sanitizeAmount(
            unit,
            _uiState.value.autoUpdateIntervalAmount
        )
        settings.setAutoUpdateInterval(unit, amount)
        _uiState.update {
            it.copy(
                autoUpdateIntervalUnit = unit,
                autoUpdateIntervalAmount = amount,
                autoUpdateAmountText = amount.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    fun setAutoUpdateAmountText(raw: String) {
        val digits = raw.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(autoUpdateAmountText = digits) }
        val parsed = digits.toIntOrNull() ?: return
        val unit = _uiState.value.autoUpdateIntervalUnit
        val amount = UpdateCheckFrequency.sanitizeAmount(unit, parsed)
        settings.setAutoUpdateInterval(unit, amount)
        _uiState.update {
            it.copy(
                autoUpdateIntervalAmount = amount,
                autoUpdateAmountText = if (digits.isEmpty()) "" else amount.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    fun setAutoUpdateWeekAmount(amount: Int) {
        val safe = UpdateCheckFrequency.sanitizeAmount(UpdateCheckUnit.Weeks, amount)
        settings.setAutoUpdateInterval(UpdateCheckUnit.Weeks, safe)
        _uiState.update {
            it.copy(
                autoUpdateIntervalUnit = UpdateCheckUnit.Weeks,
                autoUpdateIntervalAmount = safe,
                autoUpdateAmountText = safe.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    /** Call after the platform account picker returns. Null = cancelled. */
    fun onGoogleAccountPicked(email: String?) {
        if (email == null) {
            _uiState.update {
                it.copy(googleAccountError = "Google Account linking cancelled")
            }
            return
        }
        settings.setGoogleAccountEmail(email)
        settings.setGoogleAccountLinkEnabled(true)
        _uiState.update {
            it.copy(
                googleAccountLinkEnabled = true,
                googleAccountEmail = email,
                googleAccountError = null
            )
        }
    }

    fun disableGoogleAccountLink() {
        settings.setGoogleAccountLinkEnabled(false)
        _uiState.update {
            it.copy(
                googleAccountLinkEnabled = false,
                googleAccountEmail = "",
                googleAccountError = null
            )
        }
    }

    fun dismissGoogleAccountError() {
        _uiState.update { it.copy(googleAccountError = null) }
    }

    fun dismissPinError() {
        _uiState.update { it.copy(pinError = null) }
    }
}
