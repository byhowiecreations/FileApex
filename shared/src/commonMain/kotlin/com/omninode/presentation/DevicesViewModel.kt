package com.omninode.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.cloud.GoogleLinkCoordinator
import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.di.OmniNodeServices
import com.omninode.domain.pairing.PairingPayload
import com.omninode.network.sendWakeBroadcast
import com.omninode.platform.localIpv4Addresses
import com.omninode.session.DeviceSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class DevicesUiState(
    val pairedDevices: List<PairedDeviceEntity> = emptyList(),
    val onlineDeviceIds: Set<String> = emptySet(),
    val localDeviceName: String = "",
    val renameTargetId: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    /** When set, UI must collect a PIN before completing pairing. */
    val pendingPinPairing: PairingPayload? = null,
    /** When set, UI must collect a PIN before browsing a PIN-protected peer. */
    val pendingPinUnlock: PendingPinUnlock? = null,
    /** Restored LazyColumn first-visible index (survives leaving Devices). */
    val listScrollIndex: Int = 0,
    val listScrollOffset: Int = 0
)

data class PendingPinUnlock(
    val device: PairedDeviceEntity,
    val displayName: String
)

class DevicesViewModel : ViewModel() {
    private val repository = OmniNodeServices.deviceRepository
    private val presence = OmniNodeServices.presenceMonitor
    private val identity: LocalIdentity
        get() = OmniNodeServices.localIdentity

    private var pendingOpenAction: ((BrowseTarget) -> Unit)? = null

