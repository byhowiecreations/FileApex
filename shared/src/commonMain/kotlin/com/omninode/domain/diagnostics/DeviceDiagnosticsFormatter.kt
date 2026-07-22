package com.omninode.domain.diagnostics

import com.omninode.util.TimeUtils
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

    fun formatUptime(uptimeMs: Long?): String {
        if (uptimeMs == null || uptimeMs < 0L) return "—"
        val totalMinutes = uptimeMs / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            add("${minutes}m")
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
        return "${formatBytes(used)} of ${formatBytes(total)} used"
    }

    fun memorySummary(memory: MemoryDiagnostics): String {
        val available = memory.availableBytes
        val total = memory.totalBytes
        if (available == null || total == null || total <= 0L) {
            return "—"
        }
        val used = memory.usedBytes ?: (total - available).coerceAtLeast(0L)
        return "${formatBytes(available)} free of ${formatBytes(total)} (${formatBytes(used)} used)"
    }

    fun formatRefreshRate(hz: Float?): String =
        hz?.takeIf { it > 0f }?.let { String.format("%.1f Hz", it) } ?: "—"

    fun processorSummary(processor: ProcessorDiagnostics): String {
        val cores = when {
            processor.activeCoreCount != null && processor.totalCoreCount != null ->
                "${processor.activeCoreCount} active / ${processor.totalCoreCount} total"
            processor.totalCoreCount != null -> "${processor.totalCoreCount} cores"
            else -> "—"
        }
        val arch = processor.architecture.ifBlank { "—" }
        val hardware = processor.hardware.ifBlank { "—" }
        val freq = processor.frequencyScaling.ifBlank { "—" }
        return "$arch · $hardware · $cores · $freq"
    }

    fun detailRows(snapshot: PeerDeviceDiagnostics): List<Pair<String, String>> {
        return listOf(
            "Platform" to snapshot.platform.ifBlank { "—" },
            "Collected" to formatBootEpoch(snapshot.collectedAtEpochMs),
            "Make" to snapshot.device.make.ifBlank { "—" },
            "Model" to snapshot.device.model.ifBlank { "—" },
            "Kernel" to snapshot.device.kernelVersion.ifBlank { "—" },
            "OS build" to snapshot.device.osBuildVersion.ifBlank { "—" },
            "Processor" to processorSummary(snapshot.processor),
            "CPU architecture" to snapshot.processor.architecture.ifBlank { "—" },
            "CPU hardware" to snapshot.processor.hardware.ifBlank { "—" },
            "CPU cores" to when {
                snapshot.processor.activeCoreCount != null &&
                    snapshot.processor.totalCoreCount != null ->
                    "${snapshot.processor.activeCoreCount} active / ${snapshot.processor.totalCoreCount} total"
                snapshot.processor.totalCoreCount != null ->
                    "${snapshot.processor.totalCoreCount} total"
                else -> "—"
            },
            "CPU frequency" to snapshot.processor.frequencyScaling.ifBlank { "—" },
            "Display resolution" to snapshot.display.resolution.ifBlank { "—" },
            "Refresh rate" to formatRefreshRate(snapshot.display.refreshRateHz),
            "Brightness" to formatPercent(snapshot.display.brightnessPercent),
            "Battery level" to formatPercent(snapshot.battery.levelPercent),
            "Charging" to snapshot.battery.chargingState.ifBlank { "—" },
            "Battery temp" to formatTemperature(snapshot.battery.temperatureCelsius),
            "Storage" to storageSummary(snapshot.storage),
            "Network type" to snapshot.network.interfaceType.ifBlank { "—" },
            "SSID" to snapshot.network.ssid.ifBlank { "—" },
            "Signal" to formatDbm(snapshot.network.signalDbm),
            "Link speed" to (snapshot.network.linkSpeedMbps?.let { "$it Mbps" } ?: "—"),
            "Frequency band" to snapshot.network.frequencyBand.ifBlank { "—" },
            "Channel" to (snapshot.network.channel?.toString() ?: "—"),
            "Uptime" to formatUptime(snapshot.uptime.uptimeMs),
            "Last boot" to formatBootEpoch(snapshot.uptime.bootEpochMs),
            "Thermal state" to snapshot.thermal.state.ifBlank { "—" },
            "Thermal temp" to formatTemperature(snapshot.thermal.temperatureCelsius),
            "Memory" to memorySummary(snapshot.memory)
        )
    }
}
