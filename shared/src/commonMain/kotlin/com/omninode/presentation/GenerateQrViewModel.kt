package com.omninode.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.data.identity.LocalIdentity
import com.omninode.di.OmniNodeServices
import com.omninode.domain.pairing.PairingPayload
import com.omninode.domain.pairing.PairingPayloadFactory
import com.omninode.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GenerateQrUiState(
    val payload: PairingPayload? = null,
    val errorMessage: String? = null,
    val pairingCompleted: Boolean = false,
    val pairedDeviceName: String? = null
)

/**
 * Shows a QR for inbound pairing and dismisses automatically when Room reports a newly paired device.
 */
class GenerateQrViewModel : ViewModel() {
    private val identity: LocalIdentity = OmniNodeServices.localIdentity
    private val deviceRepository = OmniNodeServices.deviceRepository
    private val baselineDeviceIds = mutableSetOf<String>()
    private var baselineCaptured = false

    private val _uiState = MutableStateFlow(GenerateQrUiState())
    val uiState: StateFlow<GenerateQrUiState> = _uiState.asStateFlow()

    init {
        refresh()
        observeIncomingPairings()
    }

    fun refresh() {
        val live = OmniNodeServices.localIdentity
        val host = NetworkUtils.lanIpv4Addresses().sorted().firstOrNull()
        if (host == null) {
            _uiState.update {
                it.copy(
                    payload = null,
                    errorMessage = "No LAN IPv4 address found. Join Wi‑Fi and retry."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                errorMessage = null,
                pairingCompleted = false,
                pairedDeviceName = null,
                payload = PairingPayloadFactory.create(
                    deviceId = live.deviceId,
                    deviceName = live.deviceName,
                    host = host,
                    port = live.sharePort,
                    rootPath = live.rootPath,
                    pinRequired = OmniNodeServices.settings.pinRequiredEnabled.value
                )
            )
        }
    }

    private fun observeIncomingPairings() {
        viewModelScope.launch {
            deviceRepository.observeDevices()
                .map { devices -> devices.map { it.deviceId to it.deviceName } }
                .distinctUntilChanged()
                .collect { devices ->
                    val ids = devices.map { it.first }.toSet()
                    if (!baselineCaptured) {
                        baselineDeviceIds.clear()
                        baselineDeviceIds.addAll(ids)
                        baselineCaptured = true
                        return@collect
                    }
                    val newlyPaired = devices.firstOrNull { (id, _) -> id !in baselineDeviceIds }
                    if (newlyPaired != null && !_uiState.value.pairingCompleted) {
                        _uiState.update {
                            it.copy(
                                pairingCompleted = true,
                                pairedDeviceName = newlyPaired.second
                            )
                        }
                    }
                }
        }
    }
}
