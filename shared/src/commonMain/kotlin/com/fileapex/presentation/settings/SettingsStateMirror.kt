package com.fileapex.presentation.settings

import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.fileapex.data.settings.BulletinRemoteFilePurgePreference
import com.fileapex.presentation.SettingsUiState
import kotlinx.coroutines.CoroutineScope

internal class SettingsStateMirror(
    private val _uiState: MutableStateFlow<SettingsUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val settings = FileApexServices.settings

    fun start() {
        viewModelScope.launch {
            settings.clipboardSharingEnabled.collect { enabled ->
                _uiState.update { it.copy(clipboardSharingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.clipboardDisclosureAcknowledged.collect { ack ->
                _uiState.update { it.copy(clipboardDisclosureAcknowledged = ack) }
            }
        }
        viewModelScope.launch {
            settings.accessibilityDisclosureAcknowledged.collect { ack ->
                _uiState.update { it.copy(accessibilityDisclosureAcknowledged = ack) }
            }
        }
        viewModelScope.launch {
            settings.installPackagesDisclosureAcknowledged.collect { ack ->
                _uiState.update { it.copy(installPackagesDisclosureAcknowledged = ack) }
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
            settings.clipboardSendNotificationEnabled.collect { enabled ->
                _uiState.update { it.copy(clipboardSendNotificationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.clipboardShizukuEnabled.collect { enabled ->
                _uiState.update { it.copy(clipboardShizukuEnabled = enabled) }
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
            settings.kineticSpherePersistentWallpaperEnabled.collect { enabled ->
                _uiState.update { it.copy(kineticSpherePersistentWallpaperEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.settingsGroupGeneralExpanded.collect { expanded ->
                _uiState.update { it.copy(generalExpanded = expanded) }
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
            settings.showAdvancedSettings.collect { show ->
                _uiState.update { it.copy(showAdvancedSettings = show) }
            }
        }
        viewModelScope.launch {
            settings.settingsGroupRemoteAccessExpanded.collect { expanded ->
                _uiState.update { it.copy(remoteAccessExpanded = expanded) }
            }
        }
        viewModelScope.launch {
            settings.tailscaleSetupExpanded.collect { expanded ->
                _uiState.update { it.copy(tailscaleSetupExpanded = expanded) }
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
        viewModelScope.launch {
            settings.bulletinBoardStyle.collect { style ->
                _uiState.update { it.copy(bulletinBoardStyle = style) }
            }
        }
        viewModelScope.launch {
            settings.themeIconStyle.collect { style ->
                _uiState.update { it.copy(themeIconStyle = style) }
            }
        }
        viewModelScope.launch {
            settings.kineticStyle.collect { style ->
                _uiState.update { it.copy(kineticStyle = style) }
            }
        }
        viewModelScope.launch {
            settings.desktopLayoutMode.collect { mode ->
                _uiState.update { it.copy(desktopLayoutMode = mode) }
            }
        }
        viewModelScope.launch {
            settings.desktopUiStyle.collect { style ->
                _uiState.update { it.copy(desktopUiStyle = style) }
            }
        }
        viewModelScope.launch {
            settings.appTheme.collect { theme ->
                _uiState.update { it.copy(appTheme = theme) }
            }
        }
    }
}
