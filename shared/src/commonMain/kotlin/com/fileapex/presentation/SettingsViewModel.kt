package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.settings.PinIdleTimeout
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.domain.diagnostics.DeviceDetailsFieldId
import com.fileapex.platform.BootLaunchPreference
import com.fileapex.platform.ServiceWatchdog
import com.fileapex.update.AppUpdateCoordinator
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
    val clipboardSharingEnabled: Boolean = false,
    val fileTransferNotificationsEnabled: Boolean = false,
    val liveTransferCapsuleEnabled: Boolean = false,
    val liveTransferShowQueueEnabled: Boolean = false,
    val pinRequiredEnabled: Boolean = false,
    val devicePin: String = "",
    val pinError: String? = null,
    val pinIdleTimeout: PinIdleTimeout = PinIdleTimeout.FiveMinutes,
    val checkForUpdatesEnabled: Boolean = false,
    val checkForUpdatesIntervalUnit: UpdateCheckUnit = UpdateCheckUnit.Days,
    val checkForUpdatesIntervalAmount: Int = 1,
    val checkForUpdatesAmountText: String = "1",
    val enableServiceWatchdog: Boolean = true,
    val autoLaunchOnReboot: Boolean = true,
    val desktopLayoutMode: DesktopLayoutMode = DesktopLayoutMode.Compact,
    val desktopUiStyle: DesktopUiStyle = DesktopUiStyle.Standard,
    val googleAccountError: String? = null,
    val deviceDetailsDisplayPreferences: DeviceDetailsDisplayPreferences =
        DeviceDetailsDisplayPreferences.defaults(),
    val deviceDetailsAllowOverCellular: Boolean = false
)

class SettingsViewModel : ViewModel() {
    private val settings = FileApexServices.settings
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            googleAccountLinkEnabled = settings.googleAccountLinkEnabled.value,
            googleAccountEmail = settings.googleAccountEmail.value,
            clipboardSharingEnabled = settings.clipboardSharingEnabled.value,
            fileTransferNotificationsEnabled = settings.fileTransferNotificationsEnabled.value,
            liveTransferCapsuleEnabled = settings.liveTransferCapsuleEnabled.value,
            liveTransferShowQueueEnabled = settings.liveTransferShowQueueEnabled.value,
            pinRequiredEnabled = settings.pinRequiredEnabled.value,
            devicePin = settings.devicePin.value,
            pinIdleTimeout = settings.pinIdleTimeout.value,
            checkForUpdatesEnabled = settings.checkForUpdatesEnabled.value,
            checkForUpdatesIntervalUnit = settings.checkForUpdatesIntervalUnit.value,
            checkForUpdatesIntervalAmount = settings.checkForUpdatesIntervalAmount.value,
            checkForUpdatesAmountText = settings.checkForUpdatesIntervalAmount.value.toString(),
            enableServiceWatchdog = settings.enableServiceWatchdog.value,
            autoLaunchOnReboot = settings.autoLaunchOnReboot.value,
            desktopLayoutMode = settings.desktopLayoutMode.value,
            desktopUiStyle = settings.desktopUiStyle.value,
            deviceDetailsDisplayPreferences = settings.deviceDetailsDisplayPreferences.value,
            deviceDetailsAllowOverCellular = settings.deviceDetailsAllowOverCellular.value
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val updateStatusMessage: StateFlow<String?> = AppUpdateCoordinator.statusMessage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val googleLinkStatus: StateFlow<String?> = GoogleLinkCoordinator.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnableServiceWatchdog(enabled: Boolean) {
        settings.setEnableServiceWatchdog(enabled)
        ServiceWatchdog.onPreferenceChanged(enabled)
        _uiState.update { it.copy(enableServiceWatchdog = enabled) }
    }

    fun setAutoLaunchOnReboot(enabled: Boolean) {
        settings.setAutoLaunchOnReboot(enabled)
        BootLaunchPreference.onPreferenceChanged(enabled)
        _uiState.update { it.copy(autoLaunchOnReboot = enabled) }
    }

    fun setDesktopLayoutMode(mode: DesktopLayoutMode) {
        settings.setDesktopLayoutMode(mode)
        _uiState.update { it.copy(desktopLayoutMode = mode) }
    }

    fun setDesktopUiStyle(style: DesktopUiStyle) {
        settings.setDesktopUiStyle(style)
        _uiState.update { it.copy(desktopUiStyle = style) }
    }

    fun setClipboardSharing(enabled: Boolean) {
        settings.setClipboardSharingEnabled(enabled)
        _uiState.update { it.copy(clipboardSharingEnabled = enabled) }
    }

    fun setFileTransferNotifications(enabled: Boolean) {
        settings.setFileTransferNotificationsEnabled(enabled)
        _uiState.update { it.copy(fileTransferNotificationsEnabled = enabled) }
    }

    fun setLiveTransferCapsule(enabled: Boolean) {
        settings.setLiveTransferCapsuleEnabled(enabled)
        _uiState.update { it.copy(liveTransferCapsuleEnabled = enabled) }
    }

    fun setLiveTransferShowQueue(enabled: Boolean) {
        settings.setLiveTransferShowQueueEnabled(enabled)
        _uiState.update { it.copy(liveTransferShowQueueEnabled = enabled) }
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

    /** Force an immediate update check from repeated Settings version taps. */
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

    fun setDeviceDetailsFieldVisible(fieldId: DeviceDetailsFieldId, visible: Boolean) {
        val current = _uiState.value.deviceDetailsDisplayPreferences.normalized()
        val updated = current.copy(
            fields = current.fields.map { pref ->
                if (pref.id == fieldId.name) pref.copy(visible = visible) else pref
            }
        )
        settings.setDeviceDetailsDisplayPreferences(updated)
        _uiState.update { it.copy(deviceDetailsDisplayPreferences = updated) }
    }

    fun reorderDeviceDetailsFields(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _uiState.value.deviceDetailsDisplayPreferences.normalized()
        val mutable = current.fields.toMutableList()
        if (fromIndex !in mutable.indices || toIndex !in mutable.indices) return
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val updated = current.copy(fields = mutable)
        settings.setDeviceDetailsDisplayPreferences(updated)
        _uiState.update { it.copy(deviceDetailsDisplayPreferences = updated) }
    }

    fun resetDeviceDetailsDisplayPreferences() {
        val defaults = DeviceDetailsDisplayPreferences.defaults()
        settings.setDeviceDetailsDisplayPreferences(defaults)
        _uiState.update { it.copy(deviceDetailsDisplayPreferences = defaults) }
    }

    fun setDeviceDetailsAllowOverCellular(enabled: Boolean) {
        settings.setDeviceDetailsAllowOverCellular(enabled)
        _uiState.update { it.copy(deviceDetailsAllowOverCellular = enabled) }
        viewModelScope.launch {
            if (!settings.googleAccountLinkEnabled.value) {
                if (!enabled) DiagnosticsCloudRelay.stopInbox()
                return@launch
            }
            val uid = settings.googleAccountUid.value
            val deviceId = loadLocalIdentity().deviceId
            runCatching {
                DiagnosticsCloudRelay.syncCloudOptIn(uid, deviceId, enabled)
            }.onFailure { error ->
                println("SettingsViewModel: diagnostics cloud sync failed — ${error.message}")
            }
            if (enabled) {
                DiagnosticsCloudRelay.startInbox(uid, deviceId)
            } else {
                DiagnosticsCloudRelay.stopInbox()
            }
        }
    }
}