    private val _uiState = MutableStateFlow(
        DevicesUiState(localDeviceName = LocalDeviceNameStore.current())
    )
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    val devices = repository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        presence.start()
        LocalDeviceNameStore.ensureLoaded()
        viewModelScope.launch {
            LocalDeviceNameStore.deviceName.collect { name ->
                if (name.isNotBlank()) {
                    _uiState.update { it.copy(localDeviceName = name) }
                }
            }
        }
        viewModelScope.launch {
            devices.collect { list ->
                _uiState.update { it.copy(pairedDevices = list) }
            }
        }
        viewModelScope.launch {
            presence.onlineDeviceIds.collect { online ->
                _uiState.update { it.copy(onlineDeviceIds = online) }
            }
        }
    }

    fun isDeviceOnline(deviceId: String): Boolean = deviceId in _uiState.value.onlineDeviceIds

    fun openDeviceOrExplain(device: PairedDeviceEntity, open: (BrowseTarget) -> Unit) {
        if (!isDeviceOnline(device.deviceId)) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(statusMessage = "Waking ${device.deviceName}…")
                }
                withContext(Dispatchers.IO) {
                    runCatching { sendWakeBroadcast() }
                }
                repeat(WAKE_POLL_ATTEMPTS) {
                    delay(WAKE_POLL_INTERVAL_MS)
                    runCatching { presence.refreshNow() }
                    if (isDeviceOnline(device.deviceId)) {
                        continueOpenDevice(device, open)
                        return@launch
                    }
                }
                _uiState.update {
                    it.copy(
                        statusMessage = "${device.deviceName} is offline — open OmniNode on that device"
                    )
                }
            }
            return
        }
        continueOpenDevice(device, open)
    }

    private fun continueOpenDevice(device: PairedDeviceEntity, open: (BrowseTarget) -> Unit) {
        if (DeviceSessionManager.isSessionValid(device.deviceId)) {
            DeviceSessionManager.markDeviceAccessed(device.deviceId)
            open(browseTargetFor(device, pinRequired = true))
            return
        }
        viewModelScope.launch {
            runCatching {
                val remote = OmniNodeServices.client.fetchIdentity(device.lastKnownIp, device.port)
                if (remote.pinRequired) {
                    pendingOpenAction = open
                    val name = remote.deviceName.ifBlank { device.deviceName }
                    _uiState.update {
                        it.copy(
                            pendingPinUnlock = PendingPinUnlock(device = device, displayName = name),
                            statusMessage = "Enter PIN for $name"
                        )
                    }
                    return@launch
                }
                open(browseTargetFor(device, pinRequired = false))
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Could not reach ${device.deviceName}")
                }
            }
        }
    }

    /**
     * Dual-pairing handshake after scanning a broadcaster QR.
     * If the target requires a PIN, defer until [confirmPinPairing].
     */
    fun pairFromQrPayload(payload: PairingPayload) {
        viewModelScope.launch {
            runCatching {
                if (payload.deviceId == identity.deviceId) {
                    error("You scanned this device's own QR code")
                }
                val verified = runCatching {
                    OmniNodeServices.client.fetchIdentity(payload.host, payload.port)
                }.getOrNull()
                val pinRequired = verified?.pinRequired == true || payload.pinRequired
                if (pinRequired) {
                    _uiState.update {
                        it.copy(
                            pendingPinPairing = payload.copy(pinRequired = true),
                            statusMessage = "Enter PIN for ${verified?.deviceName ?: payload.deviceName}"
                        )
                    }
                    return@launch
                }
                completePairing(payload, pin = null)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Pairing failed")
                }
            }
        }
    }

    fun cancelPinPairing() {
        _uiState.update { it.copy(pendingPinPairing = null) }
    }

    fun confirmPinPairing(pin: String) {
        val payload = _uiState.value.pendingPinPairing ?: return
        viewModelScope.launch {
            runCatching {
                require(pin.isNotBlank()) { "PIN is required" }
                completePairing(payload, pin = pin.trim())
                _uiState.update { it.copy(pendingPinPairing = null) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Pairing failed")
                }
            }
        }
    }

    fun cancelPinUnlock() {
        pendingOpenAction = null
        _uiState.update { it.copy(pendingPinUnlock = null) }
    }

    fun confirmPinUnlock(pin: String) {
        val pending = _uiState.value.pendingPinUnlock ?: return
        viewModelScope.launch {
            runCatching {
                require(pin.isNotBlank()) { "PIN is required" }
                OmniNodeServices.client.verifyPin(
                    host = pending.device.lastKnownIp,
                    port = pending.device.port,
                    pin = pin.trim()
                )
                DeviceSessionManager.markDeviceAccessed(pending.device.deviceId)
                val action = pendingOpenAction
                pendingOpenAction = null
                _uiState.update { it.copy(pendingPinUnlock = null) }
                action?.invoke(browseTargetFor(pending.device, pinRequired = true))
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Incorrect PIN")
                }
            }
        }
    }

    private suspend fun completePairing(payload: PairingPayload, pin: String?) {
        val verified = runCatching {
            OmniNodeServices.client.fetchIdentity(payload.host, payload.port)
        }.getOrNull()

        val broadcasterId = verified?.deviceId ?: payload.deviceId
        val broadcasterName = verified?.deviceName ?: payload.deviceName
        val broadcasterRoot = verified?.rootPath ?: payload.rootPath

        val broadcasterEntity = PairedDeviceEntity(
            deviceId = broadcasterId,
            deviceName = broadcasterName,
            lastKnownIp = payload.host,
            port = payload.port,
            publicKeyHash = payload.publicKeyHash,
            rootPath = broadcasterRoot
        )
        repository.upsertReplacingAliases(broadcasterEntity)

        val scannerHost = localIpv4Addresses().firstOrNull()
            ?: error("No LAN IPv4 address available for reverse pairing")
        val scannerEntity = PairedDeviceEntity(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            lastKnownIp = scannerHost,
            port = identity.sharePort,
            publicKeyHash = "",
            rootPath = identity.rootPath
        )
        OmniNodeServices.client.postPairingRespond(
            host = payload.host,
            port = payload.port,
            scannerDevice = scannerEntity,
            pin = pin
        )
        if (!pin.isNullOrBlank()) {
            OmniNodeServices.client.rememberSessionPin(payload.host, payload.port, pin)
            DeviceSessionManager.markDeviceAccessed(broadcasterId)
        }
        OmniNodeServices.pairingCoordinator.afterOutboundPair(broadcasterEntity)

        _uiState.update {
            it.copy(statusMessage = "Paired with $broadcasterName (cluster updated)")
        }
    }

    fun beginRename(deviceId: String) {
        _uiState.update { it.copy(renameTargetId = deviceId) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(renameTargetId = null) }
    }

    fun confirmRename(deviceId: String, newName: String) {
        viewModelScope.launch {
            runCatching {
                val trimmed = newName.trim()
                require(trimmed.isNotEmpty()) { "Name cannot be empty" }
                if (deviceId == LocalIdentity.LOCAL_DEVICE_ID) {
                    LocalDeviceNameStore.apply(trimmed)
                    OmniNodeServices.pairingCoordinator.broadcastSelfIdentity()
                    presence.refreshNow()
                    runCatching {
                        GoogleLinkCoordinator.publishUserRenamedDevice(deviceId, trimmed)
                    }
                    _uiState.update {
                        it.copy(
                            localDeviceName = trimmed,
                            renameTargetId = null,
                            statusMessage = "Renamed to $trimmed — synced to cluster"
                        )
                    }
                } else {
                    val peer = repository.getDevice(deviceId)
                        ?: error("Device not found")
                    val updated = peer.copy(deviceName = trimmed)
                    // Prefer direct rename API when the peer supports it (0.0.2b+).
                    val remoteOk = runCatching {
                        OmniNodeServices.client.postRemoteRename(
                            host = peer.lastKnownIp,
                            port = peer.port,
                            newName = trimmed
                        )
                    }.isSuccess
                    repository.upsertReplacingAliases(updated)
                    // Always fan-out via cluster merge (includes the target) so rosters
                    // update instantly — and 0.0.2b+ targets adopt the name even if
                    // /identity/rename is missing or the share server wasn't restarted.
                    OmniNodeServices.pairingCoordinator.broadcastDeviceUpdate(updated)
                    // Initiator is source of truth for Firestore deviceName (field patch only).
                    runCatching {
                        GoogleLinkCoordinator.publishUserRenamedDevice(deviceId, trimmed)
                    }
                    presence.refreshNow()
                    _uiState.update {
                        it.copy(
                            renameTargetId = null,
                            statusMessage = if (remoteOk) {
                                "Renamed to $trimmed — synced to cluster"
                            } else {
                                "Renamed to $trimmed — synced via cluster " +
                                    "(update the other device to 0.0.2b if its own name didn't change)"
                            }
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Rename failed")
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            repository.remove(deviceId)
            _uiState.update { it.copy(statusMessage = "Device removed") }
        }
    }

    fun refreshEndpoint(deviceId: String, host: String, port: Int) {
        viewModelScope.launch {
            repository.updateEndpoint(deviceId, host, port)
        }
    }

    fun thisDeviceTarget(): BrowseTarget {
        return BrowseTarget.Local(
            deviceId = LocalIdentity.LOCAL_DEVICE_ID,
            displayName = "This device (${LocalDeviceNameStore.current()})",
            rootPath = identity.rootPath
        )
    }

    fun browseTargetFor(device: PairedDeviceEntity, pinRequired: Boolean = false): BrowseTarget {
        return BrowseTarget.Remote(
            deviceId = device.deviceId,
            displayName = device.deviceName,
            host = device.lastKnownIp,
            port = device.port,
            rootPath = device.rootPath,
            pinRequired = pinRequired
        )
    }

    fun dismissMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun saveListScroll(index: Int, offset: Int) {
        _uiState.update {
            if (it.listScrollIndex == index && it.listScrollOffset == offset) it
            else it.copy(listScrollIndex = index, listScrollOffset = offset)
        }
    }

    companion object {
        private const val WAKE_POLL_ATTEMPTS = 10
        private const val WAKE_POLL_INTERVAL_MS = 500L
    }
}

sealed interface BrowseTarget {
    val deviceId: String
    val displayName: String
    val rootPath: String

    data class Local(
        override val deviceId: String,
        override val displayName: String,
        override val rootPath: String
    ) : BrowseTarget

    data class Remote(
        override val deviceId: String,
        override val displayName: String,
        val host: String,
        val port: Int,
        override val rootPath: String,
        /** Peer advertised PIN requirement; explorer re-checks session before navigation. */
        val pinRequired: Boolean = false
    ) : BrowseTarget
}
