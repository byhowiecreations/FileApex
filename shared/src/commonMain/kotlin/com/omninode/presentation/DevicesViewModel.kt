package com.omninode.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.cloud.GoogleLinkCoordinator
import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.di.OmniNodeServices
import com.omninode.domain.pairing.PairingPayload
import com.omninode.platform.purgeDirectShareTarget
import com.omninode.util.NetworkUtils
import com.omninode.session.DeviceSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ephemeral Devices-screen chrome only.
 *
 * Paired-device list rows live in [DevicesViewModel.deviceRows] so snackbars, dialogs,
 * and scroll bookmarks cannot force a structural list invalidation.
 */
data class DevicesUiState(
    val localDeviceName: String = "",
    val renameTargetId: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    /** When set, UI must collect a PIN before completing pairing. */
    val pendingPinPairing: PairingPayload? = null,
    /** When set, UI must collect a PIN before browsing a PIN-protected peer. */
    val pendingPinUnlock: PendingPinUnlock? = null
)

data class PendingPinUnlock(
    val device: PairedDeviceEntity,
    val displayName: String
)

class DevicesViewModel : ViewModel() {
    private val repository = OmniNodeServices.deviceRepository
    private val presence = OmniNodeServices.presenceMonitor
    private val transferManager = OmniNodeServices.transferManager
    private val identity: LocalIdentity
        get() = OmniNodeServices.localIdentity

    private var pendingOpenAction: ((BrowseTarget) -> Unit)? = null

    /** Scroll bookmark — not part of reactive UI state (avoids list recomposition on scroll). */
    private var listScrollIndex: Int = 0
    private var listScrollOffset: Int = 0

    private val _uiState = MutableStateFlow(
        DevicesUiState(localDeviceName = LocalDeviceNameStore.current())
    )
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    /**
     * Diffed device rows for LazyColumn.
     * Emits only when item identity/content actually changes (AsyncListDiffer equivalent).
     */
    val deviceRows: StateFlow<List<DeviceListRow>> = combine(
        repository.observeDevices(),
        presence.reachabilityEpochMs,
        presence.onlineDeviceIds,
        presence.onlineSnapshotEpochMs
    ) { devices, _, _, _ ->
        devices
            .distinctBy { it.deviceId }
            .map { device ->
                DeviceListRow(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    online = presence.isDeviceOnline(device),
                    appVersion = device.clientVersion.takeIf { it.isNotEmpty() },
                    appVersionCode = device.clientVersionCode,
                    lastSeenEpochMs = device.lastSeenEpochMs
                )
            }
    }
        .distinctUntilChanged { old, new ->
            if (old.size != new.size) return@distinctUntilChanged false
            old.indices.all { index ->
                DeviceListRow.areItemsTheSame(old[index], new[index]) &&
                    DeviceListRow.areContentsTheSame(old[index], new[index])
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        LocalDeviceNameStore.ensureLoaded()
        viewModelScope.launch {
            runCatching { repository.reconcileDuplicateEndpoints() }
        }
        viewModelScope.launch {
            LocalDeviceNameStore.deviceName.collect { name ->
                if (name.isNotBlank()) {
                    _uiState.update { it.copy(localDeviceName = name) }
                }
            }
        }
    }

    fun isDeviceOnline(device: PairedDeviceEntity): Boolean =
        presence.isDeviceOnline(device)

    fun isDeviceOnline(deviceId: String): Boolean {
        val row = deviceRows.value.firstOrNull { it.deviceId == deviceId } ?: return false
        return row.online
    }

    fun openDeviceOrExplain(deviceId: String, open: (BrowseTarget) -> Unit) {
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            openDeviceOrExplain(device, open)
        }
    }

