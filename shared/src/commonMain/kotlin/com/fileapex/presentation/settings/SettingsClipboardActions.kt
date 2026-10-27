package com.fileapex.presentation.settings

import com.fileapex.domain.clipboard.applyAutoDefaultClipboardTarget
import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.fileapex.presentation.SettingsUiState
import kotlinx.coroutines.CoroutineScope

internal class SettingsClipboardActions(
    private val _uiState: MutableStateFlow<SettingsUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val settings = FileApexServices.settings

    fun setClipboardSharing(enabled: Boolean) {
        settings.setClipboardSharingEnabled(enabled)
        _uiState.update { it.copy(clipboardSharingEnabled = enabled) }
        com.fileapex.platform.ClipboardShareChrome.fire()
        if (enabled) {
            viewModelScope.launch {
                val paired = FileApexServices.deviceRepository.listDevices()
                val peers = paired.map {
                    ClipboardSharePolicy.PeerRef(
                        deviceId = it.deviceId,
                        isDesktop = com.fileapex.domain.peer.PeerPlatform.isDesktop(it.os, it.platform)
                    )
                }
                val selfIsAndroid = currentPlatformLabel() == "Android"
                val isConfigured = settings.clipboardTargetConfigured.value

                if (!isConfigured) {
                    settings.applyAutoDefaultClipboardTarget(selfIsAndroid, peers)?.let { defaultTargetId ->
                        _uiState.update {
                            it.copy(
                                clipboardShareMode = ClipboardShareMode.SPECIFIC,
                                clipboardTargetDeviceIds = setOf(defaultTargetId)
                            )
                        }
                    }
                }

                if (com.fileapex.domain.clipboard.ClipboardSharePolicy.shouldPromptTargetConfiguration(isConfigured, selfIsAndroid, peers)) {
                    _uiState.update {
                        it.copy(
                            showClipboardConfigDialog = true,
                            clipboardConfigPeers = paired
                        )
                    }
                } else {
                    com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboard()
                }
            }
        }
    }

    fun dismissClipboardConfigDialog() {
        _uiState.update { it.copy(showClipboardConfigDialog = false) }
    }

    fun confirmClipboardConfig(mode: ClipboardShareMode, targetIds: Set<String>) {
        settings.setClipboardShareMode(mode)
        settings.setClipboardTargetDeviceIds(targetIds)
        settings.setClipboardTargetConfigured(true)
        _uiState.update {
            it.copy(
                showClipboardConfigDialog = false,
                clipboardShareMode = mode,
                clipboardTargetDeviceIds = targetIds
            )
        }
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboard()
    }

    fun setClipboardDisclosureAcknowledged(acknowledged: Boolean) {
        settings.setClipboardDisclosureAcknowledged(acknowledged)
        _uiState.update { it.copy(clipboardDisclosureAcknowledged = acknowledged) }
    }

    fun setAccessibilityDisclosureAcknowledged(acknowledged: Boolean) {
        settings.setAccessibilityDisclosureAcknowledged(acknowledged)
        _uiState.update { it.copy(accessibilityDisclosureAcknowledged = acknowledged) }
    }

    fun setInstallPackagesDisclosureAcknowledged(acknowledged: Boolean) {
        settings.setInstallPackagesDisclosureAcknowledged(acknowledged)
        _uiState.update { it.copy(installPackagesDisclosureAcknowledged = acknowledged) }
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
        if (enabled && !settings.googleAccountLinkEnabled.value) return
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

    fun setClipboardSendNotification(enabled: Boolean) {
        settings.setClipboardSendNotificationEnabled(enabled)
        _uiState.update { it.copy(clipboardSendNotificationEnabled = enabled) }
    }

    fun setClipboardShizuku(enabled: Boolean) {
        if (enabled) {
            com.fileapex.platform.ClipboardRuntimeDiagnostics.activateShizuku()
        } else {
            settings.setClipboardShizukuEnabled(false)
        }
        _uiState.update { it.copy(clipboardShizukuEnabled = enabled) }
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
}
