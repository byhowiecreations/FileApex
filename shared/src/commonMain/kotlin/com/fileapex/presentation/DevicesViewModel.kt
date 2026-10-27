package com.fileapex.presentation

import com.fileapex.data.db.isDockerNode
import com.fileapex.presentation.devices.DevicesClipboardActions
import com.fileapex.presentation.devices.DevicesConnectActions
import com.fileapex.presentation.devices.DevicesPairingActions
import com.fileapex.presentation.devices.DevicesRenameRemoveActions
import com.fileapex.presentation.devices.DevicesDropActions
import com.fileapex.presentation.devices.DevicesDetailsActions
import com.fileapex.presentation.devices.DevicesOrderActions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.di.FileApexServices
import com.fileapex.domain.device.DeviceOrderCoordinator
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingBeacon
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import com.fileapex.domain.presence.PresenceForegroundRefresh
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val detailsHint: String? = null,
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




class DevicesViewModel : ViewModel() {
    private val repository = FileApexServices.deviceRepository
    private val presence = FileApexServices.presenceMonitor
    private val transferManager = FileApexServices.transferManager
    private val identity: LocalIdentity
        get() = FileApexServices.localIdentity

    /** Scroll bookmark, not part of reactive UI state (avoids list recomposition on scroll). */
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
    @OptIn(ExperimentalCoroutinesApi::class)
    val deviceRows: StateFlow<List<DeviceListRow>> = PresenceForegroundRefresh.isForeground
        .flatMapLatest { isForeground ->
            if (isForeground) {
                combine(
                    combine(
                        repository.observeDevices(),
                        presence.reachabilityEpochMs,
                        presence.onlineDeviceIds,
                        combine(presence.onlineSnapshotEpochMs, presence.reachedVia) { _, via -> via },
                        com.fileapex.domain.peer.PeerAppVersions.versions
                    ) { devices, _, _, reachedVia, dockerVersions ->
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
                                    displayVersion = dockerVersions[device.deviceId]?.takeIf { device.isDockerNode() },
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
                                    tileMenuOrder = device.tileMenuOrder,
                                    reachedBy = reachedVia[device.deviceId]
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
            } else {
                emptyFlow()
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

    private val clipboard = DevicesClipboardActions(_uiState, viewModelScope)
    private val connect = DevicesConnectActions(_uiState, viewModelScope)
    private val pairing = DevicesPairingActions(_uiState, viewModelScope)
    private val renameRemove = DevicesRenameRemoveActions(_uiState, viewModelScope)
    private val drops = DevicesDropActions(_uiState, viewModelScope)
    private val details = DevicesDetailsActions(_uiState, viewModelScope, deviceRows)
    private val orderEdit = DevicesOrderActions(_uiState, viewModelScope, deviceRows)

    init {
        LocalDeviceNameStore.ensureLoaded()
        viewModelScope.launch {
            cancellableCatching { repository.reconcileDuplicateEndpoints() }
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

    fun initialListScrollIndex(): Int = listScrollIndex

    fun initialListScrollOffset(): Int = listScrollOffset

    fun saveListScroll(index: Int, offset: Int) {
        listScrollIndex = index
        listScrollOffset = offset
    }


    fun sendClipboardNow() = clipboard.sendClipboardNow()
    fun dismissClipboardConfigDialog() = clipboard.dismissClipboardConfigDialog()
    fun confirmClipboardConfig(mode: ClipboardShareMode, targetIds: Set<String>) = clipboard.confirmClipboardConfig(mode, targetIds)
    fun sendClipboardToDevice(deviceId: String) = clipboard.sendClipboardToDevice(deviceId)
    fun openDeviceOrExplain(deviceId: String, open: (BrowseTarget) -> Unit) = connect.openDeviceOrExplain(deviceId, open)
    fun openDeviceOrExplain(device: PairedDeviceEntity, open: (BrowseTarget) -> Unit) = connect.openDeviceOrExplain(device, open)
    fun cancelPinUnlock() = connect.cancelPinUnlock()
    fun confirmPinUnlock(pin: String) = connect.confirmPinUnlock(pin)
    fun browseTargetFor(
        device: PairedDeviceEntity,
        host: String = device.lastKnownIp,
        port: Int = device.port,
        pinRequired: Boolean = false
    ): BrowseTarget = connect.browseTargetFor(device, host, port, pinRequired)
    fun thisDeviceTarget(): BrowseTarget = connect.thisDeviceTarget()
    fun pairFromQrPayload(payload: PairingPayload) = pairing.pairFromQrPayload(payload)
    fun startPairingDiscovery() = pairing.startPairingDiscovery()
    fun stopPairingDiscovery() = pairing.stopPairingDiscovery()
    fun cancelPinPairing() = pairing.cancelPinPairing()
    fun confirmPinPairing(pin: String) = pairing.confirmPinPairing(pin)
    fun beginRename(deviceId: String) = renameRemove.beginRename(deviceId)
    fun cancelRename() = renameRemove.cancelRename()
    fun confirmRename(deviceId: String, newName: String) = renameRemove.confirmRename(deviceId, newName)
    fun removeDevice(deviceId: String) = renameRemove.removeDevice(deviceId)
    fun sendDroppedLocalFiles(deviceId: String, absolutePaths: List<String>) = drops.sendDroppedLocalFiles(deviceId, absolutePaths)
    fun requestDeviceDetails(deviceId: String, summaryOnly: Boolean = false) = details.requestDeviceDetails(deviceId, summaryOnly)
    fun requestDeviceDetails(device: PairedDeviceEntity, summaryOnly: Boolean = false) = details.requestDeviceDetails(device, summaryOnly)
    fun dismissDeviceDetails() = details.dismissDeviceDetails()
    fun checkBatteries() = details.checkBatteries()
    fun dismissBatteryOverlay() = details.dismissBatteryOverlay()
    fun enterDeviceOrderEditMode() = orderEdit.enterDeviceOrderEditMode()
    fun exitDeviceOrderEditMode() = orderEdit.exitDeviceOrderEditMode()
    fun reorderEditDevice(fromIndex: Int, toIndex: Int) = orderEdit.reorderEditDevice(fromIndex, toIndex)
    fun revertDeviceOrderInEditMode() = orderEdit.revertDeviceOrderInEditMode()
    fun saveDeviceOrderAndExitEditMode() = orderEdit.saveDeviceOrderAndExitEditMode()
    fun saveDeviceCardPosition(deviceId: String, x: Float?, y: Float?) = orderEdit.saveDeviceCardPosition(deviceId, x, y)
    fun saveDeviceTilePosition(deviceId: String, x: Float?, y: Float?) = orderEdit.saveDeviceTilePosition(deviceId, x, y)
    fun saveDeviceCardMenuOrder(deviceId: String, menuOrder: String) = orderEdit.saveDeviceCardMenuOrder(deviceId, menuOrder)
    fun saveDeviceTileMenuOrder(deviceId: String, menuOrder: String) = orderEdit.saveDeviceTileMenuOrder(deviceId, menuOrder)
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

    data class Demo(
        override val deviceId: String,
        override val displayName: String,
        override val rootPath: String = "/"
    ) : BrowseTarget
}
