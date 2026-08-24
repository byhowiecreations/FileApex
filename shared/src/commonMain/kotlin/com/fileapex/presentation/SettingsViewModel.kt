package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.settings.PinIdleTimeout
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.domain.clipboard.ClipboardShareMode
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

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.BulletinRemoteFilePurgePreference
import com.fileapex.data.settings.DriveRelayMaxMb

data class SettingsUiState(
    val googleAccountLinkEnabled: Boolean = false,
    val googleAccountEmail: String = "",
    val clipboardSharingEnabled: Boolean = false,
    val clipboardShareMode: ClipboardShareMode = ClipboardShareMode.UNSET,
    val clipboardTargetDeviceIds: Set<String> = emptySet(),
    val clipboardViaCellularEnabled: Boolean = false,
    val clipboardAccessibilityEnabled: Boolean = false,
    val clipboardAutoSendEnabled: Boolean = false,
    val clipboardPeers: List<PairedDeviceEntity> = emptyList(),
    val fileTransferNotificationsEnabled: Boolean = false,
    val driveRelayNotificationsEnabled: Boolean = false,
    val notesNotificationsEnabled: Boolean = false,
    val liveTransferCapsuleEnabled: Boolean = false,
    val liveTransferShowQueueEnabled: Boolean = false,
    val appTheme: AppTheme = AppTheme.CLEAN,
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
    val deviceDetailsAllowOverCellular: Boolean = false,
    val cellularEnabled: Boolean = false,
    val googleDriveRelayEnabled: Boolean = false,
    val driveRelayMaxMb: DriveRelayMaxMb = DriveRelayMaxMb.DEFAULT,
    val drivePurgeAfter72Hours: Boolean = true,
    val allowRemoteFileDeletion: Boolean = false,
    val googleDriveAuthError: String? = null,
    val drivePurgeNowBusy: Boolean = false,
    val drivePurgeNowMessage: String? = null,
    val kineticSphereCleanMode: Boolean = false,
    val kineticSphereConnectedLinesEnabled: Boolean = true,
    val kineticSphereOrbitalRingsEnabled: Boolean = true,
    val systemPerformanceExpanded: Boolean = true,
    val appearanceBehaviorExpanded: Boolean = true,
    val securityAccountExpanded: Boolean = true,
    val showAccessibilityRestrictedHelp: Boolean = false
)

