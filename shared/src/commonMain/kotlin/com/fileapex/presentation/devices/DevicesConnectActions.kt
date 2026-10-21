package com.fileapex.presentation.devices

import androidx.lifecycle.viewModelScope
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.LanPresenceTiming
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.network.PeerReachabilityMessages
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.presentation.PendingPinUnlock
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DevicesConnectActions(
    private val _uiState: MutableStateFlow<DevicesUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val repository = FileApexServices.deviceRepository
    private val presence = FileApexServices.presenceMonitor
    private val identity: LocalIdentity
        get() = FileApexServices.localIdentity

    private var pendingOpenAction: ((BrowseTarget) -> Unit)? = null

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
        val outcome = cancellableCatching {
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
        if (!presence.canAttemptRoutes()) {
            return DeviceConnectOutcome.Unreachable(
                detail = PeerReachabilityMessages.localWifiRequired(),
                quickFail = true
            )
        }
        val peer = repository.getDevice(device.deviceId) ?: device
        val initialEndpoint = presence.probeNow(peer)
            ?: run {
                presence.validatePeerOnDemand(peer)
                presence.probeNow(repository.getDevice(device.deviceId) ?: peer)
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
        var remote = cancellableCatching {
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
            presence.reportRouteFailure(peer, endpoint.host)
            presence.validatePeerOnDemand(peer)
            refreshed = repository.getDevice(device.deviceId) ?: peer
            endpoint = presence.probeNow(refreshed)
                ?: return DeviceConnectOutcome.Unreachable(
                    detail = PeerReachabilityMessages.peerOffline()
                )
            remote = cancellableCatching {
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

    fun cancelPinUnlock() {
        pendingOpenAction = null
        _uiState.update { it.copy(pendingPinUnlock = null) }
    }

    fun confirmPinUnlock(pin: String) {
        val pending = _uiState.value.pendingPinUnlock ?: return
        viewModelScope.launch {
            cancellableCatching {
                require(pin.isNotBlank()) { AppI18n.t("pin_required_error") }
                val endpoint = presence.probeNow(pending.device)
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

    fun thisDeviceTarget(): BrowseTarget {
        return BrowseTarget.Local(
            deviceId = LocalIdentity.LOCAL_DEVICE_ID,
            displayName = AppI18n.t("this_device_named", LocalDeviceNameStore.current()),
            rootPath = identity.rootPath
        )
    }
}

internal sealed interface DeviceConnectOutcome {
    data class Open(val target: BrowseTarget) : DeviceConnectOutcome
    data class NeedsPin(
        val device: PairedDeviceEntity,
        val displayName: String
    ) : DeviceConnectOutcome
    data class Unreachable(
        val detail: String,
        /** Skip the minimum "Connecting…" delay, used for fast off-LAN privacy blocks. */
        val quickFail: Boolean = false
    ) : DeviceConnectOutcome
}
