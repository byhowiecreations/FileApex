package com.fileapex.presentation

import com.fileapex.presentation.settings.SettingsClipboardActions
import com.fileapex.presentation.settings.SettingsDeviceNameActions
import com.fileapex.presentation.settings.SettingsDriveActions
import com.fileapex.presentation.settings.SettingsGoogleActions
import com.fileapex.presentation.settings.SettingsStateMirror
import com.fileapex.presentation.settings.SettingsUpdateActions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.PeerDeviceNameLabel
import com.fileapex.data.identity.DeviceNamePeerLabelsStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.settings.PinIdleTimeout
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.domain.diagnostics.DeviceDetailsFieldId
import com.fileapex.platform.BootLaunchPreference
import com.fileapex.platform.ServiceWatchdog
import com.fileapex.update.AppUpdateCoordinator
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.BulletinBoardStyle
import com.fileapex.data.settings.BulletinRemoteFilePurgePreference
import com.fileapex.data.settings.DriveRelayMaxMb

data class SettingsUiState(
    val googleAccountLinkEnabled: Boolean = false,
    val googleAccountEmail: String = "",
    val clipboardSharingEnabled: Boolean = false,
    val clipboardDisclosureAcknowledged: Boolean = false,
    val accessibilityDisclosureAcknowledged: Boolean = false,
    val installPackagesDisclosureAcknowledged: Boolean = false,
    val clipboardShareMode: ClipboardShareMode = ClipboardShareMode.UNSET,
    val clipboardTargetDeviceIds: Set<String> = emptySet(),
    val clipboardViaCellularEnabled: Boolean = false,
    val clipboardAccessibilityEnabled: Boolean = false,
    val clipboardSendNotificationEnabled: Boolean = false,
    val clipboardShizukuEnabled: Boolean = false,
    val clipboardAutoSendEnabled: Boolean = false,
    val clipboardPeers: List<PairedDeviceEntity> = emptyList(),
    val showClipboardConfigDialog: Boolean = false,
    val clipboardConfigPeers: List<PairedDeviceEntity> = emptyList(),
    val fileTransferNotificationsEnabled: Boolean = false,
    val driveRelayNotificationsEnabled: Boolean = false,
    val notesNotificationsEnabled: Boolean = false,
    val liveTransferCapsuleEnabled: Boolean = false,
    val liveTransferShowQueueEnabled: Boolean = false,
    val appTheme: AppTheme = AppTheme.DEFAULT,
    val themeIconStyle: ThemeIconStyle = ThemeIconStyle.STANDARD,
    val kineticStyle: KineticStyle = KineticStyle.SPACE,
    val bulletinBoardStyle: BulletinBoardStyle = BulletinBoardStyle.DEFAULT,
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
    val driveRelayEncryptionEnabled: Boolean = false,
    val allowRemoteFileDeletion: Boolean = false,
    val googleDriveAuthError: String? = null,
    val drivePurgeNowBusy: Boolean = false,
    val drivePurgeNowMessage: String? = null,
    val kineticSphereCleanMode: Boolean = false,
    val kineticSphereConnectedLinesEnabled: Boolean = true,
    val kineticSphereOrbitalRingsEnabled: Boolean = true,
    val kineticSpherePersistentWallpaperEnabled: Boolean = false,
    val generalExpanded: Boolean = true,
    val systemPerformanceExpanded: Boolean = true,
    val appearanceBehaviorExpanded: Boolean = true,
    val securityAccountExpanded: Boolean = true,
    val remoteAccessExpanded: Boolean = true,
    val showAdvancedSettings: Boolean = false,
    val tailscaleSetupExpanded: Boolean = false,
    val showAccessibilityRestrictedHelp: Boolean = false,
    val deviceNameBroadcasting: String = "",
    val deviceNameDraft: String = "",
    val deviceNamePeerLabels: List<PeerDeviceNameLabel> = emptyList(),
    val deviceNameClusterSync: String = "",
    val deviceNameError: String? = null,
    val deviceNameStatus: String? = null,
    val deviceNameSaveBusy: Boolean = false
)

