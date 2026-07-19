package com.omninode.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.cloud.GoogleLinkCoordinator
import com.omninode.data.settings.PinIdleTimeout
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
import kotlinx.coroutines.launch

data class SettingsUiState(
    val googleAccountLinkEnabled: Boolean = false,
    val googleAccountEmail: String = "",
    val fileTransferNotificationsEnabled: Boolean = false,
    val pinRequiredEnabled: Boolean = false,
    val devicePin: String = "",
    val pinError: String? = null,
    val pinIdleTimeout: PinIdleTimeout = PinIdleTimeout.FiveMinutes,
    val checkForUpdatesEnabled: Boolean = false,
    val checkForUpdatesIntervalUnit: UpdateCheckUnit = UpdateCheckUnit.Days,
    val checkForUpdatesIntervalAmount: Int = 1,
    val checkForUpdatesAmountText: String = "1",
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
            pinIdleTimeout = settings.pinIdleTimeout.value,
            checkForUpdatesEnabled = settings.checkForUpdatesEnabled.value,
            checkForUpdatesIntervalUnit = settings.checkForUpdatesIntervalUnit.value,
            checkForUpdatesIntervalAmount = settings.checkForUpdatesIntervalAmount.value,
            checkForUpdatesAmountText = settings.checkForUpdatesIntervalAmount.value.toString()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val updateStatusMessage: StateFlow<String?> = AppUpdateCoordinator.statusMessage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val googleLinkStatus: StateFlow<String?> = GoogleLinkCoordinator.status
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

    fun setPinIdleTimeout(timeout: PinIdleTimeout) {
        settings.setPinIdleTimeout(timeout)
        _uiState.update { it.copy(pinIdleTimeout = timeout) }
    }

    fun setCheckForUpdates(enabled: Boolean) {
        settings.setCheckForUpdatesEnabled(enabled)
        _uiState.update { it.copy(checkForUpdatesEnabled = enabled) }
        if (enabled) {
            AppUpdateCoordinator.onCheckForUpdatesEnabled()
        } else {
            AppUpdateCoordinator.onCheckForUpdatesDisabled()
        }
    }

    fun setCheckForUpdatesUnit(unit: UpdateCheckUnit) {
        val amount = UpdateCheckFrequency.sanitizeAmount(
            unit,
            _uiState.value.checkForUpdatesIntervalAmount
        )
        settings.setCheckForUpdatesInterval(unit, amount)
        _uiState.update {
            it.copy(
                checkForUpdatesIntervalUnit = unit,
                checkForUpdatesIntervalAmount = amount,
                checkForUpdatesAmountText = amount.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    fun setCheckForUpdatesAmountText(raw: String) {
        val digits = raw.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(checkForUpdatesAmountText = digits) }
        val parsed = digits.toIntOrNull() ?: return
        val unit = _uiState.value.checkForUpdatesIntervalUnit
        val amount = UpdateCheckFrequency.sanitizeAmount(unit, parsed)
        settings.setCheckForUpdatesInterval(unit, amount)
        _uiState.update {
            it.copy(
                checkForUpdatesIntervalAmount = amount,
                checkForUpdatesAmountText = if (digits.isEmpty()) "" else amount.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    fun setCheckForUpdatesWeekAmount(amount: Int) {
        val safe = UpdateCheckFrequency.sanitizeAmount(UpdateCheckUnit.Weeks, amount)
        settings.setCheckForUpdatesInterval(UpdateCheckUnit.Weeks, safe)
        _uiState.update {
            it.copy(
                checkForUpdatesIntervalUnit = UpdateCheckUnit.Weeks,
                checkForUpdatesIntervalAmount = safe,
                checkForUpdatesAmountText = safe.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    /** Hidden Settings version tap easter egg — force an immediate update check. */
    fun onVersionNumberEasterEgg() {
        AppUpdateCoordinator.checkNowManual()
    }

    /** Credential Manager / desktop OAuth returned a Google ID token. */
    fun onGoogleIdToken(idToken: String?, emailHint: String?, errorMessage: String?) {
        if (idToken.isNullOrBlank()) {
            _uiState.update {
                it.copy(googleAccountError = errorMessage ?: "Google sign-in cancelled")
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                GoogleLinkCoordinator.linkWithGoogleIdToken(idToken, emailHint)
            }.onSuccess { session ->
                _uiState.update {
                    it.copy(
                        googleAccountLinkEnabled = true,
                        googleAccountEmail = session.email,
                        googleAccountError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(googleAccountError = error.message ?: "Google link failed")
                }
            }
        }
    }

    fun disableGoogleAccountLink() {
        viewModelScope.launch {
            runCatching {
                GoogleLinkCoordinator.unlinkAndSignOut()
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        googleAccountLinkEnabled = false,
                        googleAccountEmail = "",
                        googleAccountError = null
                    )
                }
            }.onFailure { error ->
                settings.setGoogleAccountLinkEnabled(false)
                _uiState.update {
                    it.copy(
                        googleAccountLinkEnabled = false,
                        googleAccountEmail = "",
                        googleAccountError = error.message
                    )
                }
            }
        }
    }

    fun dismissGoogleAccountError() {
        _uiState.update { it.copy(googleAccountError = null) }
    }

    fun dismissPinError() {
        _uiState.update { it.copy(pinError = null) }
    }
}
