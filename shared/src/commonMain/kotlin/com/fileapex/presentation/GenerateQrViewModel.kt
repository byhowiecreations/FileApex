package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.HostPairingBroadcastState
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.pairing.PairingPayloadFactory
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GenerateQrUiState(
    val payload: PairingPayload? = null,
    val errorMessage: String? = null,
    val preparingShareServer: Boolean = false,
    /** Set when a new device pairs while this QR is shown; screen stays open for more pairings. */
    val pairedDeviceName: String? = null,
    val broadcast: HostPairingBroadcastState = HostPairingBroadcastState()
)

/**
 * Shows a QR for inbound pairing and dismisses automatically when Room reports a newly paired device.
 */
class GenerateQrViewModel : ViewModel() {
    private val deviceRepository = FileApexServices.deviceRepository
    private val baselineDeviceIds = mutableSetOf<String>()
    private var hostTicker: Job? = null
    private var serverWarmupJob: Job? = null

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
            refresh(restartBroadcast = true)
        }
    }

    fun onScreenLeft() {
        hostTicker?.cancel()
        hostTicker = null
        serverWarmupJob?.cancel()
        serverWarmupJob = null
        LanPairingDiscovery.stopHost()
        _uiState.update {
            it.copy(
                broadcast = HostPairingBroadcastState(remainingLabel = "0:00"),
                preparingShareServer = false
            )
        }
    }

    fun retry() {
        refresh(restartBroadcast = true)
    }

    fun refresh() {
        refresh(restartBroadcast = true)
    }

    private fun refresh(restartBroadcast: Boolean) {
        serverWarmupJob?.cancel()
        serverWarmupJob = viewModelScope.launch {
            val built = withContext(Dispatchers.IO) {
                val host = NetworkUtils.preferredLanIpv4()
                if (!NetworkUtils.isUsableLanIpv4(host)) {
                    return@withContext null
                }
                val live = FileApexServices.localIdentity
                val freshRandomCode = PairingPayload.generatePairingCode(
                    exclude = LanPairingDiscovery.lastUsedCode().orEmpty()
                )
                PairingPayloadFactory.create(
                    deviceId = live.deviceId,
                    deviceName = live.deviceName,
                    host = host,
                    port = live.sharePort,
                    rootPath = live.rootPath,
                    pinRequired = FileApexServices.settings.pinRequiredEnabled.value,
                    pairingCode = freshRandomCode
                )
            }

            if (built == null) {
                LanPairingDiscovery.stopHost()
                _uiState.update {
                    it.copy(
                        payload = null,
                        errorMessage = "No LAN IPv4 address found. Join Wi‑Fi and retry.",
                        broadcast = HostPairingBroadcastState(remainingLabel = "0:00"),
                        preparingShareServer = false
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    errorMessage = null,
                    payload = built,
                    pairedDeviceName = null,
                    preparingShareServer = true
                )
            }

            withContext(Dispatchers.IO) {
                if (restartBroadcast) {
                    LanPairingDiscovery.startHost(built)
                }
                ServerLifecycleManager.ensureRunning()
            }

            _uiState.update { it.copy(preparingShareServer = false) }
            if (restartBroadcast) {
                startHostTicker()
            }
        }
    }

    override fun onCleared() {
        onScreenLeft()
        super.onCleared()
    }

    private fun startHostTicker() {
        hostTicker?.cancel()
        hostTicker = viewModelScope.launch {
            while (isActive) {
                val broadcast = LanPairingDiscovery.tickHost()
                _uiState.update { it.copy(broadcast = broadcast) }
                if (!broadcast.active) break
                delay(250)
            }
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
                        LanPairingDiscovery.onHostPairingAccepted()
                        hostTicker?.cancel()
                        _uiState.update {
                            it.copy(
                                pairedDeviceName = newlyPaired.second,
                                broadcast = HostPairingBroadcastState(remainingLabel = "0:00"),
                                preparingShareServer = false
                            )
                        }
                    }
                }
        }
    }
}