class SettingsViewModel : ViewModel() {
    private val settings = FileApexServices.settings
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            googleAccountLinkEnabled = settings.googleAccountLinkEnabled.value,
            googleAccountEmail = settings.googleAccountEmail.value,
            clipboardSharingEnabled = settings.clipboardSharingEnabled.value,
            clipboardDisclosureAcknowledged = settings.clipboardDisclosureAcknowledged.value,
            accessibilityDisclosureAcknowledged = settings.accessibilityDisclosureAcknowledged.value,
            installPackagesDisclosureAcknowledged = settings.installPackagesDisclosureAcknowledged.value,
            clipboardShareMode = settings.clipboardShareMode.value,
            clipboardTargetDeviceIds = settings.clipboardTargetDeviceIds.value,
            clipboardViaCellularEnabled = settings.clipboardViaCellularEnabled.value,
            clipboardAccessibilityEnabled = settings.clipboardAccessibilityEnabled.value,
            clipboardSendNotificationEnabled = settings.clipboardSendNotificationEnabled.value,
            clipboardShizukuEnabled = settings.clipboardShizukuEnabled.value,
            clipboardAutoSendEnabled = settings.clipboardAutoSendEnabled.value,
            fileTransferNotificationsEnabled = settings.fileTransferNotificationsEnabled.value,
            driveRelayNotificationsEnabled = settings.driveRelayNotificationsEnabled.value,
            notesNotificationsEnabled = settings.notesNotificationsEnabled.value,
            liveTransferCapsuleEnabled = settings.liveTransferCapsuleEnabled.value,
            liveTransferShowQueueEnabled = settings.liveTransferShowQueueEnabled.value,
            appTheme = settings.appTheme.value,
            themeIconStyle = settings.themeIconStyle.value,
            kineticStyle = settings.kineticStyle.value,
            bulletinBoardStyle = settings.bulletinBoardStyle.value,
            pinRequiredEnabled = settings.pinRequiredEnabled.value,
            devicePin = settings.devicePin.value,
            pinIdleTimeout = settings.pinIdleTimeout.value,
            checkForUpdatesEnabled = settings.checkForUpdatesEnabled.value,
            checkForUpdatesIntervalUnit = settings.checkForUpdatesIntervalUnit.value,
            checkForUpdatesIntervalAmount = settings.checkForUpdatesIntervalAmount.value,
            checkForUpdatesAmountText = settings.checkForUpdatesIntervalAmount.value.toString(),
            enableServiceWatchdog = settings.enableServiceWatchdog.value,
            autoLaunchOnReboot = if (BootLaunchPreference.usesDesktopAutostart()) settings.desktopAutostart.value else settings.autoLaunchOnReboot.value,
            desktopLayoutMode = settings.desktopLayoutMode.value,
            desktopUiStyle = settings.desktopUiStyle.value,
            deviceDetailsDisplayPreferences = settings.deviceDetailsDisplayPreferences.value,
            deviceDetailsAllowOverCellular = settings.deviceDetailsAllowOverCellular.value,
            cellularEnabled = settings.cellularEnabled.value,
            googleDriveRelayEnabled = settings.googleDriveRelayEnabled.value,
            driveRelayMaxMb = settings.driveRelayMaxMb.value,
            drivePurgeAfter72Hours = settings.drivePurgeAfter72Hours.value,
            driveRelayEncryptionEnabled = settings.driveRelayEncryptionEnabled.value,
            allowRemoteFileDeletion =
                settings.bulletinRemoteFilePurgePreference.value == BulletinRemoteFilePurgePreference.ENABLED,
            kineticSphereCleanMode = settings.kineticSphereCleanMode.value,
            kineticSphereConnectedLinesEnabled = settings.kineticSphereConnectedLinesEnabled.value,
            kineticSphereOrbitalRingsEnabled = settings.kineticSphereOrbitalRingsEnabled.value,
            kineticSpherePersistentWallpaperEnabled = settings.kineticSpherePersistentWallpaperEnabled.value,
            generalExpanded = settings.settingsGroupGeneralExpanded.value,
            systemPerformanceExpanded = settings.settingsGroupSystemPerformanceExpanded.value,
            appearanceBehaviorExpanded = settings.settingsGroupAppearanceBehaviorExpanded.value,
            securityAccountExpanded = settings.settingsGroupSecurityAccountExpanded.value,
            remoteAccessExpanded = settings.settingsGroupRemoteAccessExpanded.value,
            showAdvancedSettings = settings.showAdvancedSettings.value,
            tailscaleSetupExpanded = settings.tailscaleSetupExpanded.value
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val clipboard = SettingsClipboardActions(_uiState, viewModelScope)
    private val updates = SettingsUpdateActions(_uiState)
    private val drive = SettingsDriveActions(_uiState, viewModelScope)
    private val deviceName = SettingsDeviceNameActions(_uiState, viewModelScope)
    private val google = SettingsGoogleActions(_uiState, viewModelScope)

