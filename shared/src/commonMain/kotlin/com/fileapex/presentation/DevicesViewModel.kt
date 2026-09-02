package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.cloud.diagnostics.DiagnosticsRelayErrors
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.di.FileApexServices
import com.fileapex.domain.device.DeviceOrderCoordinator
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingBeacon
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.presence.LanPresenceTiming
import com.fileapex.domain.presence.PeerLanReachabilityVerdict
import com.fileapex.network.PeerReachabilityMessages
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.platform.purgeDirectShareTarget
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.i18n.AppI18n
import com.fileapex.session.DeviceSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
data class BatteryStatusItem(
    val deviceId: String,
    val deviceName: String,
    val levelPercent: Int?,
    val chargingState: String = "",
    val online: Boolean
)

data class BatteryCheckOverlayState(
    val loading: Boolean = false,
    val items: List<BatteryStatusItem> = emptyList()
)

data class DevicesUiState(
    val localDeviceName: String = "",
    val renameTargetId: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    /** 2s connecting handshake on the device card. */
    val connectingDeviceId: String? = null,
    val pendingPinPairing: PairingPayload? = null,
    val pendingPinUnlock: PendingPinUnlock? = null,
    val deviceOrderEditMode: Boolean = false,
    val editOrderRows: List<DeviceListRow> = emptyList(),
    val deviceDetails: DeviceDetailsState? = null,
    val batteryOverlayState: BatteryCheckOverlayState? = null,
    val discoveredPairingPeers: List<PairingBeacon> = emptyList(),
    val pairingSucceeded: Boolean = false
)

data class DeviceDetailsState(
    val deviceId: String,
    val deviceName: String,
    val loading: Boolean = false,
    val snapshot: PeerDeviceDiagnostics? = null,
    val errorMessage: String? = null
)

data class PendingPinUnlock(
    val device: PairedDeviceEntity,
    val displayName: String
)

private sealed interface DeviceConnectOutcome {
    data class Open(val target: BrowseTarget) : DeviceConnectOutcome
    data class NeedsPin(
        val device: PairedDeviceEntity,
        val displayName: String
    ) : DeviceConnectOutcome
    data class Unreachable(
        val detail: String,
        /** Skip the minimum "Connecting…" delay — used for fast off-LAN privacy blocks. */
        val quickFail: Boolean = false
    ) : DeviceConnectOutcome
}

class DevicesViewModel : ViewModel() {
    private val repository = FileApexServices.deviceRepository
    private val presence = FileApexServices.presenceMonitor
    private val transferManager = FileApexServices.transferManager
    private val identity: LocalIdentity
        get() = FileApexServices.localIdentity

