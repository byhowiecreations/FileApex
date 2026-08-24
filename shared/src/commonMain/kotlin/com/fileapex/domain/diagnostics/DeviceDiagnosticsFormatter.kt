package com.fileapex.domain.diagnostics

import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.formatLocalizedNumber
import com.fileapex.util.TimeUtils
import kotlin.math.roundToInt

/** Human-readable labels for [PeerDeviceDiagnostics] UI rows. */
object DeviceDiagnosticsFormatter {
    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val rounded = if (unitIndex == 0) {
            value.roundToInt().toString()
        } else {
            String.format("%.1f", value)
        }
        return "$rounded ${units[unitIndex]}"
    }

    fun formatPercent(value: Int?): String =
        value?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—"

    fun formatTemperature(celsius: Double?): String =
        celsius?.let { String.format("%.1f °C", it) } ?: "—"

    fun formatDbm(value: Int?): String =
        value?.let { "$it dBm" } ?: "—"

    fun formatFrequencyMhz(value: Int?): String =
        value?.takeIf { it > 0 }?.let { "$it MHz" } ?: "—"

    fun formatUptime(uptimeMs: Long?): String {
        if (uptimeMs == null || uptimeMs < 0L) return "—"
        val totalMinutes = uptimeMs / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return buildList {
            if (days > 0) add(AppI18n.t("diag_uptime_d", days.toString()))
            if (hours > 0) add(AppI18n.t("diag_uptime_h", hours.toString()))
            add(AppI18n.t("diag_uptime_m", minutes.toString()))
        }.joinToString(" ")
    }

    fun formatBootEpoch(epochMs: Long?): String {
        if (epochMs == null || epochMs <= 0L) return "—"
        return TimeUtils.formatUtcToLocal(epochMs)
    }

    fun storageSummary(storage: StorageDiagnostics): String {
        val used = storage.usedBytes
        val total = storage.totalBytes
        if (used == null || total == null || total <= 0L) {
            return "—"
        }
        return AppI18n.t("storage_used", formatBytes(used), formatBytes(total))
    }

    fun memorySummary(memory: MemoryDiagnostics): String {
        val available = memory.availableBytes
        val total = memory.totalBytes
        if (available == null || total == null || total <= 0L) {
            return "—"
        }
        val used = memory.usedBytes ?: (total - available).coerceAtLeast(0L)
        return AppI18n.t(
            "memory_free_of",
            formatBytes(available),
            formatBytes(total),
            formatBytes(used)
        )
    }

    fun formatRefreshRate(hz: Float?): String =
        hz?.takeIf { it > 0f }?.let { String.format("%.1f Hz", it) } ?: "—"

    fun processorSummary(processor: ProcessorDiagnostics): String {
        val cores = when {
            processor.activeCoreCount != null && processor.totalCoreCount != null ->
                AppI18n.t(
                    "cores_active_total",
                    formatLocalizedNumber(processor.activeCoreCount),
                    formatLocalizedNumber(processor.totalCoreCount)
                )
            processor.totalCoreCount != null ->
                AppI18n.plural("n_cores", processor.totalCoreCount, formatLocalizedNumber(processor.totalCoreCount))
            else -> "—"
        }
        val arch = processor.architecture.ifBlank { "—" }
        val hardware = processor.hardware.ifBlank { "—" }
        val freq = processor.frequencyScaling.ifBlank { "—" }
        return "$arch · $hardware · $cores · $freq"
    }

    fun detailRows(
        snapshot: PeerDeviceDiagnostics,
        preferences: DeviceDetailsDisplayPreferences = DeviceDetailsDisplayPreferences.defaults()
    ): List<Pair<String, String>> {
        val networkType = snapshot.network.interfaceType.trim()
        val isWifi = networkType.equals("Wi-Fi", ignoreCase = true)
        val isCellular = networkType.equals("Cellular", ignoreCase = true)

        fun valueFor(field: DeviceDetailsFieldId): String = when (field) {
            DeviceDetailsFieldId.Platform -> snapshot.platform.ifBlank { "—" }
            DeviceDetailsFieldId.Collected -> formatBootEpoch(snapshot.collectedAtEpochMs)
            DeviceDetailsFieldId.Make -> snapshot.device.make.ifBlank { "—" }
            DeviceDetailsFieldId.Model -> snapshot.device.model.ifBlank { "—" }
            DeviceDetailsFieldId.Kernel -> snapshot.device.kernelVersion.ifBlank { "—" }
            DeviceDetailsFieldId.OsBuild -> snapshot.device.osBuildVersion.ifBlank { "—" }
            DeviceDetailsFieldId.Processor -> processorSummary(snapshot.processor)
            DeviceDetailsFieldId.CpuArchitecture -> snapshot.processor.architecture.ifBlank { "—" }
            DeviceDetailsFieldId.CpuHardware -> snapshot.processor.hardware.ifBlank { "—" }
            DeviceDetailsFieldId.CpuCores -> when {
                snapshot.processor.activeCoreCount != null &&
                    snapshot.processor.totalCoreCount != null ->
                    AppI18n.t(
                        "cores_active_total",
                        formatLocalizedNumber(snapshot.processor.activeCoreCount),
                        formatLocalizedNumber(snapshot.processor.totalCoreCount)
                    )
                snapshot.processor.totalCoreCount != null ->
                    AppI18n.t("n_total", formatLocalizedNumber(snapshot.processor.totalCoreCount))
                else -> "—"
            }
            DeviceDetailsFieldId.CpuFrequency -> snapshot.processor.frequencyScaling.ifBlank { "—" }
            DeviceDetailsFieldId.DisplayResolution -> snapshot.display.resolution.ifBlank { "—" }
            DeviceDetailsFieldId.RefreshRate -> formatRefreshRate(snapshot.display.refreshRateHz)
            DeviceDetailsFieldId.Brightness -> formatPercent(snapshot.display.brightnessPercent)
            DeviceDetailsFieldId.BatteryLevel -> formatPercent(snapshot.battery.levelPercent)
            DeviceDetailsFieldId.Charging -> localizeCharging(snapshot.battery.chargingState)
            DeviceDetailsFieldId.BatteryTemp -> formatTemperature(snapshot.battery.temperatureCelsius)
            DeviceDetailsFieldId.Storage -> storageSummary(snapshot.storage)
            DeviceDetailsFieldId.NetworkType -> localizeNetworkType(networkType)
            DeviceDetailsFieldId.Ssid -> snapshot.network.ssid.ifBlank { "—" }
            DeviceDetailsFieldId.Signal -> formatDbm(snapshot.network.signalDbm)
            DeviceDetailsFieldId.LinkSpeed ->
                snapshot.network.linkSpeedMbps?.let { "$it Mbps" } ?: "—"
            DeviceDetailsFieldId.FrequencyBand -> snapshot.network.frequencyBand.ifBlank { "—" }
            DeviceDetailsFieldId.Channel -> snapshot.network.channel?.toString() ?: "—"
            DeviceDetailsFieldId.CellularGeneration ->
                snapshot.network.networkGeneration.ifBlank { "—" }
            DeviceDetailsFieldId.CellularCarrier -> snapshot.network.carrier.ifBlank { "—" }
            DeviceDetailsFieldId.CellularSignal -> formatDbm(snapshot.network.signalDbm)
            DeviceDetailsFieldId.CellularFrequency -> formatFrequencyMhz(snapshot.network.frequencyMhz)
            DeviceDetailsFieldId.CellularBand -> snapshot.network.cellBand.ifBlank { "—" }
            DeviceDetailsFieldId.Uptime -> formatUptime(snapshot.uptime.uptimeMs)
            DeviceDetailsFieldId.LastBoot -> formatBootEpoch(snapshot.uptime.bootEpochMs)
            DeviceDetailsFieldId.ThermalState -> localizeThermal(snapshot.thermal.state)
            DeviceDetailsFieldId.ThermalTemp -> formatTemperature(snapshot.thermal.temperatureCelsius)
            DeviceDetailsFieldId.Memory -> memorySummary(snapshot.memory)
        }

        return preferences.visibleFieldIds()
            .filter { field ->
                when {
                    field.wifiOnly -> isWifi
                    field.cellularOnly -> isCellular
                    else -> true
                }
            }
            .map { field -> com.fileapex.i18n.AppI18n.t("field_${field.name}") to valueFor(field) }
    }

    private fun localizeCharging(raw: String): String {
        val token = raw.trim()
        if (token.isEmpty()) return "—"
        return when (token.lowercase()) {
            "full" -> AppI18n.t("charge_full")
            "ac" -> AppI18n.t("charge_ac")
            "usb" -> AppI18n.t("charge_usb")
            "wireless" -> AppI18n.t("charge_wireless")
            "discharging" -> AppI18n.t("charge_discharging")
            "unknown" -> AppI18n.t("unknown")
            "not available" -> AppI18n.t("not_available")
            else -> token
        }
    }

    private fun localizeNetworkType(raw: String): String {
        val token = raw.trim()
        if (token.isEmpty()) return "—"
        return when (token.lowercase()) {
            "wi-fi", "wifi" -> AppI18n.t("net_wifi")
            "ethernet" -> AppI18n.t("net_ethernet")
            "cellular" -> AppI18n.t("net_cellular")
            "unknown" -> AppI18n.t("unknown")
            else -> token
        }
    }

    private fun localizeThermal(raw: String): String {
        val token = raw.trim()
        if (token.isEmpty()) return "—"
        return when (token.lowercase()) {
            "nominal" -> AppI18n.t("thermal_nominal")
            "fair" -> AppI18n.t("thermal_fair")
            "serious" -> AppI18n.t("thermal_serious")
            "critical" -> AppI18n.t("thermal_critical")
            "unknown" -> AppI18n.t("unknown")
            "not available" -> AppI18n.t("not_available")
            else -> token
        }
    }
}