    init {
        viewModelScope.launch {
            FileApexServices.awaitBootstrap()
            DeviceNamePeerLabelsStore.ensureLoaded()
            LocalDeviceNameStore.deviceName.collect { name ->
                _uiState.update { state ->
                    val syncedDraft = state.deviceNameDraft == state.deviceNameBroadcasting
                    state.copy(
                        deviceNameBroadcasting = name,
                        deviceNameDraft = if (syncedDraft) name else state.deviceNameDraft
                    )
                }
            }
        }
        viewModelScope.launch {
            DeviceNamePeerLabelsStore.labels.collect { labels ->
                _uiState.update { it.copy(deviceNamePeerLabels = labels) }
            }
        }
        viewModelScope.launch {
            settings.googleAccountLinkEnabled.collect {
                deviceName.refreshDeviceNameClusterSync()
            }
        }
        viewModelScope.launch {
            FileApexServices.awaitBootstrap()
            FileApexServices.deviceRepository.observeDevices().collect { devices ->
                _uiState.update { it.copy(clipboardPeers = devices) }
                deviceName.refreshDeviceNameClusterSync()
            }
        }
        SettingsStateMirror(_uiState, viewModelScope).start()
    }

    fun toggleGeneralGroup() {
        val newExpanded = !_uiState.value.generalExpanded
        settings.setSettingsGroupGeneralExpanded(newExpanded)
        _uiState.update { it.copy(generalExpanded = newExpanded) }
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

    fun setTailscaleSetupExpanded(expanded: Boolean) {
        settings.setTailscaleSetupExpanded(expanded)
        _uiState.update { it.copy(tailscaleSetupExpanded = expanded) }
    }

    fun setShowAdvancedSettings(show: Boolean) {
        settings.setShowAdvancedSettings(show)
        _uiState.update { it.copy(showAdvancedSettings = show) }
    }

    fun toggleRemoteAccessGroup() {
        val newExpanded = !_uiState.value.remoteAccessExpanded
        settings.setSettingsGroupRemoteAccessExpanded(newExpanded)
        _uiState.update { it.copy(remoteAccessExpanded = newExpanded) }
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

    fun setKineticSpherePersistentWallpaperEnabled(enabled: Boolean) {
        settings.setKineticSpherePersistentWallpaperEnabled(enabled)
        _uiState.update { it.copy(kineticSpherePersistentWallpaperEnabled = enabled) }
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
        if (BootLaunchPreference.usesDesktopAutostart()) settings.setDesktopAutostart(enabled) else settings.setAutoLaunchOnReboot(enabled)
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

    fun setAppTheme(theme: AppTheme) {
        settings.setAppTheme(theme)
        _uiState.update { it.copy(appTheme = theme, themeIconStyle = settings.themeIconStyle.value) }
    }

    fun setThemeIconStyle(theme: AppTheme, style: ThemeIconStyle) {
        settings.setThemeIconStyle(theme, style)
        _uiState.update { it.copy(themeIconStyle = settings.themeIconStyle.value) }
    }

    fun setKineticStyle(style: KineticStyle) {
        settings.setKineticStyle(style)
        _uiState.update { it.copy(kineticStyle = style) }
    }

    fun setBulletinBoardStyle(style: BulletinBoardStyle) {
        settings.setBulletinBoardStyle(style)
        _uiState.update { it.copy(bulletinBoardStyle = style) }
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

    /** Force an immediate update check from repeated Settings version taps. */
    fun onVersionNumberEasterEgg() {
        AppUpdateCoordinator.checkNowManual().also { com.fileapex.notice.NoticeCoordinator.checkNow() }
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
            cancellableCatching {
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

    fun setClipboardSharing(enabled: Boolean) = clipboard.setClipboardSharing(enabled)

    fun dismissClipboardConfigDialog() = clipboard.dismissClipboardConfigDialog()

    fun confirmClipboardConfig(mode: ClipboardShareMode, targetIds: Set<String>) = clipboard.confirmClipboardConfig(mode, targetIds)

    fun setClipboardDisclosureAcknowledged(acknowledged: Boolean) = clipboard.setClipboardDisclosureAcknowledged(acknowledged)

    fun setAccessibilityDisclosureAcknowledged(acknowledged: Boolean) = clipboard.setAccessibilityDisclosureAcknowledged(acknowledged)

    fun setInstallPackagesDisclosureAcknowledged(acknowledged: Boolean) = clipboard.setInstallPackagesDisclosureAcknowledged(acknowledged)

    fun setClipboardShareMode(mode: ClipboardShareMode) = clipboard.setClipboardShareMode(mode)

    fun setClipboardTargetDevice(deviceId: String, selected: Boolean) = clipboard.setClipboardTargetDevice(deviceId, selected)

    fun setClipboardViaCellular(enabled: Boolean) = clipboard.setClipboardViaCellular(enabled)

    fun setClipboardAutoSend(enabled: Boolean) = clipboard.setClipboardAutoSend(enabled)

    fun setClipboardAccessibility(enabled: Boolean) = clipboard.setClipboardAccessibility(enabled)

    fun setClipboardSendNotification(enabled: Boolean) = clipboard.setClipboardSendNotification(enabled)

    fun setClipboardShizuku(enabled: Boolean) = clipboard.setClipboardShizuku(enabled)

    fun dismissAccessibilityRestrictedHelp() = clipboard.dismissAccessibilityRestrictedHelp()

    fun openAccessibilityAppInfo() = clipboard.openAccessibilityAppInfo()

    fun openAccessibilitySystemSettings() = clipboard.openAccessibilitySystemSettings()

    fun sendClipboardNow() = clipboard.sendClipboardNow()

    fun setCheckForUpdates(enabled: Boolean) = updates.setCheckForUpdates(enabled)

    fun setCheckForUpdatesUnit(unit: UpdateCheckUnit) = updates.setCheckForUpdatesUnit(unit)

    fun setCheckForUpdatesAmountText(raw: String) = updates.setCheckForUpdatesAmountText(raw)

    fun setCheckForUpdatesWeekAmount(amount: Int) = updates.setCheckForUpdatesWeekAmount(amount)

    fun setGoogleDriveRelayEnabled(enabled: Boolean) = drive.setGoogleDriveRelayEnabled(enabled)

    fun setDriveRelayMaxMb(limit: DriveRelayMaxMb) = drive.setDriveRelayMaxMb(limit)

    fun setDriveRelayEncryptionEnabled(enabled: Boolean) = drive.setDriveRelayEncryptionEnabled(enabled)

    fun setDrivePurgeAfter72Hours(enabled: Boolean) = drive.setDrivePurgeAfter72Hours(enabled)

    fun purgeDriveRelayNow() = drive.purgeDriveRelayNow()

    fun onGoogleDriveAuthResult(granted: Boolean, errorMessage: String?) = drive.onGoogleDriveAuthResult(granted, errorMessage)

    fun setDeviceNameDraft(value: String) = deviceName.setDeviceNameDraft(value)

    fun saveDeviceName() = deviceName.saveDeviceName()

    /** Credential Manager / desktop OAuth returned a Google ID token. */
    fun onGoogleIdToken(idToken: String?, emailHint: String?, errorMessage: String?) = google.onGoogleIdToken(idToken, emailHint, errorMessage)

    fun disableGoogleAccountLink() = google.disableGoogleAccountLink()
}