    fun openDeviceOrExplain(device: PairedDeviceEntity, open: (BrowseTarget) -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    statusMessage = "Connecting to ${device.deviceName}…",
                    errorMessage = null
                )
            }
            val peer = repository.getDevice(device.deviceId) ?: device
            val reached = withContext(Dispatchers.IO) {
                presence.validatePeerOnDemand(peer)
            }
            val refreshed = repository.getDevice(device.deviceId) ?: peer
            if (!reached && !presence.isDeviceOnline(refreshed)) {
                _uiState.update {
                    it.copy(
                        statusMessage = "${device.deviceName} is offline — open OmniNode on that device",
                        errorMessage = null
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(statusMessage = null) }
            continueOpenDevice(refreshed, open)
        }
    }

    private fun continueOpenDevice(device: PairedDeviceEntity, open: (BrowseTarget) -> Unit) {
        if (DeviceSessionManager.isSessionValid(device.deviceId)) {
            DeviceSessionManager.markDeviceAccessed(device.deviceId)
            open(browseTargetFor(device, pinRequired = true))
            return
        }
        viewModelScope.launch {
            runCatching {
                val remote = OmniNodeServices.client.fetchPeerNodeState(device.lastKnownIp, device.port)
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
                    OmniNodeServices.client.fetchPeerNodeState(payload.host, payload.port)
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
            OmniNodeServices.client.fetchPeerNodeState(payload.host, payload.port)
        }.getOrNull()

        val broadcasterId = verified?.deviceId ?: payload.deviceId
        val broadcasterName = verified?.deviceName ?: payload.deviceName
        val broadcasterRoot = verified?.rootPath ?: payload.rootPath

        val broadcasterEntity = PairedDeviceEntity(
            deviceId = broadcasterId,
            deviceName = broadcasterName,
            lastKnownIp = payload.host,
            port = payload.port,
            publicKeyHash = verified?.publicKeyHash?.ifBlank { payload.publicKeyHash } ?: payload.publicKeyHash,
            rootPath = broadcasterRoot
        )
        repository.adoptFromPairing(broadcasterEntity)
        verified?.let { state ->
            repository.applyPeerNodeState(state, rosterDeviceId = payload.deviceId)
        }

        val scannerHost = NetworkUtils.lanIpv4Addresses().sorted().firstOrNull()
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
                    // Cloud first so peer firestore views update; LAN fan-out next.
                    // Never refresh presence before publish — peers still hold the old name.
                    runCatching {
                        GoogleLinkCoordinator.publishUserRenamedDevice(deviceId, trimmed)
                    }
                    OmniNodeServices.pairingCoordinator.broadcastSelfIdentity()
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
                    // Remote rename API triggers self-metadata broadcast on the peer; no proxy fan-out.
                    runCatching {
                        GoogleLinkCoordinator.publishUserRenamedDevice(deviceId, trimmed)
                    }
                    presence.refreshOnlineSnapshot()
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
            val device = repository.getDevice(deviceId)
            if (device == null) {
                _uiState.update {
                    it.copy(errorMessage = "Device is no longer in the paired list")
                }
                return@launch
            }
            runCatching {
                val removed = repository.removePermanently(deviceId)
                check(removed) { "Could not remove device" }
                DeviceSessionManager.clearSession(deviceId)
                purgeDirectShareTarget(deviceId)
                OmniNodeServices.pairingCoordinator.broadcastDeviceRemoval(device)
                GoogleLinkCoordinator.publishRemovedPeer(deviceId)
                presence.refreshOnlineSnapshot()
            }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            statusMessage = "${device.deviceName} removed — pair again to restore",
                            errorMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Remove failed")
                    }
                }
            )
        }
    }

    /**
     * Finder / OS drop onto a device tile → outbound send via [transferManager] (SSOT).
     * Local "This device" drops are refused (no same-device remote transfer).
     */
    fun sendDroppedLocalFiles(deviceId: String, absolutePaths: List<String>) {
        viewModelScope.launch {
            if (deviceId == LocalIdentity.LOCAL_DEVICE_ID || deviceId == identity.deviceId) {
                _uiState.update {
                    it.copy(
                        statusMessage = null,
                        errorMessage = "Can't send to this device — drop onto a paired peer"
                    )
                }
                return@launch
            }
            val files = withContext(Dispatchers.IO) {
                absolutePaths.filter { path ->
                    runCatching {
                        val file = kotlinx.io.files.Path(path)
                        val meta = kotlinx.io.files.SystemFileSystem.metadataOrNull(file)
                        meta != null && !meta.isDirectory
                    }.getOrDefault(false)
                }
            }
            if (files.isEmpty()) {
                _uiState.update {
                    it.copy(
                        statusMessage = null,
                        errorMessage = "Drop one or more files (folders are not sent)"
                    )
                }
                return@launch
            }
            val target = repository.getDevice(deviceId)
            if (target == null) {
                _uiState.update {
                    it.copy(errorMessage = "Device is no longer paired")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    statusMessage = "Sending ${files.size} file(s) to ${target.deviceName}…",
                    errorMessage = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    transferManager.sendLocalPathsToDeviceIds(files, listOf(deviceId))
                }
            }.fold(
                onSuccess = { batch ->
                    _uiState.update {
                        if (batch.allFailed) {
                            it.copy(
                                statusMessage = null,
                                errorMessage = batch.summaryMessage
                            )
                        } else {
                            it.copy(
                                statusMessage = batch.summaryMessage,
                                errorMessage = null
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            statusMessage = null,
                            errorMessage = error.message ?: "Send failed"
                        )
                    }
                }
            )
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

    fun initialListScrollIndex(): Int = listScrollIndex

    fun initialListScrollOffset(): Int = listScrollOffset

    fun saveListScroll(index: Int, offset: Int) {
        listScrollIndex = index
        listScrollOffset = offset
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