    private var pendingOpenAction: ((BrowseTarget) -> Unit)? = null
    private var pairingDiscoveryPrune: Job? = null

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
        combine(
            repository.observeDevices(),
            presence.reachabilityEpochMs,
            presence.onlineDeviceIds,
            presence.onlineSnapshotEpochMs
        ) { devices, _, _, _ ->
            val selfDeviceId = identity.deviceId
            devices
                .distinctBy { it.deviceId }
                .filter { device ->
                    device.deviceId != LocalIdentity.LOCAL_DEVICE_ID &&
                        device.deviceId != selfDeviceId
                }
                .map { device ->
                    DeviceListRow(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        online = presence.isDeviceOnline(device),
                        appVersion = device.clientVersion.takeIf { it.isNotEmpty() },
                        appVersionCode = device.clientVersionCode,
                        lastSeenEpochMs = device.lastSeenEpochMs,
                        os = device.os,
                        platform = device.platform,
                        deviceMake = device.deviceMake,
                        deviceModel = device.deviceModel
                    )
                }
        },
        FileApexServices.settings.deviceOrderIds,
        DeviceOrderCoordinator.revisionEpochMs
    ) { rows, _, _ ->
        DeviceOrderCoordinator.applySavedOrder(rows)
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
        viewModelScope.launch {
            LanPairingDiscovery.discoveredPeers.collect { peers ->
                _uiState.update { it.copy(discoveredPairingPeers = peers) }
            }
        }
    }

    fun isDeviceOnline(device: PairedDeviceEntity): Boolean =
        presence.isDeviceOnline(device)

    fun isDeviceOnline(deviceId: String): Boolean {
        val row = deviceRows.value.firstOrNull { it.deviceId == deviceId } ?: return false
        return row.online
    }

    fun sendClipboardNow() {
        viewModelScope.launch {
            val sending = AppI18n.t("sending_clipboard")
            _uiState.update { it.copy(statusMessage = sending) }
            val message = com.fileapex.domain.clipboard.ClipboardShareCoordinator.pushCurrentClipboardNow()
            if (message == sending) {
                _uiState.update { it.copy(statusMessage = sending) }
            } else {
                _uiState.update { it.copy(statusMessage = null, errorMessage = message) }
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
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = error.message ?: AppI18n.t("clipboard_send_failed", device.deviceName)
                    )
                }
            }
        }
    }

    fun openDeviceOrExplain(deviceId: String, open: (BrowseTarget) -> Unit) {
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            openDeviceOrExplainInternal(device, open)
        }
    }

    fun openDeviceOrExplain(device: PairedDeviceEntity, open: (BrowseTarget) -> Unit) {
        viewModelScope.launch {
            openDeviceOrExplainInternal(device, open)
        }
    }

    private suspend fun openDeviceOrExplainInternal(
        device: PairedDeviceEntity,
        open: (BrowseTarget) -> Unit
    ) {
        _uiState.update {
            it.copy(
                connectingDeviceId = device.deviceId,
                statusMessage = null,
                errorMessage = null
            )
        }
        val startedAt = TimeUtils.now()
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                performDeviceConnectHandshake(device)
            }
        }.getOrElse { error ->
            DeviceConnectOutcome.Unreachable(error.message ?: AppI18n.t("unable_to_reach_device"))
        }
        val skipMinDelay = outcome is DeviceConnectOutcome.Unreachable && outcome.quickFail
        val remainingMs = if (skipMinDelay) {
            0L
        } else {
            TimeUtils.remainingMs(startedAt, LanPresenceTiming.DEVICE_CONNECT_HANDSHAKE_MS)
        }
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
        _uiState.update { it.copy(connectingDeviceId = null) }
        when (outcome) {
            is DeviceConnectOutcome.Open -> open(outcome.target)
            is DeviceConnectOutcome.NeedsPin -> {
                pendingOpenAction = open
                _uiState.update {
                    it.copy(
                        pendingPinUnlock = PendingPinUnlock(
                            device = outcome.device,
                            displayName = outcome.displayName
                        ),
                        statusMessage = AppI18n.t("enter_pin_for", outcome.displayName)
                    )
                }
            }
            is DeviceConnectOutcome.Unreachable -> {
                _uiState.update {
                    it.copy(errorMessage = outcome.detail)
                }
            }
        }
    }

    private suspend fun performDeviceConnectHandshake(device: PairedDeviceEntity): DeviceConnectOutcome {
        if (!isActiveLanConnectivity()) {
            return DeviceConnectOutcome.Unreachable(
                detail = PeerReachabilityMessages.localWifiRequired(),
                quickFail = true
            )
        }
        val peer = repository.getDevice(device.deviceId) ?: device
        val initialEndpoint = presence.resolveOutboundEndpoint(peer)
            ?: run {
                presence.validatePeerOnDemand(peer)
                presence.resolveOutboundEndpoint(repository.getDevice(device.deviceId) ?: peer)
            }
            ?: return DeviceConnectOutcome.Unreachable(
                detail = PeerReachabilityMessages.peerOffline()
            )
        var endpoint = initialEndpoint
        var refreshed = repository.getDevice(device.deviceId) ?: peer
        if (DeviceSessionManager.isSessionValid(refreshed.deviceId)) {
            DeviceSessionManager.markDeviceAccessed(refreshed.deviceId)
            return DeviceConnectOutcome.Open(
                browseTargetFor(refreshed, endpoint.host, endpoint.port, pinRequired = true)
            )
        }
        runCatching { com.fileapex.cloud.FcmWakeCoordinator.dispatchPresenceWakeToLinkedPeers() }
        runCatching { com.fileapex.network.sendWakeBroadcastOnPrimaryInterface() }
        var remote = runCatching {
            FileApexServices.client.fetchPeerNodeState(
                endpoint.host,
                endpoint.port,
                LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
            )
        }.recoverCatching {
            delay(400)
            FileApexServices.client.fetchPeerNodeState(
                endpoint.host,
                endpoint.port,
                LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
            )
        }.getOrNull()
        if (remote == null) {
            presence.validatePeerOnDemand(peer)
            refreshed = repository.getDevice(device.deviceId) ?: peer
            endpoint = presence.resolveOutboundEndpoint(refreshed)
                ?: return DeviceConnectOutcome.Unreachable(
                    detail = PeerReachabilityMessages.peerOffline()
                )
            remote = runCatching {
                FileApexServices.client.fetchPeerNodeState(
                    endpoint.host,
                    endpoint.port,
                    LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
                )
            }.getOrElse { error ->
                return DeviceConnectOutcome.Unreachable(
                    detail = error.message ?: PeerReachabilityMessages.peerOffline()
                )
            }
        }
        if (remote.pinRequired) {
            val name = remote.deviceName.ifBlank { refreshed.deviceName }
            return DeviceConnectOutcome.NeedsPin(refreshed, name)
        }
        DeviceSessionManager.markDeviceAccessed(refreshed.deviceId)
        return DeviceConnectOutcome.Open(
            browseTargetFor(refreshed, endpoint.host, endpoint.port, pinRequired = false)
        )
    }

    fun pairFromQrPayload(payload: PairingPayload) {
        viewModelScope.launch {
            runCatching {
                if (payload.deviceId == identity.deviceId) {
                    error(AppI18n.t("pairing_scan_own_qr"))
                }
                val verified = runCatching {
                    FileApexServices.client.fetchPeerNodeState(payload.host, payload.port)
                }.getOrNull()
                val pinRequired = verified?.pinRequired == true || payload.pinRequired
                if (pinRequired) {
                    _uiState.update {
                        it.copy(
                            pendingPinPairing = payload.copy(pinRequired = true),
                            statusMessage = AppI18n.t("enter_pin_for", verified?.deviceName ?: payload.deviceName)
                        )
                    }
                    return@launch
                }
                completePairing(payload, pin = null)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: AppI18n.t("pairing_failed"))
                }
            }
        }
    }

    fun startPairingDiscovery() {
        LanPairingDiscovery.startDiscovery()
        viewModelScope.launch(Dispatchers.IO) {
            ServerLifecycleManager.ensureRunning()
        }
        pairingDiscoveryPrune?.cancel()
        pairingDiscoveryPrune = viewModelScope.launch {
            while (true) {
                delay(500)
                LanPairingDiscovery.pruneStalePeers()
            }
        }
    }

    fun stopPairingDiscovery() {
        pairingDiscoveryPrune?.cancel()
        pairingDiscoveryPrune = null
        LanPairingDiscovery.stopDiscovery()
    }

    fun pairFromManualInput(input: String) {
        viewModelScope.launch {
            val trimmed = input.trim()
            if (trimmed.isBlank()) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("enter_pairing_code")) }
                return@launch
            }

            val matched = LanPairingDiscovery.matchInput(trimmed)
            if (matched != null) {
                pairFromQrPayload(matched)
                return@launch
            }

            _uiState.update {
                it.copy(
                    errorMessage = AppI18n.t("no_nearby_broadcast")
                )
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
                require(pin.isNotBlank()) { AppI18n.t("pin_required_error") }
                completePairing(payload, pin = pin.trim())
                _uiState.update { it.copy(pendingPinPairing = null) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: AppI18n.t("pairing_failed"))
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
                require(pin.isNotBlank()) { AppI18n.t("pin_required_error") }
                FileApexServices.client.verifyPin(
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
                    it.copy(errorMessage = error.message ?: AppI18n.t("incorrect_pin"))
                }
            }
        }
    }

    private suspend fun completePairing(payload: PairingPayload, pin: String?) {
        val verified = runCatching {
            FileApexServices.client.fetchPeerNodeState(payload.host, payload.port)
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

        val scannerHost = NetworkUtils.preferredLanIpv4()
        if (!NetworkUtils.isUsableLanIpv4(scannerHost)) {
            error(AppI18n.t("no_lan_ipv4_reverse"))
        }
        val scannerEntity = PairedDeviceEntity(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            lastKnownIp = scannerHost,
            port = identity.sharePort,
            publicKeyHash = "",
            rootPath = identity.rootPath
        )
        FileApexServices.pairingCoordinator.awaitShareServerReady()
        FileApexServices.client.postPairingRespond(
            host = payload.host,
            port = payload.port,
            scannerDevice = scannerEntity,
            pin = pin,
            pairingCode = payload.pairingCode
        )
        if (!pin.isNullOrBlank()) {
            FileApexServices.client.rememberSessionPin(payload.host, payload.port, pin)
            DeviceSessionManager.markDeviceAccessed(broadcasterId)
        }

        // Navigate back immediately — roster import / cluster announce can take seconds on cold LAN.
        _uiState.update {
            it.copy(
                statusMessage = AppI18n.t("paired_with", broadcasterName),
                pairingSucceeded = true
            )
        }

        viewModelScope.launch {
            runCatching {
                val importedCount = FileApexServices.pairingCoordinator.importDirectPeerRoster(
                    host = payload.host,
                    port = payload.port,
                    excludeDeviceIds = setOf(broadcasterId)
                )
                FileApexServices.pairingCoordinator.announceSelfToCluster(
                    excludeDeviceIds = setOf(broadcasterId)
                )
                FileApexServices.pairingCoordinator.afterOutboundPair(broadcasterEntity)
                if (importedCount > 0) {
                    _uiState.update {
                        it.copy(
                            statusMessage = "${AppI18n.t("paired_with", broadcasterName)} ${AppI18n.plural("paired_cluster_extra", importedCount)}",
                            pairingSucceeded = true
                        )
                    }
                }
            }.onFailure { error ->
                println("DevicesViewModel: post-pair cluster sync failed - ${error.message}")
            }
        }
    }

    fun beginRename(deviceId: String) {
        _uiState.update { it.copy(renameTargetId = deviceId) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(renameTargetId = null) }
    }

    fun confirmRename(deviceId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = AppI18n.t("name_cannot_be_empty")) }
            return
        }
        _uiState.update {
            it.copy(
                renameTargetId = null,
                statusMessage = AppI18n.t("updating_device_name"),
                errorMessage = null
            )
        }
        viewModelScope.launch {
            runCatching {
                if (deviceId == LocalIdentity.LOCAL_DEVICE_ID) {
                    com.fileapex.domain.device.DeviceNameCoordinator.saveLocalBroadcastName(trimmed)
                    _uiState.update {
                        it.copy(
                            localDeviceName = trimmed,
                            statusMessage = AppI18n.t("renamed_synced", trimmed)
                        )
                    }
                } else {
                    com.fileapex.domain.device.DeviceNameCoordinator.renamePeerDevice(deviceId, trimmed)
                    _uiState.update {
                        it.copy(statusMessage = AppI18n.t("renamed_synced", trimmed))
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: AppI18n.t("rename_failed"))
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            val device = repository.getDevice(deviceId)
            if (device == null) {
                _uiState.update {
                    it.copy(errorMessage = AppI18n.t("device_not_in_list"))
                }
                return@launch
            }
            runCatching {
                val removed = repository.removePermanently(deviceId)
                check(removed) { "Could not remove device" }
                DeviceSessionManager.clearSession(deviceId)
                purgeDirectShareTarget(deviceId)
                FileApexServices.pairingCoordinator.broadcastDeviceRemoval(device)
                GoogleLinkCoordinator.publishRemovedPeer(deviceId)
                presence.refreshOnlineSnapshot()
            }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            statusMessage = AppI18n.t("device_removed_restore", device.deviceName),
                            errorMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: AppI18n.t("remove_failed"))
                    }
                }
            )
        }
    }

    /**
     * Finder / Explorer drop onto a device tile. Folders keep nested structure on the peer.
     * Local "This device" drops are refused (no same-device remote transfer).
     */
    fun sendDroppedLocalFiles(deviceId: String, absolutePaths: List<String>) {
        viewModelScope.launch {
            if (deviceId == LocalIdentity.LOCAL_DEVICE_ID || deviceId == identity.deviceId) {
                _uiState.update {
                    it.copy(
                        statusMessage = null,
                        errorMessage = AppI18n.t("cannot_send_to_self")
                    )
                }
                return@launch
            }
            val roots = withContext(Dispatchers.IO) {
                absolutePaths.filter { path ->
                    runCatching {
                        val file = kotlinx.io.files.Path(path)
                        kotlinx.io.files.SystemFileSystem.exists(file)
                    }.getOrDefault(false)
                }
            }
            if (roots.isEmpty()) {
                _uiState.update {
                    it.copy(
                        statusMessage = null,
                        errorMessage = AppI18n.t("drop_files_or_folders")
                    )
                }
                return@launch
            }
            val target = repository.getDevice(deviceId)
            if (target == null) {
                _uiState.update {
                    it.copy(errorMessage = AppI18n.t("device_no_longer_paired"))
                }
                return@launch
            }
            _uiState.update {
                it.copy(errorMessage = null)
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    FileApexServices.transferQueue.sendLocalPathsOrQueue(roots, listOf(deviceId))
                }
            }.fold(
                onSuccess = { outcome ->
                    _uiState.update {
                        val batch = outcome.batch
                        val failed = batch?.allFailed != false && !outcome.hadQueue
                        if (failed) {
                            it.copy(
                                statusMessage = null,
                                errorMessage = outcome.message
                            )
                        } else {
                            it.copy(
                                statusMessage = outcome.message,
                                errorMessage = null
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            statusMessage = null,
                            errorMessage = error.message ?: AppI18n.t("send_failed")
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
            displayName = AppI18n.t("this_device_named", LocalDeviceNameStore.current()),
            rootPath = identity.rootPath
        )
    }

    fun browseTargetFor(
        device: PairedDeviceEntity,
        host: String = device.lastKnownIp,
        port: Int = device.port,
        pinRequired: Boolean = false
    ): BrowseTarget {
        return BrowseTarget.Remote(
            deviceId = device.deviceId,
            displayName = device.deviceName,
            host = host,
            port = port,
            rootPath = device.rootPath,
            pinRequired = pinRequired
        )
    }

    fun dismissMessages() {
        _uiState.update {
            it.copy(statusMessage = null, errorMessage = null, pairingSucceeded = false)
        }
    }

    override fun onCleared() {
        stopPairingDiscovery()
        super.onCleared()
    }

    fun reportScanError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun requestDeviceDetails(deviceId: String) {
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            requestDeviceDetails(device)
        }
    }

    fun requestDeviceDetails(device: PairedDeviceEntity) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    deviceDetails = DeviceDetailsState(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        loading = true
                    ),
                    errorMessage = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    fetchDeviceDetailsSnapshot(device)
                }
            }.fold(
                onSuccess = { snapshot ->
                    _uiState.update {
                        it.copy(
                            deviceDetails = DeviceDetailsState(
                                deviceId = device.deviceId,
                                deviceName = device.deviceName,
                                loading = false,
                                snapshot = snapshot
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            deviceDetails = DeviceDetailsState(
                                deviceId = device.deviceId,
                                deviceName = device.deviceName,
                                loading = false,
                                errorMessage = DiagnosticsRelayErrors.fromThrowable(error)
                            )
                        )
                    }
                }
            )
        }
    }

    private suspend fun fetchDeviceDetailsSnapshot(device: PairedDeviceEntity): PeerDeviceDiagnostics {
        presence.resolveOutboundEndpoint(device)?.let { direct ->
            runCatching {
                FileApexServices.client.fetchDeviceDiagnostics(direct.host, direct.port)
            }.onSuccess { return it }
        }
        return DiagnosticsCloudRelay.fetchPeerDiagnostics(device.deviceId)
    }

    fun dismissDeviceDetails() {
        _uiState.update { it.copy(deviceDetails = null) }
    }

    fun checkBatteries() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    batteryOverlayState = BatteryCheckOverlayState(loading = true, items = emptyList())
                )
            }

            val results = withContext(Dispatchers.IO) {
                val localDiag = runCatching {
                    com.fileapex.platform.collectDeviceDiagnostics()
                }.getOrNull()

                val thisDeviceItem = BatteryStatusItem(
                    deviceId = "this_device_local",
                    deviceName = AppI18n.t("this_device"),
                    levelPercent = localDiag?.battery?.levelPercent,
                    chargingState = localDiag?.battery?.chargingState ?: "",
                    online = true
                )

                val rows = deviceRows.value
                val remoteItems = rows.map { row ->
                    val deviceEntity = repository.getDevice(row.deviceId)
                    if (deviceEntity != null && row.online) {
                        val diagnostics = runCatching {
                            fetchDeviceDetailsSnapshot(deviceEntity)
                        }.getOrNull()
                        BatteryStatusItem(
                            deviceId = row.deviceId,
                            deviceName = row.deviceName,
                            levelPercent = diagnostics?.battery?.levelPercent,
                            chargingState = diagnostics?.battery?.chargingState ?: "",
                            online = true
                        )
                    } else {
                        BatteryStatusItem(
                            deviceId = row.deviceId,
                            deviceName = row.deviceName,
                            levelPercent = null,
                            chargingState = "",
                            online = false
                        )
                    }
                }

                listOf(thisDeviceItem) + remoteItems
            }

            _uiState.update {
                it.copy(
                    batteryOverlayState = BatteryCheckOverlayState(loading = false, items = results)
                )
            }
        }
    }

    fun dismissBatteryOverlay() {
        _uiState.update { it.copy(batteryOverlayState = null) }
    }

    fun initialListScrollIndex(): Int = listScrollIndex

    fun initialListScrollOffset(): Int = listScrollOffset

    fun saveListScroll(index: Int, offset: Int) {
        listScrollIndex = index
        listScrollOffset = offset
    }

    fun enterDeviceOrderEditMode() {
        _uiState.update {
            it.copy(
                deviceOrderEditMode = true,
                editOrderRows = deviceRows.value
            )
        }
    }

    fun exitDeviceOrderEditMode() {
        _uiState.update {
            it.copy(deviceOrderEditMode = false, editOrderRows = emptyList())
        }
    }

    fun reorderEditDevice(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        _uiState.update { state ->
            if (!state.deviceOrderEditMode) return@update state
            val rows = state.editOrderRows.toMutableList()
            if (fromIndex !in rows.indices || toIndex !in rows.indices) return@update state
            val item = rows.removeAt(fromIndex)
            rows.add(toIndex, item)
            state.copy(editOrderRows = rows)
        }
    }

    fun revertDeviceOrderInEditMode() {
        val rows = _uiState.value.editOrderRows
        if (rows.isEmpty()) return
        val alphabetical = DeviceOrderCoordinator.applyOrderIds(
            rows,
            DeviceOrderCoordinator.alphabeticalOrderIds(rows)
        )
        _uiState.update { it.copy(editOrderRows = alphabetical) }
    }

    fun saveDeviceOrderAndExitEditMode() {
        val rows = _uiState.value.editOrderRows
        if (rows.isEmpty()) {
            exitDeviceOrderEditMode()
            return
        }
        DeviceOrderCoordinator.saveLocalOrder(rows.map { it.deviceId })
        exitDeviceOrderEditMode()
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
