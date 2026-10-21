package com.fileapex.presentation.devices

import androidx.lifecycle.viewModelScope
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.session.DeviceSessionManager
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class DevicesPairingActions(
    private val _uiState: MutableStateFlow<DevicesUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val repository = FileApexServices.deviceRepository
    private val identity: LocalIdentity
        get() = FileApexServices.localIdentity

    private var pairingDiscoveryPrune: Job? = null

    fun pairFromQrPayload(payload: PairingPayload) {
        viewModelScope.launch {
            cancellableCatching {
                if (payload.deviceId == identity.deviceId) {
                    error(AppI18n.t("pairing_scan_own_qr"))
                }
                val verified = cancellableCatching {
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

    fun cancelPinPairing() {
        _uiState.update { it.copy(pendingPinPairing = null) }
    }

    fun confirmPinPairing(pin: String) {
        val payload = _uiState.value.pendingPinPairing ?: return
        viewModelScope.launch {
            cancellableCatching {
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

    private suspend fun completePairing(payload: PairingPayload, pin: String?) {
        // Snapshot before anything is adopted: only this pre-pairing cluster is ours to announce.
        val preexistingIds = repository.listDevices().map { it.deviceId }.toSet()
        val verified = cancellableCatching {
            FileApexServices.client.fetchPeerNodeState(payload.host, payload.port)
        }.getOrNull()

        val broadcasterId = verified?.deviceId ?: payload.deviceId
        val broadcasterName = verified?.deviceName ?: payload.deviceName
        val broadcasterRoot = verified?.rootPath ?: payload.rootPath

        // Both users confirm: this screen shows the check code and waits for the user to say it matches the other
        // device's screen. Nothing is stored or sent before that, and the other device asks its own user afterwards.
        val hostPin = payload.tlsPin.ifBlank { verified?.tlsPin.orEmpty() }
        val hostPort = if (payload.tlsPort > 0) payload.tlsPort else verified?.tlsPort ?: 0
        val checkCode = com.fileapex.domain.pairing.PairingCheckCode.derive(
            hostKey = com.fileapex.domain.pairing.PairingCheckCode.key(hostPin, broadcasterId),
            joinerKey = com.fileapex.domain.pairing.PairingCheckCode.key(com.fileapex.security.tls.LocalTlsInfo.pin, identity.deviceId),
            pairingCode = payload.pairingCode
        )
        val codeMatches = com.fileapex.domain.pairing.PairingConfirmations.board.askJoiner(
            com.fileapex.domain.pairing.JoinerCheck(hostName = broadcasterName, checkCode = checkCode)
        )
        if (!codeMatches) error(AppI18n.t("pairing_cancelled"))

        val broadcasterEntity = PairedDeviceEntity(
            deviceId = broadcasterId,
            deviceName = broadcasterName,
            lastKnownIp = payload.host,
            port = payload.port,
            publicKeyHash = verified?.publicKeyHash?.ifBlank { payload.publicKeyHash } ?: payload.publicKeyHash,
            rootPath = broadcasterRoot
        )
        repository.adoptFromPairing(broadcasterEntity)
        // The check code covered this key, so the user has verified it against the other screen.
        if (hostPin.isNotEmpty() && hostPort > 0) {
            repository.recordTlsPin(broadcasterId, hostPin, hostPort)
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
            tlsPort = com.fileapex.security.tls.LocalTlsInfo.port,
            // Only the host sees this, after this user confirmed the code: it lets the host say "same hardware as X".
            hardwareHash = com.fileapex.platform.HardwareId.local
        )
        FileApexServices.pairingCoordinator.awaitShareServerReady()
        _uiState.update { it.copy(statusMessage = AppI18n.t("pairing_waiting_host", broadcasterName)) }
        try {
            FileApexServices.client.postPairingRespond(
                host = payload.host,
                port = payload.port,
                scannerDevice = scannerEntity,
                pin = pin,
                pairingCode = payload.pairingCode
            )
        } catch (error: Throwable) {
            // The other user declined, or the request failed: do not keep a device that never accepted us.
            if (broadcasterId !in preexistingIds) repository.discardUnconfirmedPairing(broadcasterId)
            throw error
        }
        if (!pin.isNullOrBlank()) {
            FileApexServices.client.rememberSessionPin(payload.host, payload.port, pin)
            DeviceSessionManager.markDeviceAccessed(broadcasterId)
        }

        // The first identity request carries our id, which a peer holding an old removal for us refuses (401).
        // Accepting the pairing clears that removal, and introductions can only be sealed for a peer whose key we hold.
        if (verified == null) {
            cancellableCatching { FileApexServices.client.fetchPeerNodeState(payload.host, payload.port) }
                .getOrNull()
                ?.let { state ->
                    repository.applyPeerNodeState(state, rosterDeviceId = payload.deviceId, observedDirectly = true)
                }
        }

        // Navigate back immediately: roster import / cluster announce can take seconds on cold LAN.
        _uiState.update {
            it.copy(
                statusMessage = AppI18n.t("paired_with", broadcasterName),
                pairingSucceeded = true
            )
        }

        viewModelScope.launch {
            cancellableCatching {
                if (!FileApexServices.pairingCoordinator.awaitPairingReady(broadcasterId, TLS_CONFIRM_WAIT_MS)) {
                    println("DevicesViewModel: pairing with ${broadcasterName} not ready; cluster sync left to catch-up")
                    return@cancellableCatching
                }
                val importedCount = FileApexServices.pairingCoordinator.importDirectPeerRoster(
                    host = payload.host,
                    port = payload.port,
                    excludeDeviceIds = setOf(broadcasterId),
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
}

internal const val TLS_CONFIRM_WAIT_MS = 10L * 60L * 1000L
