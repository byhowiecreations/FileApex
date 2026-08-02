package com.fileapex.platform

import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.DeviceIdentityDiagnostics
import com.fileapex.domain.diagnostics.DisplayDiagnostics
import com.fileapex.domain.diagnostics.MemoryDiagnostics
import com.fileapex.domain.diagnostics.NetworkDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.diagnostics.ProcessorDiagnostics
import com.fileapex.domain.diagnostics.StorageDiagnostics
import com.fileapex.domain.diagnostics.ThermalDiagnostics
import com.fileapex.domain.diagnostics.UptimeDiagnostics
import com.sun.management.OperatingSystemMXBean
import java.awt.GraphicsEnvironment
import java.io.File
import java.lang.management.ManagementFactory
import java.net.NetworkInterface
import kotlin.math.roundToInt

actual fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics {
    return PeerDeviceDiagnostics(
        collectedAtEpochMs = 0L,
        platform = "",
        device = readDeviceIdentity(),
        processor = readProcessor(),
        display = readDisplay(),
        battery = readBattery(),
        storage = readStorage(),
        network = readNetwork(),
        uptime = readUptime(),
        thermal = readThermal(),
        memory = readMemory()
    )
}

private fun readDeviceIdentity(): DeviceIdentityDiagnostics {
    val osName = System.getProperty("os.name").orEmpty()
    val osVersion = System.getProperty("os.version").orEmpty()
    val make = when {
        osName.contains("Mac", ignoreCase = true) -> "Apple"
        osName.isNotBlank() -> osName
        else -> "Unknown"
    }
    val model = readSysctl("hw.model")
        ?: readSysctl("machdep.cpu.brand_string")
        ?: System.getProperty("os.arch").orEmpty().ifBlank { "Unknown" }
    return DeviceIdentityDiagnostics(
        make = make,
        model = model,
        kernelVersion = osVersion.ifBlank { "Unknown" },
        osBuildVersion = listOf(osName, osVersion).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Unknown" }
    )
}

private fun readProcessor(): ProcessorDiagnostics {
    val totalCores = Runtime.getRuntime().availableProcessors().takeIf { it > 0 }
    val os = ManagementFactory.getOperatingSystemMXBean()
    val extended = os as? OperatingSystemMXBean
    val hardware = readSysctl("machdep.cpu.brand_string")
        ?: os.name.orEmpty()
    val loadPercent = extended?.cpuLoad?.takeIf { it >= 0.0 }?.let { (it * 100.0).roundToInt() }
    val frequencyScaling = when {
        loadPercent != null -> "CPU load ${loadPercent}%"
        else -> "Not available"
    }
    return ProcessorDiagnostics(
        architecture = System.getProperty("os.arch").orEmpty(),
        hardware = hardware,
        activeCoreCount = totalCores,
        totalCoreCount = totalCores,
        frequencyScaling = frequencyScaling
    )
}

private fun readDisplay(): DisplayDiagnostics {
    return runCatching {
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val bounds = device.defaultConfiguration.bounds
        val refreshRate = device.displayMode.refreshRate
            .takeIf { it > 0 }
            ?.toFloat()
        DisplayDiagnostics(
            resolution = "${bounds.width} x ${bounds.height}",
            refreshRateHz = refreshRate,
            brightnessPercent = null
        )
    }.getOrDefault(DisplayDiagnostics())
}

private fun readBattery(): BatteryDiagnostics {
    return BatteryDiagnostics(chargingState = "Not available")
}

private fun readStorage(): StorageDiagnostics {
    return runCatching {
        val home = File(System.getProperty("user.home").orEmpty().ifBlank { "/" })
        val total = home.totalSpace.takeIf { it > 0L }
        val free = home.freeSpace.takeIf { it >= 0L }
        if (total == null || free == null) {
            StorageDiagnostics()
        } else {
            StorageDiagnostics(
                usedBytes = (total - free).coerceAtLeast(0L),
                totalBytes = total
            )
        }
    }.getOrDefault(StorageDiagnostics())
}

private fun readNetwork(): NetworkDiagnostics {
    val active = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface ->
            iface.inetAddresses.toList().mapNotNull { address ->
                val host = address.hostAddress ?: return@mapNotNull null
                if (host.contains(':')) return@mapNotNull null
                iface.name to host
            }
        }
        .firstOrNull { (_, host) ->
            !host.startsWith("127.") && !host.startsWith("169.254.")
        }
        ?: return NetworkDiagnostics(interfaceType = "Unknown")

    val interfaceName = active.first
    val interfaceType = when {
        interfaceName.startsWith("eth") -> "Ethernet"
        interfaceName.startsWith("en") || interfaceName.contains("wlan", ignoreCase = true) -> "Wi-Fi"
        else -> "Unknown"
    }
    return NetworkDiagnostics(
        interfaceType = interfaceType,
        ssid = "",
        signalDbm = null,
        linkSpeedMbps = null,
        frequencyBand = "Unknown",
        channel = null
    )
}

private fun readUptime(): UptimeDiagnostics {
    val uptimeMs = ManagementFactory.getRuntimeMXBean().uptime
    val bootEpochMs = (System.currentTimeMillis() - uptimeMs).coerceAtLeast(0L)
    return UptimeDiagnostics(uptimeMs = uptimeMs, bootEpochMs = bootEpochMs)
}

private fun readThermal(): ThermalDiagnostics {
    return ThermalDiagnostics(state = "Not available", temperatureCelsius = null)
}

private fun readMemory(): MemoryDiagnostics {
    val os = ManagementFactory.getOperatingSystemMXBean()
    val extended = os as? OperatingSystemMXBean
    val total = extended?.totalMemorySize?.takeIf { it > 0L }
    val free = extended?.freeMemorySize?.takeIf { it >= 0L }
    val used = if (total != null && free != null) (total - free).coerceAtLeast(0L) else null
    return MemoryDiagnostics(
        totalBytes = total,
        availableBytes = free,
        usedBytes = used
    )
}

private fun readSysctl(name: String): String? {
    return runCatching {
        ProcessBuilder("/usr/sbin/sysctl", "-n", name)
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .use { it.readText().trim() }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}
