package com.fileapex.presentation.devices

import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.cloud.diagnostics.DiagnosticsRelayErrors
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.DeviceDetailsCache
import com.fileapex.domain.diagnostics.DiagnosticGroup
import com.fileapex.domain.diagnostics.DiagnosticGroups
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.i18n.AppI18n
import com.fileapex.presentation.BatteryCheckOverlayState
import com.fileapex.presentation.BatteryStatusItem
import com.fileapex.presentation.DeviceDetailsState
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class DevicesDetailsActions(
    private val _uiState: MutableStateFlow<DevicesUiState>,
    private val viewModelScope: CoroutineScope, private val deviceRows: StateFlow<List<DeviceListRow>>
) {
    private val repository = FileApexServices.deviceRepository
    private val presence = FileApexServices.presenceMonitor

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

    init {
        // A removed device takes its saved details with it. An empty roster usually means "not loaded yet", so it prunes nothing.
        viewModelScope.launch(Dispatchers.IO) {
            deviceRows.collect { rows ->
                if (rows.isNotEmpty()) DeviceDetailsCache.prune(rows.map { it.deviceId }.toSet())
            }
        }
    }

    /**
     * Opens at once with what is saved for the device and refreshes behind it. With nothing saved yet nothing opens:
     * a short message says it is on its way and the details appear when they arrive.
     */
    fun requestDeviceDetails(device: PairedDeviceEntity, summaryOnly: Boolean = false) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { DeviceDetailsCache.get(device.deviceId) }
            showDetails(device, loading = true, snapshot = saved)
            // Waits first so a fast device never flashes it; clearing it takes the snackbar down.
            val hint = if (saved == null) launch {
                delay(DETAILS_HINT_DELAY_MS)
                _uiState.update { it.copy(detailsHint = AppI18n.t("getting_device_details", device.deviceName)) }
            } else null
            val groups = DiagnosticGroups.toRequest(
                FileApexServices.settings.deviceDetailsDisplayPreferences.first().visibleFieldIds(),
                saved
            )
            cancellableCatching {
                withContext(Dispatchers.IO) {
                    DeviceDetailsCache.remember(device.deviceId, fetchDeviceDetailsSnapshot(device, summaryOnly, groups), groups)
                }
            }.also { _ ->
                hint?.cancel()
                _uiState.update { it.copy(detailsHint = null) }
            }.fold(
                onSuccess = { fresh ->
                    if (!showDetails(device, loading = false, snapshot = fresh, onlyIfCurrent = true)) {
                        _uiState.update { it.copy(statusMessage = AppI18n.t("device_details_ready", device.deviceName)) }
                    }
                },
                onFailure = { error ->
                    showDetails(
                        device, loading = false, snapshot = saved,
                        error = DiagnosticsRelayErrors.fromThrowable(error), onlyIfCurrent = true
                    )
                }
            )
        }
    }

    /** False when [onlyIfCurrent] and the user has moved on to another device, so a late answer does not replace it. */
    private fun showDetails(
        device: PairedDeviceEntity,
        loading: Boolean,
        snapshot: PeerDeviceDiagnostics?,
        error: String? = null,
        onlyIfCurrent: Boolean = false
    ): Boolean {
        if (onlyIfCurrent && _uiState.value.deviceDetails?.deviceId != device.deviceId) return false
        _uiState.update {
            it.copy(
                deviceDetails = DeviceDetailsState(device.deviceId, device.deviceName, loading, snapshot, error),
                errorMessage = null
            )
        }
        return true
    }

    private suspend fun fetchDeviceDetailsSnapshot(
        device: PairedDeviceEntity,
        summaryOnly: Boolean,
        groups: Set<DiagnosticGroup>?
    ): PeerDeviceDiagnostics {
        presence.probeNow(device)?.let { direct ->
            cancellableCatching {
                FileApexServices.client.fetchDeviceDiagnostics(direct.host, direct.port, summaryOnly, groups)
            }.onSuccess { return it }
        }
        return DiagnosticsCloudRelay.fetchPeerDiagnostics(device.deviceId)
    }

    private val batteryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, BatteryDiagnostics>>()

    /** A second tap a few seconds later reuses the first answer instead of polling every device again. */
    private fun cachedBattery(deviceId: String): BatteryDiagnostics? {
        val entry = batteryCache[deviceId] ?: return null
        return entry.second.takeIf { TimeUtils.isWithinWindow(entry.first, BATTERY_CACHE_MS) }
    }

    private fun rememberBattery(deviceId: String, battery: BatteryDiagnostics) {
        batteryCache[deviceId] = TimeUtils.now() to battery
    }

    private suspend fun fetchDeviceBattery(device: PairedDeviceEntity): BatteryDiagnostics? = PeerBatteryFetch.fetch(device)

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
}

internal const val BATTERY_FETCH_CONCURRENCY = 4

internal const val BATTERY_CACHE_MS = 15_000L
internal const val DETAILS_HINT_DELAY_MS = 600L
