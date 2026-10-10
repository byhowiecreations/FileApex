package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.cloud.diagnostics.DiagnosticsRelayErrors
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices
import com.fileapex.domain.device.DeviceOrderCoordinator
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingBeacon
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.presence.LanPresenceTiming
import com.fileapex.domain.presence.PeerLanReachabilityVerdict
import com.fileapex.domain.presence.isTailscaleEnabled
import com.fileapex.network.PeerReachabilityMessages
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.platform.purgeDirectShareTarget
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.session.DeviceSessionManager
import com.fileapex.domain.transfer.MultiCopySource
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Ephemeral Devices-screen chrome only.
 *
 * All state that must survive navigation (custom order, pairing pins, active connects)
 * belongs in [DeviceRepository], [AppSettings], or another process-scoped singleton.
 */
data class BatteryStatusItem(
    val deviceId: String,
    val deviceName: String,
    val levelPercent: Int?,
    val chargingState: String = "",
    val lowPowerMode: Boolean = false,
    val online: Boolean
)

data class BatteryCheckOverlayState(
    val loading: Boolean = false,
    val items: List<BatteryStatusItem> = emptyList(),
    val logLines: List<String> = emptyList(),
    val isComplete: Boolean = false
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
    val pairingSucceeded: Boolean = false,
    val isSendingClipboard: Boolean = false,
    val showClipboardConfigDialog: Boolean = false,
    val clipboardConfigPeers: List<PairedDeviceEntity> = emptyList()
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

private const val BATTERY_FETCH_CONCURRENCY = 4
private const val BATTERY_CACHE_MS = 15_000L

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
    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

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
                        deviceModel = device.deviceModel,
                        cardPosX = device.cardPosX,
                        cardPosY = device.cardPosY,
                        cardSortOrder = device.cardSortOrder,
                        cardMenuOrder = device.cardMenuOrder,
                        tilePosX = device.tilePosX,
                        tilePosY = device.tilePosY,
                        tileSortOrder = device.tileSortOrder,
                        tileMenuOrder = device.tileMenuOrder
                    )
                }
        },
        FileApexServices.settings.deviceOrderIds,
        DeviceOrderCoordinator.revisionEpochMs,
        com.fileapex.domain.demo.DemoModeState.isDemoModeActive,
        com.fileapex.domain.demo.DemoModeState.demoDeviceRows
    ) { rows, _, _, isDemoActive, demoRows ->
        if (rows.isEmpty() && isDemoActive) {
            demoRows
        } else {
            DeviceOrderCoordinator.applySavedOrder(rows)
        }
    }
        .onEach {
            _isInitialLoadComplete.value = true
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
        viewModelScope.launch {
            repository.observeDevices().collect { devices ->
                if (devices.isNotEmpty() && com.fileapex.domain.demo.DemoModeState.isDemoModeActive.value) {
                    com.fileapex.domain.demo.DemoModeState.exitDemo()
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
            } catch (t: Throwable) {
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
        if (com.fileapex.domain.demo.DemoModeState.isDemoModeActive.value &&
            (deviceId == "demo_macbook" || deviceId == "demo_tablet")
        ) {
            open(com.fileapex.domain.demo.DemoModeState.getBrowseTarget(deviceId))
            return
        }
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
            DeviceConnectOutcome.Unreachable(UserFacingErrors.message(error, "unable_to_reach_device"))
        }
        val skipMinDelay = outcome is DeviceConnectOutcome.Unreachable && outcome.quickFail
        LanPresenceTiming.awaitConnectHandshakeMinDelay(startedAt, skipMinDelay)
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
        if (!isTailscaleEnabled() && !isActiveLanConnectivity()) {
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
                browseTargetFor(refreshed, endpoint.host, endpoint.port, pinRequired = false)
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
        val actualRoot = remote.rootPath.takeIf { it.isNotBlank() } ?: refreshed.rootPath
        if (refreshed.rootPath != actualRoot) {
            val updated = refreshed.copy(rootPath = actualRoot)
            repository.upsert(updated)
            refreshed = updated
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
                    it.copy(errorMessage = UserFacingErrors.message(error, "pairing_failed"))
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
                    it.copy(errorMessage = UserFacingErrors.message(error, "pairing_failed"))
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
                val endpoint = presence.resolveOutboundEndpoint(pending.device)
                val host = endpoint?.host ?: pending.device.lastKnownIp
                val port = endpoint?.port ?: pending.device.port
                FileApexServices.client.verifyPin(
                    host = host,
                    port = port,
                    pin = pin.trim()
                )
                DeviceSessionManager.markDeviceAccessed(pending.device.deviceId)
                val action = pendingOpenAction
                pendingOpenAction = null
                _uiState.update { it.copy(pendingPinUnlock = null) }
                action?.invoke(browseTargetFor(pending.device, host, port, pinRequired = true))
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = UserFacingErrors.message(error, "incorrect_pin"))
                }
            }
        }
    }

    private suspend fun completePairing(payload: PairingPayload, pin: String?) {
        // Snapshot before anything is adopted: only this pre-pairing cluster is ours to announce.
        val preexistingIds = repository.listDevices().map { it.deviceId }.toSet()
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
        if (payload.tlsPinTrusted) {
            repository.recordTlsPin(broadcasterId, payload.tlsPin, payload.tlsPort)
        } else if (payload.tlsPin.isNotEmpty() && payload.tlsPort > 0) {
            // Manual code: the pin came over multicast, so the user checks it against the other screen.
            com.fileapex.security.tls.PeerTlsStatus.requestConfirmation(
                com.fileapex.security.tls.TlsPinPrompt(
                    broadcasterId,
                    com.fileapex.security.tls.TlsPromptKind.NEW_PEER_KEY,
                    payload.tlsPin,
                    payload.tlsPort
                )
            )
        }
        verified?.let { state ->
            repository.applyPeerNodeState(state, rosterDeviceId = payload.deviceId, observedDirectly = true)
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
            rootPath = identity.rootPath,
            tlsPin = com.fileapex.security.tls.LocalTlsInfo.pin,
            tlsPort = com.fileapex.security.tls.LocalTlsInfo.port
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
                    excludeDeviceIds = setOf(broadcasterId),
                    sourceClientVersion = verified?.clientVersion.orEmpty()
                )
                // Introduce ourselves only to peers just imported from the broadcaster; our own
                // cluster already knows us and is told about the broadcaster by afterOutboundPair.
                val importedIds = repository.listDevices().map { it.deviceId }.toSet() -
                    preexistingIds - broadcasterId
                if (importedIds.isNotEmpty()) {
                    FileApexServices.pairingCoordinator.announceSelfToCluster(
                        excludeDeviceIds = setOf(broadcasterId),
                        onlyDeviceIds = importedIds
                    )
                }
                FileApexServices.pairingCoordinator.afterOutboundPair(
                    broadcasterEntity,
                    preexistingDeviceIds = preexistingIds
                )
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
        if (com.fileapex.domain.demo.DemoModeState.isDemoModeActive.value &&
            com.fileapex.domain.demo.DemoModeState.isDemoDeviceId(deviceId)
        ) {
            com.fileapex.domain.demo.DemoModeState.renameDemoDevice(deviceId, trimmed)
            _uiState.update { it.copy(statusMessage = AppI18n.t("renamed_synced", trimmed)) }
            return
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
                    it.copy(errorMessage = UserFacingErrors.message(error, "rename_failed"))
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        if (com.fileapex.domain.demo.DemoModeState.isDemoModeActive.value &&
            com.fileapex.domain.demo.DemoModeState.isDemoDeviceId(deviceId)
        ) {
            val demoRow = com.fileapex.domain.demo.DemoModeState.demoDeviceRows.value.firstOrNull { it.deviceId == deviceId }
            val name = demoRow?.deviceName ?: deviceId
            com.fileapex.domain.demo.DemoModeState.removeDemoDevice(deviceId)
            _uiState.update { it.copy(statusMessage = AppI18n.t("device_removed_restore", name)) }
            return
        }
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
                        it.copy(errorMessage = UserFacingErrors.message(error, "remove_failed"))
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
                // Entries dragged out of a remote device may land on this device; plain local paths may not.
                if (absolutePaths.any { it.startsWith("fileapex-transfer://") }) {
                    handleDroppedRemoteTransfer(LocalIdentity.LOCAL_DEVICE_ID, absolutePaths)
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        statusMessage = null,
                        errorMessage = AppI18n.t("cannot_send_to_self")
                    )
                }
                return@launch
            }

            if (absolutePaths.any { it.startsWith("fileapex-transfer://") }) {
                handleDroppedRemoteTransfer(deviceId, absolutePaths)
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
                            errorMessage = UserFacingErrors.message(error, "send_failed")
                        )
                    }
                }
            )
        }
    }

    private suspend fun handleDroppedRemoteTransfer(targetDeviceId: String, uriList: List<String>) {
        val uris = uriList.filter { it.startsWith("fileapex-transfer://") }
        if (uris.isEmpty()) return
        for (uri in uris) {
            val withoutScheme = uri.removePrefix("fileapex-transfer://")
            val sourceDeviceId = withoutScheme.substringBefore('/')
            val rest = withoutScheme.substringAfter('/')
            val rawPath = rest.substringBefore('?')
            val remotePath = if (rawPath.startsWith('/')) rawPath else "/$rawPath"
            val query = rest.substringAfter('?', "")
            val fileName = query.substringAfter("name=", "").substringBefore('&').takeIf { it.isNotBlank() }
                ?: remotePath.substringAfterLast('/')
            val fileSize = query.substringAfter("size=", "0").substringBefore('&').toLongOrNull() ?: 0L

            if (sourceDeviceId == targetDeviceId) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("cannot_send_to_self")) }
                return
            }

            if (sourceDeviceId == "local") {
                sendDroppedLocalFiles(targetDeviceId, listOf(remotePath))
                return
            }

            val sourceDevice = repository.getDevice(sourceDeviceId)
            if (sourceDevice == null) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("device_no_longer_paired")) }
                return
            }

            val isTargetLocal = targetDeviceId == LocalIdentity.LOCAL_DEVICE_ID
            val targetDevice = if (isTargetLocal) null else repository.getDevice(targetDeviceId)
            if (!isTargetLocal && targetDevice == null) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("device_no_longer_paired")) }
                return
            }

            val targetName = if (isTargetLocal) AppI18n.t("this_device") else targetDevice!!.deviceName

            _uiState.update {
                it.copy(
                    statusMessage = "Transferring $fileName from ${sourceDevice.deviceName} to $targetName...",
                    errorMessage = null
                )
            }

            val destination = if (isTargetLocal) {
                MultiCopyDeviceOption(
                    deviceId = LocalIdentity.LOCAL_DEVICE_ID,
                    deviceName = targetName,
                    isLocal = true,
                    host = com.fileapex.util.NetworkUtils.preferredLanIpv4(),
                    port = identity.sharePort,
                    destinationRoot = com.fileapex.platform.defaultDownloadsDir()
                )
            } else {
                val dev = targetDevice!!
                val effectiveRoot = if (dev.platform.trim().equals("android", ignoreCase = true) && (dev.rootPath.isBlank() || dev.rootPath == "/")) {
                    "/storage/emulated/0"
                } else {
                    dev.rootPath
                }
                val resolvedRoot = com.fileapex.platform.DownloadsPaths.resolveReceiveRoot(
                    downloadsPath = "",
                    rootPath = effectiveRoot,
                    platform = dev.platform
                )
                MultiCopyDeviceOption(
                    deviceId = dev.deviceId,
                    deviceName = dev.deviceName,
                    isLocal = false,
                    host = dev.lastKnownIp,
                    port = dev.port,
                    destinationRoot = resolvedRoot
                )
            }

            val sourceEndpoint = if (isTailscaleEnabled()) {
                presence.resolveOutboundEndpoint(sourceDevice)
            } else {
                null
            }
            val source = MultiCopySource.Remote(
                fileName = fileName,
                sizeBytes = fileSize,
                absolutePath = remotePath,
                host = sourceEndpoint?.host ?: sourceDevice.lastKnownIp,
                port = sourceEndpoint?.port ?: sourceDevice.port,
                isDirectory = false,
                relativeDestPath = fileName
            )

            withContext(Dispatchers.IO) {
                runCatching {
                    FileApexServices.transferManager.sendToDevices(listOf(source), listOf(destination))
                }.fold(
                    onSuccess = { res ->
                        _uiState.update {
                            if (res.allFailed) {
                                val reason = res.results.flatMap { it.failures.values }.firstOrNull()?.let { ": $it" }.orEmpty()
                                it.copy(errorMessage = "Transfer of $fileName failed$reason", statusMessage = null)
                            } else {
                                it.copy(statusMessage = "Transferred $fileName to $targetName", errorMessage = null)
                            }
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(errorMessage = "Transfer failed: ${err.message}", statusMessage = null)
                        }
                    }
                )
            }
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

    fun requestDeviceDetails(deviceId: String, summaryOnly: Boolean = false) {
        if (com.fileapex.domain.demo.DemoModeState.isDemoModeActive.value &&
            com.fileapex.domain.demo.DemoModeState.isDemoDeviceId(deviceId)
        ) {
            val demoRow = com.fileapex.domain.demo.DemoModeState.demoDeviceRows.value.firstOrNull { it.deviceId == deviceId }
            val name = demoRow?.deviceName ?: deviceId
            _uiState.update {
                it.copy(
                    deviceDetails = DeviceDetailsState(
                        deviceId = deviceId,
                        deviceName = name,
                        loading = false,
                        snapshot = com.fileapex.domain.demo.DemoModeState.getDemoDeviceDiagnostics(deviceId)
                    ),
                    errorMessage = null
                )
            }
            return
        }
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            requestDeviceDetails(device, summaryOnly)
        }
    }

    fun requestDeviceDetails(device: PairedDeviceEntity, summaryOnly: Boolean = false) {
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
                    fetchDeviceDetailsSnapshot(device, summaryOnly)
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

    private suspend fun fetchDeviceDetailsSnapshot(device: PairedDeviceEntity, summaryOnly: Boolean): PeerDeviceDiagnostics {
        presence.resolveOutboundEndpoint(device)?.let { direct ->
            runCatching {
                FileApexServices.client.fetchDeviceDiagnostics(direct.host, direct.port, summaryOnly)
            }.onSuccess { return it }
        }
        return DiagnosticsCloudRelay.fetchPeerDiagnostics(device.deviceId)
    }

    private val batteryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, BatteryDiagnostics>>()

    /** A second tap a few seconds later reuses the first answer instead of polling every device again. */
    private fun cachedBattery(deviceId: String): BatteryDiagnostics? {
        val entry = batteryCache[deviceId] ?: return null
        return entry.second.takeIf { TimeUtils.now() - entry.first < BATTERY_CACHE_MS }
    }

    private fun rememberBattery(deviceId: String, battery: BatteryDiagnostics) {
        batteryCache[deviceId] = TimeUtils.now() to battery
    }

    private suspend fun fetchDeviceBattery(device: PairedDeviceEntity): BatteryDiagnostics? {
        presence.resolveOutboundEndpoint(device)?.let { direct ->
            val directResult = runCatching {
                FileApexServices.client.fetchFastBattery(direct.host, direct.port)
            }.getOrNull()
            if (directResult != null) return directResult
        }
        return runCatching {
            DiagnosticsCloudRelay.fetchPeerDiagnostics(device.deviceId).battery
        }.getOrNull()
    }

    fun dismissDeviceDetails() {
        _uiState.update { it.copy(deviceDetails = null) }
    }

    fun checkBatteries() {
        viewModelScope.launch {
            // Only devices that look offline need a wake. Online ones are queried directly below.
            val sleeping = deviceRows.value.filter { !it.online }
            if (sleeping.isNotEmpty()) {
                runCatching { com.fileapex.network.sendWakeBroadcastOnPrimaryInterface() }
                sleeping.forEach { row ->
                    runCatching { com.fileapex.cloud.FcmWakeCoordinator.dispatchPresenceWakeToDevice(row.deviceId) }
                }
            }
            val initialLogs = listOf(
                "FileApex Linux v${com.fileapex.update.currentAppVersionName()} (tty1)",
                "login: fileapex",
                "root@fileapex:~# batstat --all-devices",
                "[INIT] Polling battery telemetry across cluster...",
                "--------------------------------------------------"
            )
            _uiState.update {
                it.copy(
                    batteryOverlayState = BatteryCheckOverlayState(
                        loading = false,
                        items = emptyList(),
                        logLines = initialLogs,
                        isComplete = false
                    )
                )
            }

            val rows = deviceRows.value
            val localDeviceName = _uiState.value.localDeviceName
                .ifBlank { LocalDeviceNameStore.current() }
                .ifBlank { AppI18n.t("this_device") }
            val allNames = listOf(localDeviceName) + rows.map { it.deviceName }
            val nameColumnWidth = (allNames.maxOfOrNull { it.length } ?: 10) + 1

            // 1. Immediately query and display local machine (fast path: < 20ms)
            val localDiag = withContext(Dispatchers.IO) {
                runCatching {
                    com.fileapex.platform.collectFastBatteryDiagnostics()
                }.getOrNull()
            }
            val localLevel = localDiag?.levelPercent
            val localCharging = localDiag?.chargingState?.takeIf { it.isNotBlank() } ?: "BATTERY"
            val localLowPower = localDiag?.lowPowerMode == true
            val localPercentText = if (localLevel != null) "$localLevel%" else "---"
            val localStateTag = when {
                localLevel == null && localCharging.equals("Not available", ignoreCase = true) -> "[AC]"
                else -> "[${localCharging.uppercase()}]"
            }
            val localLowPowerTag = if (localLowPower) " [LOW POWER]" else ""
            val localLine = "[ONLINE]  ${localDeviceName.padEnd(nameColumnWidth)}│ ${localPercentText.padStart(4)} │ $localStateTag$localLowPowerTag"
            val thisDeviceItem = BatteryStatusItem(
                deviceId = "this_device_local",
                deviceName = localDeviceName,
                levelPercent = localLevel,
                chargingState = localCharging,
                lowPowerMode = localLowPower,
                online = true
            )
            _uiState.update { state ->
                val cur = state.batteryOverlayState ?: return@update state
                cur.copy(
                    items = cur.items + thisDeviceItem,
                    logLines = cur.logLines + localLine
                ).let { state.copy(batteryOverlayState = it) }
            }

            // 2. Query online devices concurrently - display each as soon as it responds
            val (onlineRows, offlineRows) = rows.partition { it.online }

            val fetchGate = Semaphore(BATTERY_FETCH_CONCURRENCY)
            withContext(Dispatchers.IO) {
                coroutineScope {
                    onlineRows.forEach { row ->
                        launch {
                            val deviceEntity = repository.getDevice(row.deviceId)
                            val battery = if (deviceEntity != null) {
                                cachedBattery(row.deviceId) ?: fetchGate.withPermit {
                                    fetchDeviceBattery(deviceEntity)
                                }?.also { rememberBattery(row.deviceId, it) }
                            } else null

                            val level = battery?.levelPercent
                            // A device that answered without a battery level has no battery: it runs on mains.
                            // No answer at all stays a timeout.
                            val noBattery = battery != null && level == null
                            val charging = when {
                                noBattery -> "AC"
                                else -> battery?.chargingState?.takeIf { it.isNotBlank() } ?: "BATTERY"
                            }
                            val lowPower = battery?.lowPowerMode == true
                            val percentText = if (level != null) "$level%" else "---"
                            val stateTag = when {
                                level != null || noBattery -> "[${charging.uppercase()}]"
                                else -> "[TIMEOUT]"
                            }
                            val lowPowerTag = if (lowPower) " [LOW POWER]" else ""
                            val line = "[ONLINE]  ${row.deviceName.padEnd(nameColumnWidth)}│ ${percentText.padStart(4)} │ $stateTag$lowPowerTag"
                            val item = BatteryStatusItem(
                                deviceId = row.deviceId,
                                deviceName = row.deviceName,
                                levelPercent = level,
                                chargingState = charging,
                                lowPowerMode = lowPower,
                                online = true
                            )
                            _uiState.update { state ->
                                val cur = state.batteryOverlayState ?: return@update state
                                cur.copy(
                                    items = cur.items + item,
                                    logLines = cur.logLines + line
                                ).let { state.copy(batteryOverlayState = it) }
                            }
                        }
                    }
                }
            }

            // 3. Query offline devices last
            for (row in offlineRows) {
                val line = "[OFFLINE] ${row.deviceName.padEnd(nameColumnWidth)}│  --- │ [OFFLINE]"
                val item = BatteryStatusItem(
                    deviceId = row.deviceId,
                    deviceName = row.deviceName,
                    levelPercent = null,
                    chargingState = "",
                    lowPowerMode = false,
                    online = false
                )
                _uiState.update { state ->
                    val cur = state.batteryOverlayState ?: return@update state
                    cur.copy(
                        items = cur.items + item,
                        logLines = cur.logLines + line
                    ).let { state.copy(batteryOverlayState = it) }
                }
            }

            val summaryLine = "--------------------------------------------------\n" +
                "[DONE] Query complete: ${onlineRows.size + 1} online, ${offlineRows.size} offline."
            _uiState.update { state ->
                val cur = state.batteryOverlayState ?: return@update state
                cur.copy(
                    isComplete = true,
                    logLines = cur.logLines + summaryLine
                ).let { state.copy(batteryOverlayState = it) }
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

    private var freestyleEditSnapshot: FreestyleEditSnapshot? = null

    fun enterDeviceOrderEditMode() {
        val settings = FileApexServices.settings
        val currentRows = deviceRows.value
        val cardOffsets = currentRows.associate { row ->
            val cached = settings.freestyleCardNodeOffsets.value[row.deviceId]
            row.deviceId to Pair(cached?.first ?: row.cardPosX, cached?.second ?: row.cardPosY)
        }
        val tileOffsets = currentRows.associate { row ->
            val cached = settings.freestyleTileNodeOffsets.value[row.deviceId]
            row.deviceId to Pair(cached?.first ?: row.tilePosX, cached?.second ?: row.tilePosY)
        }
        val cardVerticalOffsets = currentRows.associate { row ->
            val cached = settings.freestyleCardVerticalNodeOffsets.value[row.deviceId]
            row.deviceId to Pair(cached?.first ?: row.cardPosX, cached?.second ?: row.cardPosY)
        }
        val cardMenus = currentRows.associate { row ->
            val cached = settings.freestyleCardMenuOrders.value[row.deviceId]
            row.deviceId to (cached ?: row.cardMenuOrder)
        }
        val cardVerticalMenus = currentRows.associate { row ->
            val cached = settings.freestyleCardVerticalMenuOrders.value[row.deviceId]
            row.deviceId to (cached ?: row.cardMenuOrder)
        }
        val tileMenus = currentRows.associate { row ->
            val cached = settings.freestyleTileMenuOrders.value[row.deviceId]
            row.deviceId to (cached ?: row.tileMenuOrder)
        }
        freestyleEditSnapshot = FreestyleEditSnapshot(
            cardOptionsPos = Pair(settings.freestyleCardOptionsPosX.value, settings.freestyleCardOptionsPosY.value),
            cardVerticalOptionsPos = Pair(settings.freestyleCardVerticalOptionsPosX.value, settings.freestyleCardVerticalOptionsPosY.value),
            tileOptionsPos = Pair(settings.freestyleTileOptionsPosX.value, settings.freestyleTileOptionsPosY.value),
            optionsMenuOrder = settings.freestyleOptionsMenuOrder.value,
            cardNodeOffsets = cardOffsets,
            cardVerticalNodeOffsets = cardVerticalOffsets,
            tileNodeOffsets = tileOffsets,
            cardMenuOrders = cardMenus,
            cardVerticalMenuOrders = cardVerticalMenus,
            tileMenuOrders = tileMenus,
            freestyleLayoutMode = settings.freestyleLayoutMode.value
        )
        _uiState.update {
            it.copy(
                deviceOrderEditMode = true,
                editOrderRows = deviceRows.value
            )
        }
    }

    fun exitDeviceOrderEditMode() {
        freestyleEditSnapshot = null
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
        val snapshot = freestyleEditSnapshot
        if (snapshot != null) {
            val settings = FileApexServices.settings
            settings.setFreestyleOptionsPosition(FreestyleLayoutMode.CARDS_HORIZONTAL, snapshot.cardOptionsPos.first, snapshot.cardOptionsPos.second)
            settings.setFreestyleOptionsPosition(FreestyleLayoutMode.CARDS_VERTICAL, snapshot.cardVerticalOptionsPos.first, snapshot.cardVerticalOptionsPos.second)
            settings.setFreestyleOptionsPosition(FreestyleLayoutMode.TILES, snapshot.tileOptionsPos.first, snapshot.tileOptionsPos.second)
            settings.setFreestyleOptionsMenuOrder(snapshot.optionsMenuOrder)
            settings.setFreestyleLayoutMode(snapshot.freestyleLayoutMode)

            snapshot.cardNodeOffsets.forEach { (id, offset) ->
                if (offset.first != null && offset.second != null) {
                    settings.setFreestyleCardNodeOffset(id, offset.first!!, offset.second!!)
                }
                saveDeviceCardPosition(id, offset.first, offset.second)
            }
            snapshot.cardVerticalNodeOffsets.forEach { (id, offset) ->
                if (offset.first != null && offset.second != null) {
                    settings.setFreestyleCardVerticalNodeOffset(id, offset.first!!, offset.second!!)
                }
            }
            snapshot.tileNodeOffsets.forEach { (id, offset) ->
                if (offset.first != null && offset.second != null) {
                    settings.setFreestyleTileNodeOffset(id, offset.first!!, offset.second!!)
                }
                saveDeviceTilePosition(id, offset.first, offset.second)
            }
            snapshot.cardMenuOrders.forEach { (id, order) ->
                settings.setFreestyleCardMenuOrder(id, order)
                saveDeviceCardMenuOrder(id, order)
            }
            snapshot.cardVerticalMenuOrders.forEach { (id, order) ->
                settings.setFreestyleCardVerticalMenuOrder(id, order)
            }
            snapshot.tileMenuOrders.forEach { (id, order) ->
                settings.setFreestyleTileMenuOrder(id, order)
                saveDeviceTileMenuOrder(id, order)
            }
        }
        val rows = _uiState.value.editOrderRows
        if (rows.isNotEmpty()) {
            val alphabetical = DeviceOrderCoordinator.applyOrderIds(
                rows,
                DeviceOrderCoordinator.alphabeticalOrderIds(rows)
            )
            _uiState.update { it.copy(editOrderRows = alphabetical) }
        }
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

    fun saveDeviceCardPosition(deviceId: String, x: Float?, y: Float?) {
        viewModelScope.launch {
            repository.saveDeviceCardLayout(deviceId, x, y)
        }
    }

    fun saveDeviceTilePosition(deviceId: String, x: Float?, y: Float?) {
        viewModelScope.launch {
            repository.saveDeviceTileLayout(deviceId, x, y)
        }
    }

    fun saveDeviceCardMenuOrder(deviceId: String, menuOrder: String) {
        viewModelScope.launch {
            repository.saveDeviceCardLayout(deviceId, null, null, menuOrder = menuOrder)
        }
    }

    fun saveDeviceTileMenuOrder(deviceId: String, menuOrder: String) {
        viewModelScope.launch {
            repository.saveDeviceTileLayout(deviceId, null, null, menuOrder = menuOrder)
        }
    }
}

data class FreestyleEditSnapshot(
    val cardOptionsPos: Pair<Float?, Float?>,
    val cardVerticalOptionsPos: Pair<Float?, Float?>,
    val tileOptionsPos: Pair<Float?, Float?>,
    val optionsMenuOrder: String,
    val cardNodeOffsets: Map<String, Pair<Float?, Float?>>,
    val cardVerticalNodeOffsets: Map<String, Pair<Float?, Float?>>,
    val tileNodeOffsets: Map<String, Pair<Float?, Float?>>,
    val cardMenuOrders: Map<String, String>,
    val cardVerticalMenuOrders: Map<String, String>,
    val tileMenuOrders: Map<String, String>,
    val freestyleLayoutMode: FreestyleLayoutMode
)

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

    data class Demo(
        override val deviceId: String,
        override val displayName: String,
        override val rootPath: String = "/"
    ) : BrowseTarget
}
