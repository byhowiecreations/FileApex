package com.fileapex.presentation.devices

import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.PlatformClipboard
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class DevicesClipboardActions(
    private val _uiState: MutableStateFlow<DevicesUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val repository = FileApexServices.deviceRepository

    fun sendClipboardNow() {
        if (_uiState.value.isSendingClipboard) return
        val settings = FileApexServices.settings
        viewModelScope.launch {
            val paired = repository.listDevices()
            val peers = paired.map {
                ClipboardSharePolicy.PeerRef(
                    deviceId = it.deviceId,
                    isDesktop = PeerPlatform.isDesktop(it.os, it.platform)
                )
            }
            val selfIsAndroid = currentPlatformLabel() == "Android"
            val isConfigured = settings.clipboardTargetConfigured.value

            if (!isConfigured) {
                val defaultTargetId = ClipboardSharePolicy.resolveAutoDefaultTargetId(selfIsAndroid, peers)
                if (defaultTargetId != null) {
                    if (settings.clipboardShareMode.value == ClipboardShareMode.UNSET ||
                        settings.clipboardShareMode.value == ClipboardShareMode.SPECIFIC
                    ) {
                        settings.setClipboardShareMode(ClipboardShareMode.SPECIFIC)
                        settings.setClipboardTargetDeviceIds(setOf(defaultTargetId))
                    }
                }
            }

            if (ClipboardSharePolicy.shouldPromptTargetConfiguration(isConfigured, selfIsAndroid, peers)) {
                _uiState.update {
                    it.copy(
                        showClipboardConfigDialog = true,
                        clipboardConfigPeers = paired
                    )
                }
                return@launch
            }

            executeSendClipboard()
        }
    }

    fun dismissClipboardConfigDialog() {
        _uiState.update { it.copy(showClipboardConfigDialog = false) }
    }

    fun confirmClipboardConfig(mode: ClipboardShareMode, targetIds: Set<String>) {
        val settings = FileApexServices.settings
        settings.setClipboardShareMode(mode)
        settings.setClipboardTargetDeviceIds(targetIds)
        settings.setClipboardTargetConfigured(true)
        _uiState.update { it.copy(showClipboardConfigDialog = false) }
        executeSendClipboard()
    }

    private fun executeSendClipboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingClipboard = true) }
            val sending = AppI18n.t("sending_clipboard")
            _uiState.update { it.copy(statusMessage = sending) }
            try {
                val message = com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboardNow()
                if (message != sending) {
                    _uiState.update { it.copy(statusMessage = null, errorMessage = message) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                println("DevicesViewModel: clipboard push failed - ${t::class.simpleName}")
                _uiState.update { it.copy(statusMessage = null, errorMessage = t.message) }
            } finally {
                _uiState.update { it.copy(isSendingClipboard = false) }
            }
        }
    }

    fun sendClipboardToDevice(deviceId: String) {
        viewModelScope.launch {
            val settings = FileApexServices.settings
            if (!settings.clipboardSharingEnabled.value) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("clipboard_sharing_disabled_settings")) }
                return@launch
            }
            val text = PlatformClipboard.getSystemClipboardText()
            if (text.isNullOrBlank()) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("clipboard_empty")) }
                return@launch
            }
            val device = repository.getDevice(deviceId)
            if (device == null) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("device_not_found")) }
                return@launch
            }
            _uiState.update { it.copy(statusMessage = AppI18n.t("sending_clipboard")) }
            try {
                val response = com.fileapex.domain.clipboard.ClipboardShareCoordinator.sendToDevice(deviceId)
                val targetName = if (response.recipientDeviceName.isNotBlank()) response.recipientDeviceName else device.deviceName
                _uiState.update {
                    it.copy(
                        statusMessage = AppI18n.t("clipboard_send_success", targetName)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                println("DevicesViewModel: clipboard send failed - ${error::class.simpleName}")
                _uiState.update {
                    it.copy(
                        errorMessage = error.message ?: AppI18n.t("clipboard_send_failed", device.deviceName)
                    )
                }
            }
        }
    }
}