class SettingsViewModel : ViewModel() {
    private val settings = FileApexServices.settings
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            googleAccountLinkEnabled = settings.googleAccountLinkEnabled.value,
            googleAccountEmail = settings.googleAccountEmail.value,
            clipboardSharingEnabled = settings.clipboardSharingEnabled.value,
            clipboardShareMode = settings.clipboardShareMode.value,
            clipboardTargetDeviceIds = settings.clipboardTargetDeviceIds.value,
            clipboardViaCellularEnabled = settings.clipboardViaCellularEnabled.value,
            clipboardAccessibilityEnabled = settings.clipboardAccessibilityEnabled.value,
            clipboardAutoSendEnabled = settings.clipboardAutoSendEnabled.value,
            fileTransferNotificationsEnabled = settings.fileTransferNotificationsEnabled.value,
            driveRelayNotificationsEnabled = settings.driveRelayNotificationsEnabled.value,
            notesNotificationsEnabled = settings.notesNotificationsEnabled.value,
            liveTransferCapsuleEnabled = settings.liveTransferCapsuleEnabled.value,
            liveTransferShowQueueEnabled = settings.liveTransferShowQueueEnabled.value,
            appTheme = settings.appTheme.value,
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
            deviceDetailsAllowOverCellular = settings.deviceDetailsAllowOverCellular.value,
            cellularEnabled = settings.cellularEnabled.value,
            googleDriveRelayEnabled = settings.googleDriveRelayEnabled.value,
            driveRelayMaxMb = settings.driveRelayMaxMb.value,
            drivePurgeAfter72Hours = settings.drivePurgeAfter72Hours.value,
            allowRemoteFileDeletion =
                settings.bulletinRemoteFilePurgePreference.value == BulletinRemoteFilePurgePreference.ENABLED,
            kineticSphereCleanMode = settings.kineticSphereCleanMode.value,
            kineticSphereConnectedLinesEnabled = settings.kineticSphereConnectedLinesEnabled.value,
            kineticSphereOrbitalRingsEnabled = settings.kineticSphereOrbitalRingsEnabled.value,
            systemPerformanceExpanded = settings.settingsGroupSystemPerformanceExpanded.value,
            appearanceBehaviorExpanded = settings.settingsGroupAppearanceBehaviorExpanded.value,
            securityAccountExpanded = settings.settingsGroupSecurityAccountExpanded.value
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            FileApexServices.awaitBootstrap()
            FileApexServices.deviceRepository.observeDevices().collect { devices ->
                _uiState.update { it.copy(clipboardPeers = devices) }
            }
        }
        viewModelScope.launch {
            settings.clipboardSharingEnabled.collect { enabled ->
                _uiState.update { it.copy(clipboardSharingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.clipboardShareMode.collect { mode ->
                _uiState.update { it.copy(clipboardShareMode = mode) }
            }
        }
        viewModelScope.launch {
            settings.clipboardTargetDeviceIds.collect { ids ->
                _uiState.update { it.copy(clipboardTargetDeviceIds = ids) }
            }
        }
        viewModelScope.launch {
            settings.clipboardViaCellularEnabled.collect { enabled ->
                _uiState.update { it.copy(clipboardViaCellularEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.clipboardAccessibilityEnabled.collect { enabled ->
                _uiState.update { it.copy(clipboardAccessibilityEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.clipboardAutoSendEnabled.collect { enabled ->
                _uiState.update { it.copy(clipboardAutoSendEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.notesNotificationsEnabled.collect { enabled ->
                _uiState.update { it.copy(notesNotificationsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.driveRelayNotificationsEnabled.collect { enabled ->
                _uiState.update { it.copy(driveRelayNotificationsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.kineticSphereCleanMode.collect { mode ->
                _uiState.update { it.copy(kineticSphereCleanMode = mode) }
            }
        }
        viewModelScope.launch {
            settings.kineticSphereConnectedLinesEnabled.collect { enabled ->
                _uiState.update { it.copy(kineticSphereConnectedLinesEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.kineticSphereOrbitalRingsEnabled.collect { enabled ->
                _uiState.update { it.copy(kineticSphereOrbitalRingsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.settingsGroupSystemPerformanceExpanded.collect { expanded ->
                _uiState.update { it.copy(systemPerformanceExpanded = expanded) }
            }
        }
        viewModelScope.launch {
            settings.settingsGroupAppearanceBehaviorExpanded.collect { expanded ->
                _uiState.update { it.copy(appearanceBehaviorExpanded = expanded) }
            }
        }
        viewModelScope.launch {
            settings.settingsGroupSecurityAccountExpanded.collect { expanded ->
                _uiState.update { it.copy(securityAccountExpanded = expanded) }
            }
        }
        viewModelScope.launch {
            settings.bulletinRemoteFilePurgePreference.collect { preference ->
                _uiState.update {
                    it.copy(
                        allowRemoteFileDeletion = preference == BulletinRemoteFilePurgePreference.ENABLED
                    )
                }
            }
        }
    }

    fun toggleSystemPerformanceGroup() {
        val newExpanded = !_uiState.value.systemPerformanceExpanded
        settings.setSettingsGroupSystemPerformanceExpanded(newExpanded)
        _uiState.update { it.copy(systemPerformanceExpanded = newExpanded) }
    }

    fun toggleAppearanceBehaviorGroup() {
        val newExpanded = !_uiState.value.appearanceBehaviorExpanded
        settings.setSettingsGroupAppearanceBehaviorExpanded(newExpanded)
        _uiState.update { it.copy(appearanceBehaviorExpanded = newExpanded) }
    }

    fun toggleSecurityAccountGroup() {
        val newExpanded = !_uiState.value.securityAccountExpanded
        settings.setSettingsGroupSecurityAccountExpanded(newExpanded)
        _uiState.update { it.copy(securityAccountExpanded = newExpanded) }
    }

    fun setKineticSphereCleanMode(enabled: Boolean) {
        settings.setKineticSphereCleanMode(enabled)
        _uiState.update { 
            it.copy(
                kineticSphereCleanMode = enabled,
                kineticSphereConnectedLinesEnabled = !enabled,
                kineticSphereOrbitalRingsEnabled = !enabled
            )
        }
    }

    fun setKineticSphereConnectedLinesEnabled(enabled: Boolean) {
        settings.setKineticSphereConnectedLinesEnabled(enabled)
        _uiState.update { it.copy(kineticSphereConnectedLinesEnabled = enabled) }
    }

    fun setKineticSphereOrbitalRingsEnabled(enabled: Boolean) {
        settings.setKineticSphereOrbitalRingsEnabled(enabled)
        _uiState.update { it.copy(kineticSphereOrbitalRingsEnabled = enabled) }
    }

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
        if (enabled) {
            com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboard()
        }
    }

    fun setClipboardShareMode(mode: ClipboardShareMode) {
        settings.setClipboardShareMode(mode)
        _uiState.update { it.copy(clipboardShareMode = mode) }
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboard()
    }

    fun setClipboardTargetDevice(deviceId: String, selected: Boolean) {
        settings.setClipboardTargetDevice(deviceId, selected)
        _uiState.update { it.copy(clipboardTargetDeviceIds = settings.clipboardTargetDeviceIds.value) }
        if (selected) {
            com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboard()
        }
    }

    fun setClipboardViaCellular(enabled: Boolean) {
        settings.setClipboardViaCellularEnabled(enabled)
        _uiState.update { it.copy(clipboardViaCellularEnabled = enabled) }
    }

    fun setClipboardAutoSend(enabled: Boolean) {
        settings.setClipboardAutoSendEnabled(enabled)
        _uiState.update { it.copy(clipboardAutoSendEnabled = enabled) }
    }

    fun setClipboardAccessibility(enabled: Boolean) {
        settings.setClipboardAccessibilityEnabled(enabled)
        val restricted = enabled &&
            com.fileapex.platform.ClipboardAccessibilitySettings.isRestrictedSettingsBlocked()
        _uiState.update {
            it.copy(
                clipboardAccessibilityEnabled = enabled,
                showAccessibilityRestrictedHelp = restricted
            )
        }
        if (enabled && !restricted) {
            com.fileapex.platform.ClipboardAccessibilitySettings.openSystemPrompt()
        }
    }

    fun dismissAccessibilityRestrictedHelp() {
        _uiState.update { it.copy(showAccessibilityRestrictedHelp = false) }
    }

    fun openAccessibilityAppInfo() {
        com.fileapex.platform.ClipboardAccessibilitySettings.openAppInfo()
    }

    fun openAccessibilitySystemSettings() {
        com.fileapex.platform.ClipboardAccessibilitySettings.openSystemPrompt()
    }

    fun sendClipboardNow() {
        viewModelScope.launch {
            com.fileapex.platform.BriefToast.show(
                com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboardNow()
            )
        }
    }

    fun setFileTransferNotifications(enabled: Boolean) {
        settings.setFileTransferNotificationsEnabled(enabled)
        if (!enabled) {
            settings.setLiveTransferCapsuleEnabled(false)
        }
        _uiState.update {
            it.copy(
                fileTransferNotificationsEnabled = enabled,
                liveTransferCapsuleEnabled = if (enabled) it.liveTransferCapsuleEnabled else false
            )
        }
    }

    fun setDriveRelayNotifications(enabled: Boolean) {
        settings.setDriveRelayNotificationsEnabled(enabled)
        if (enabled && com.fileapex.cloud.drive.GoogleDriveAuth.hasGrant()) {
            com.fileapex.platform.DriveRelayNotifier.onDriveEnabledAndGranted()
        }
        _uiState.update { it.copy(driveRelayNotificationsEnabled = enabled) }
    }

    fun setNotesNotifications(enabled: Boolean) {
        settings.setNotesNotificationsEnabled(enabled)
        _uiState.update { it.copy(notesNotificationsEnabled = enabled) }
    }

    fun setLiveTransferCapsule(enabled: Boolean) {
        settings.setLiveTransferCapsuleEnabled(enabled)
        _uiState.update { it.copy(liveTransferCapsuleEnabled = enabled) }
    }

    fun setLiveTransferShowQueue(enabled: Boolean) {
        settings.setLiveTransferShowQueueEnabled(enabled)
        _uiState.update { it.copy(liveTransferShowQueueEnabled = enabled) }
    }

    fun setAppTheme(theme: AppTheme) {
        settings.setAppTheme(theme)
        _uiState.update { it.copy(appTheme = theme) }
    }



    fun setPinRequired(enabled: Boolean) {
        settings.setPinRequiredEnabled(enabled)
        val pin = settings.devicePin.value
        _uiState.update {
            it.copy(
                pinRequiredEnabled = enabled,
                pinError = when {
                    enabled && pin.length < 4 -> AppI18n.t("enter_pin_4_8")
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
                    pinError = AppI18n.t("pin_must_4_8")
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
                it.copy(googleAccountError = errorMessage ?: AppI18n.t("google_signin_cancelled"))
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
                    it.copy(googleAccountError = error.message ?: AppI18n.t("google_link_failed"))
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
                println("SettingsViewModel: diagnostics cloud sync failed - ${error.message}")
            }
            if (enabled) {
                DiagnosticsCloudRelay.startInbox(uid, deviceId)
            } else {
                DiagnosticsCloudRelay.stopInbox()
            }
        }
    }

    fun setCellularEnabled(enabled: Boolean) {
        settings.setCellularEnabled(enabled)
        _uiState.update { it.copy(cellularEnabled = enabled) }
        com.fileapex.cloud.drive.DriveRelayCoordinator.applySchedulerFromSettings()
    }

    fun setAllowRemoteFileDeletion(enabled: Boolean) {
        settings.setBulletinRemoteFilePurgePreference(
            if (enabled) {
                BulletinRemoteFilePurgePreference.ENABLED
            } else {
                BulletinRemoteFilePurgePreference.DISABLED
            }
        )
        _uiState.update { it.copy(allowRemoteFileDeletion = enabled) }
    }

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

    fun setDrivePurgeAfter72Hours(enabled: Boolean) {
        settings.setDrivePurgeAfter72Hours(enabled)
        _uiState.update { it.copy(drivePurgeAfter72Hours = enabled) }
    }

    fun purgeDriveRelayNow() {
        if (_uiState.value.drivePurgeNowBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(drivePurgeNowBusy = true, drivePurgeNowMessage = null) }
            runCatching { com.fileapex.cloud.drive.DriveRelayCoordinator.purgeRelayNow() }
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
