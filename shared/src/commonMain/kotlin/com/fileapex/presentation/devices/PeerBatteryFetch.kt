package com.fileapex.presentation.devices

import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PeerBatteryFetch {
    suspend fun fetch(device: PairedDeviceEntity): BatteryDiagnostics? = withContext(Dispatchers.IO) {
        FileApexServices.presenceMonitor.probeNow(device)?.let { direct ->
            val directResult = cancellableCatching {
                FileApexServices.client.fetchFastBattery(direct.host, direct.port)
            }.getOrNull()
            if (directResult != null) return@withContext directResult
        }
        cancellableCatching {
            DiagnosticsCloudRelay.fetchPeerDiagnostics(device.deviceId).battery
        }.getOrNull()
    }

    suspend fun fetch(deviceId: String): BatteryDiagnostics? =
        FileApexServices.deviceRepository.getDevice(deviceId)?.let { fetch(it) }
}
