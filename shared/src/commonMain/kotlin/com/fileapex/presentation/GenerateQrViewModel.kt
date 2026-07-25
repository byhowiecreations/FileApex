package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.pairing.PairingPayloadFactory
import com.fileapex.util.NetworkUtils
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
    /** Set when a new device pairs while this QR is shown; screen stays open for more pairings. */
    val pairedDeviceName: String? = null
)

/**
 * Shows a QR for inbound pairing and dismisses automatically when Room reports a newly paired device.
 */
class GenerateQrViewModel : ViewModel() {
    private val identity: LocalIdentity = FileApexServices.localIdentity
    private val deviceRepository = FileApexServices.deviceRepository
    private val baselineDeviceIds = mutableSetOf<String>()

    private val _uiState = MutableStateFlow(GenerateQrUiState())
    val uiState: StateFlow<GenerateQrUiState> = _uiState.asStateFlow()

    init {
        observeIncomingPairings()
    }

    /** Call each time the Generate QR screen is shown (resets auto-close state from prior visits). */
    fun onScreenEntered() {
        viewModelScope.launch {
            baselineDeviceIds.clear()
            baselineDeviceIds.addAll(deviceRepository.listDevices().map { it.deviceId })
            _uiState.update { it.copy(pairedDeviceName = null) }
            refresh()
        }
    }

    fun refresh() {
        val live = FileApexServices.localIdentity
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
                payload = PairingPayloadFactory.create(
                    deviceId = live.deviceId,
                    deviceName = live.deviceName,
                    host = host,
                    port = live.sharePort,
                    rootPath = live.rootPath,
                    pinRequired = FileApexServices.settings.pinRequiredEnabled.value
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
                    if (baselineDeviceIds.isEmpty()) return@collect
                    val newlyPaired = devices.firstOrNull { (id, _) -> id !in baselineDeviceIds }
                    if (newlyPaired != null) {
                        baselineDeviceIds.add(newlyPaired.first)
                        _uiState.update {
                            it.copy(pairedDeviceName = newlyPaired.second)
                        }
                    }
                }
        }
    }
}
