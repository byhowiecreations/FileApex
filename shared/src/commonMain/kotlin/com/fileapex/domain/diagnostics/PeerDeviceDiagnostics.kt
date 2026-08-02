package com.fileapex.domain.diagnostics

import kotlinx.serialization.Serializable

/**
 * On-demand peer diagnostic snapshot — never included in periodic LAN heartbeats.
 */
@Serializable
data class PeerDeviceDiagnostics(
    val collectedAtEpochMs: Long,
    val platform: String = "",
    val device: DeviceIdentityDiagnostics = DeviceIdentityDiagnostics(),
    val processor: ProcessorDiagnostics = ProcessorDiagnostics(),
    val display: DisplayDiagnostics = DisplayDiagnostics(),
    val battery: BatteryDiagnostics = BatteryDiagnostics(),
    val storage: StorageDiagnostics = StorageDiagnostics(),
    val network: NetworkDiagnostics = NetworkDiagnostics(),
    val uptime: UptimeDiagnostics = UptimeDiagnostics(),
    val thermal: ThermalDiagnostics = ThermalDiagnostics(),
    val memory: MemoryDiagnostics = MemoryDiagnostics()
)

@Serializable
data class DeviceIdentityDiagnostics(
    val make: String = "",
    val model: String = "",
    val kernelVersion: String = "",
    val osBuildVersion: String = ""
)

@Serializable
data class ProcessorDiagnostics(
    val architecture: String = "",
    val hardware: String = "",
    val activeCoreCount: Int? = null,
    val totalCoreCount: Int? = null,
    val frequencyScaling: String = ""
)

@Serializable
data class DisplayDiagnostics(
    val resolution: String = "",
    val refreshRateHz: Float? = null,
    val brightnessPercent: Int? = null
)

@Serializable
data class BatteryDiagnostics(
    val levelPercent: Int? = null,
    /** AC, USB, Wireless, Discharging, Full, Not available, Unknown */
    val chargingState: String = "",
    val temperatureCelsius: Double? = null
)

@Serializable
data class StorageDiagnostics(
    val usedBytes: Long? = null,
    val totalBytes: Long? = null
)

@Serializable
data class NetworkDiagnostics(
    /** Wi-Fi, Ethernet, Cellular, Unknown */
    val interfaceType: String = "",
    val ssid: String = "",
    val signalDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    /** 2.4 GHz, 5 GHz, 6 GHz, Unknown */
    val frequencyBand: String = "",
    val channel: Int? = null
)

@Serializable
data class UptimeDiagnostics(
    val uptimeMs: Long? = null,
    val bootEpochMs: Long? = null
)

@Serializable
data class ThermalDiagnostics(
    /** Nominal, Fair, Serious, Critical, Unknown, Not available */
    val state: String = "",
    val temperatureCelsius: Double? = null
)

@Serializable
data class MemoryDiagnostics(
    val totalBytes: Long? = null,
    val availableBytes: Long? = null,
    val usedBytes: Long? = null
)
