package com.fileapex.domain.device

import com.fileapex.cloud.CloudLayoutSync
import com.fileapex.cloud.CloudUserLayout
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.presentation.DeviceListRow
import com.fileapex.util.TimestampDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SSOT for paired-device list ordering and optional cloud layout sync.
 */
object DeviceOrderCoordinator {
    private val _revisionEpochMs = MutableStateFlow(0L)
    val revisionEpochMs: StateFlow<Long> = _revisionEpochMs.asStateFlow()

    fun applySavedOrder(rows: List<DeviceListRow>): List<DeviceListRow> =
        applyOrderIds(rows, readSavedOrderIds(rows))

    fun alphabeticalOrderIds(rows: List<DeviceListRow>): List<String> =
        rows.sortedBy { it.deviceName.lowercase() }.map { it.deviceId }

    fun applyOrderIds(rows: List<DeviceListRow>, orderIds: List<String>): List<DeviceListRow> {
        if (rows.isEmpty()) return emptyList()
        val byId = rows.associateBy { it.deviceId }
        val seen = mutableSetOf<String>()
        val ordered = buildList {
            for (id in orderIds) {
                val row = byId[id] ?: continue
                if (seen.add(id)) add(row)
            }
        }
        val remainder = rows
            .filter { it.deviceId !in seen }
            .sortedBy { it.deviceName.lowercase() }
        return ordered + remainder
    }

    fun saveLocalOrder(deviceIds: List<String>) {
        val settings = FileApexServices.settings
        val encoded = DeviceOrderCodec.encode(deviceIds)
        val epochMs = TimestampDiagnostics.mutatingNow("DeviceOrderCoordinator.updatedAtEpochMs")
        settings.setDeviceOrderIds(encoded)
        settings.setDeviceOrderUpdatedAtEpochMs(epochMs)
        bumpRevision()
        if (settings.syncLayoutEnabled.value) {
            CloudLayoutSync.publishIfLinked(deviceIds, epochMs)
        }
    }

    fun revertToAlphabetical(rows: List<DeviceListRow>) {
        saveLocalOrder(alphabeticalOrderIds(rows))
    }

    fun applyRemoteLayoutIfNewer(layout: CloudUserLayout) {
        val settings = FileApexServices.settings
        if (!settings.syncLayoutEnabled.value) return
        if (layout.updatedAtEpochMs <= settings.deviceOrderUpdatedAtEpochMs.value) return
        settings.setDeviceOrderIds(DeviceOrderCodec.encode(layout.deviceOrderIds))
        settings.setDeviceOrderUpdatedAtEpochMs(layout.updatedAtEpochMs)
        bumpRevision()
    }

    suspend fun onSyncLayoutEnabledChanged(enabled: Boolean) {
        val settings = FileApexServices.settings
        settings.setSyncLayoutEnabled(enabled)
        bumpRevision()
        if (enabled) {
            val rows = orderingRowsFromDevices(FileApexServices.deviceRepository.listDevices())
            val order = readSavedOrderIds(rows).ifEmpty {
                alphabeticalOrderIds(rows)
            }
            saveLocalOrder(order)
        }
    }

    fun orderingRowsFromDevices(devices: List<PairedDeviceEntity>): List<DeviceListRow> {
        val identity = FileApexServices.localIdentity
        return devices
            .distinctBy { it.deviceId }
            .filter { device ->
                device.deviceId != LocalIdentity.LOCAL_DEVICE_ID &&
                    device.deviceId != identity.deviceId
            }
            .map { device ->
                DeviceListRow(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    online = false,
                    appVersion = null
                )
            }
    }

    private fun readSavedOrderIds(rows: List<DeviceListRow>): List<String> {
        val decoded = DeviceOrderCodec.decode(FileApexServices.settings.deviceOrderIds.value)
        if (decoded.isEmpty()) return emptyList()
        val validIds = rows.map { it.deviceId }.toSet()
        return decoded.filter { it in validIds }
    }

    private fun bumpRevision() {
        _revisionEpochMs.value = TimestampDiagnostics.mutatingNow("DeviceOrderCoordinator.revision")
    }
}

internal object DeviceOrderCodec {
    private const val SEPARATOR = "\u001f"

    fun encode(deviceIds: List<String>): String =
        deviceIds.joinToString(SEPARATOR)

    fun decode(raw: String): List<String> =
        raw.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
