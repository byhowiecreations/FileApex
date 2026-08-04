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
import java.awt.Toolkit
import java.io.File
import java.lang.management.ManagementFactory
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

private val macCombinedProfilerJson = ThreadLocal<String?>()

actual fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics {
    if (isMacOs()) {
        macCombinedProfilerJson.set(
            readCommandOutput(
                "system_profiler",
                "SPAirPortDataType",
                "SPDisplaysDataType",
                "-json"
            )
        )
    }
    return try {
        runCatching {
            PeerDeviceDiagnostics(
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
        }.getOrElse {
            PeerDeviceDiagnostics(
                collectedAtEpochMs = 0L,
                device = readDeviceIdentitySafe(),
                uptime = readUptimeSafe()
            )
        }
    } finally {
        macCombinedProfilerJson.remove()
    }
}

private fun macProfilerJsonOrEmpty(): String = macCombinedProfilerJson.get().orEmpty()

private fun readDeviceIdentitySafe(): DeviceIdentityDiagnostics {
    return runCatching { readDeviceIdentity() }
        .getOrDefault(DeviceIdentityDiagnostics())
}

private fun readDeviceIdentity(): DeviceIdentityDiagnostics {
    val osName = System.getProperty("os.name").orEmpty()
    val osVersion = System.getProperty("os.version").orEmpty()
    val isMac = osName.contains("Mac", ignoreCase = true)
    val isWindows = osName.contains("Windows", ignoreCase = true)
    val make = when {
        isMac -> "Apple"
        isWindows -> readWindowsMake()
        osName.isNotBlank() -> osName
        else -> "Unknown"
    }
    val model = when {
        isMac -> readSysctl("hw.model")
            ?: readSysctl("machdep.cpu.brand_string")
            ?: System.getProperty("os.arch").orEmpty().ifBlank { "Unknown" }
        isWindows -> readWindowsModel()
        else -> System.getProperty("os.arch").orEmpty().ifBlank { "Unknown" }
    }
    return DeviceIdentityDiagnostics(
        make = make,
        model = model,
        kernelVersion = osVersion.ifBlank { "Unknown" },
        osBuildVersion = listOf(osName, osVersion).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Unknown" }
    )
}

private fun readWindowsMake(): String =
    readCommandOutput("wmic", "computersystem", "get", "manufacturer")
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() && !it.equals("Manufacturer", ignoreCase = true) }
        ?: "Windows"

private fun readWindowsModel(): String =
    readCommandOutput("wmic", "computersystem", "get", "model")
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() && !it.equals("Model", ignoreCase = true) }
        ?: System.getProperty("os.name").orEmpty().ifBlank { "Unknown" }

private fun readProcessor(): ProcessorDiagnostics {
    if (isMacOs()) return readMacProcessor()
    if (isWindowsOs()) return readWindowsProcessor()
    return readGenericProcessor()
}

private fun readMacProcessor(): ProcessorDiagnostics {
    return runCatching {
        val brand = readSysctl("machdep.cpu.brand_string")
            ?: readSysctl("hw.model")
            ?: "Apple Silicon"
        val logical = readSysctlInt("hw.logicalcpu")
        val physical = readSysctlInt("hw.physicalcpu")
        val freqMhz = readSysctlInt("hw.cpufrequency_max")?.let { it / 1_000_000 }
        val frequencyScaling = freqMhz?.takeIf { it > 0 }?.let { "Up to ${it} MHz" } ?: "Not available"
        ProcessorDiagnostics(
            architecture = System.getProperty("os.arch").orEmpty().ifBlank { "Unknown" },
            hardware = brand,
            activeCoreCount = physical ?: logical,
            totalCoreCount = logical,
            frequencyScaling = frequencyScaling
        )
    }.getOrDefault(readGenericProcessor())
}

private fun readWindowsProcessor(): ProcessorDiagnostics {
    return runCatching {
        val name = readCommandOutput("wmic", "cpu", "get", "Name")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() && !it.equals("Name", ignoreCase = true) }
            .orEmpty()
        readGenericProcessor().copy(
            hardware = name.ifBlank { readGenericProcessor().hardware }
        )
    }.getOrDefault(readGenericProcessor())
}

private fun readGenericProcessor(): ProcessorDiagnostics {
    return runCatching {
        val totalCores = Runtime.getRuntime().availableProcessors().takeIf { it > 0 }
        val os = ManagementFactory.getOperatingSystemMXBean()
        val extended = os as? OperatingSystemMXBean
        val hardware = readSysctl("machdep.cpu.brand_string")
            ?: os.name.orEmpty()
        val loadPercent = runCatching {
            extended?.cpuLoad?.takeIf { it >= 0.0 }?.let { (it * 100.0).roundToInt() }
        }.getOrNull()
        val frequencyScaling = when {
            loadPercent != null -> "CPU load ${loadPercent}%"
            else -> "Not available"
        }
        ProcessorDiagnostics(
            architecture = System.getProperty("os.arch").orEmpty().ifBlank { "Unknown" },
            hardware = hardware.ifBlank { "Unknown" },
            activeCoreCount = totalCores,
            totalCoreCount = totalCores,
            frequencyScaling = frequencyScaling
        )
    }.getOrDefault(
        ProcessorDiagnostics(
            architecture = System.getProperty("os.arch").orEmpty().ifBlank { "Unknown" }
        )
    )
}

private fun readDisplay(): DisplayDiagnostics {
    if (isMacOs()) {
        readMacDisplayViaSystemProfiler()?.let { return it }
    }
    return runCatching {
        if (GraphicsEnvironment.isHeadless()) {
            readDisplayFromToolkit()
        } else {
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
        }
    }.getOrElse { readDisplayFromToolkit() }
}

private fun readMacDisplayViaSystemProfiler(): DisplayDiagnostics? {
    val output = macProfilerJsonOrEmpty().ifBlank {
        readCommandOutput("system_profiler", "SPDisplaysDataType", "-json").orEmpty()
    }
    if (output.isBlank()) return null
    val resolutionLine = Regex(""""_spdisplays_resolution"\s*:\s*"([^"]+)"""")
        .find(output)
        ?.groupValues
        ?.get(1)
        ?.trim()
        .orEmpty()
    if (resolutionLine.isBlank()) {
        val fallback = Regex(""""_spdisplays_pixels"\s*:\s*"(\d+\s*x\s*\d+)"""")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
        if (fallback.isBlank()) return null
        return DisplayDiagnostics(resolution = fallback, refreshRateHz = null)
    }
    val atIndex = resolutionLine.indexOf('@')
    val resolution = if (atIndex > 0) {
        resolutionLine.substring(0, atIndex).trim()
    } else {
        resolutionLine
    }
    val refreshRateHz = Regex("""@?\s*([\d.]+)\s*Hz""", RegexOption.IGNORE_CASE)
        .find(resolutionLine)
        ?.groupValues
        ?.get(1)
        ?.toFloatOrNull()
    return DisplayDiagnostics(
        resolution = resolution,
        refreshRateHz = refreshRateHz?.takeIf { it > 0f }
    )
}

private fun readDisplayFromToolkit(): DisplayDiagnostics {
    return runCatching {
        val size = Toolkit.getDefaultToolkit().screenSize
        DisplayDiagnostics(
            resolution = "${size.width} x ${size.height}",
            refreshRateHz = null,
            brightnessPercent = null
        )
    }.getOrDefault(DisplayDiagnostics())
}

private fun readBattery(): BatteryDiagnostics {
    if (isMacOs()) {
        return readMacBattery()
    }
    return BatteryDiagnostics(chargingState = "Not available")
}

private fun readMacBattery(): BatteryDiagnostics {
    val output = readCommandOutput("pmset", "-g", "batt").orEmpty()
    if (output.isBlank() || !output.contains("InternalBattery", ignoreCase = true)) {
        return BatteryDiagnostics(chargingState = "Not available")
    }
    val percent = Regex("""(\d+)%""").find(output)?.groupValues?.get(1)?.toIntOrNull()
    val chargingState = when {
        output.contains("finishing charge", ignoreCase = true) -> "Full"
        output.contains("; charging;", ignoreCase = true) -> "AC"
        output.contains("drawing from 'AC Power'", ignoreCase = true) &&
            !output.contains("discharging", ignoreCase = true) -> "AC"
        output.contains("discharging", ignoreCase = true) -> "Discharging"
        output.contains("drawing from 'Battery Power'", ignoreCase = true) -> "Discharging"
        else -> "Unknown"
    }
    return BatteryDiagnostics(
        levelPercent = percent,
        chargingState = chargingState
    )
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
    return runCatching {
        val activeInterface = findActiveNetworkInterface() ?: return@runCatching NetworkDiagnostics(
            interfaceType = "Unknown"
        )
        val interfaceName = activeInterface.name
        val interfaceType = classifyInterfaceType(interfaceName, activeInterface.displayName)
        when (interfaceType) {
            "Wi-Fi" -> readWifiNetwork(interfaceName)
            "Ethernet" -> NetworkDiagnostics(interfaceType = "Ethernet")
            else -> NetworkDiagnostics(interfaceType = interfaceType.ifBlank { "Unknown" })
        }
    }.getOrDefault(NetworkDiagnostics(interfaceType = "Unknown"))
}

private fun findActiveNetworkInterface(): NetworkInterface? {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !isVirtualLanInterface(it) }
            .sortedWith(compareBy({ desktopInterfaceTier(it.name) }, { it.name }))
            .firstOrNull { iface ->
                iface.inetAddresses.toList().any { address ->
                    address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.hostAddress.orEmpty().startsWith("169.254.")
                }
            }
    }.getOrNull()
}

private fun classifyInterfaceType(name: String, displayName: String?): String {
    val lowered = name.lowercase()
    val display = displayName?.lowercase().orEmpty()
    return when {
        lowered.startsWith("eth") || display.contains("ethernet") -> "Ethernet"
        lowered.matches(Regex("en\\d+")) ||
            lowered.startsWith("wlan") ||
            display.contains("wi-fi") ||
            display.contains("wifi") ||
            display.contains("wireless") -> "Wi-Fi"
        else -> "Unknown"
    }
}

private fun readWifiNetwork(interfaceName: String): NetworkDiagnostics {
    val osName = System.getProperty("os.name").orEmpty()
    return when {
        osName.contains("Mac", ignoreCase = true) -> readMacWifiNetwork(interfaceName)
        osName.contains("Windows", ignoreCase = true) -> readWindowsWifiNetwork()
        else -> NetworkDiagnostics(interfaceType = "Wi-Fi")
    }
}

private data class ParsedWifiTelemetry(
    val ssid: String = "",
    val signalDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyBand: String = "",
    val channel: Int? = null
)

private fun readMacWifiNetwork(interfaceName: String): NetworkDiagnostics {
    val profiler = readMacWifiFromProfilerJson(macProfilerJsonOrEmpty())
        .takeUnless { it.ssid.isBlank() && it.signalDbm == null }
        ?: readMacWifiViaSystemProfiler()
    if (profiler.ssid.isNotBlank()) {
        return NetworkDiagnostics(
            interfaceType = "Wi-Fi",
            ssid = profiler.ssid,
            signalDbm = profiler.signalDbm,
            linkSpeedMbps = profiler.linkSpeedMbps,
            frequencyBand = profiler.frequencyBand.ifBlank { "Unknown" },
            channel = profiler.channel
        )
    }
    val wifiDevice = readMacWifiDeviceName().ifBlank { interfaceName }
    val ssidFromNetworkSetup = readMacSsidViaNetworkSetup(wifiDevice)
    val airport = readMacAirportInfo()
    val merged = ParsedWifiTelemetry(
        ssid = listOf(ssidFromNetworkSetup, profiler.ssid, airport.ssid).firstOrNull { it.isNotBlank() }.orEmpty(),
        signalDbm = profiler.signalDbm ?: airport.signalDbm,
        linkSpeedMbps = profiler.linkSpeedMbps ?: airport.linkSpeedMbps,
        frequencyBand = profiler.frequencyBand.takeUnless { it.isBlank() || it == "Unknown" }
            ?: airport.frequencyBand,
        channel = profiler.channel ?: airport.channel
    )
    return NetworkDiagnostics(
        interfaceType = "Wi-Fi",
        ssid = merged.ssid,
        signalDbm = merged.signalDbm,
        linkSpeedMbps = merged.linkSpeedMbps,
        frequencyBand = merged.frequencyBand.ifBlank { "Unknown" },
        channel = merged.channel
    )
}

private fun readMacWifiFromProfilerJson(json: String): ParsedWifiTelemetry {
    if (json.isBlank()) return ParsedWifiTelemetry()
    val currentBlock = Regex(""""spairport_current_network_information"\s*:\s*\{([^}]+)\}""")
        .find(json)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    if (currentBlock.isBlank()) return ParsedWifiTelemetry()
    val ssid = Regex(""""_name"\s*:\s*"([^"]+)"""").find(currentBlock)?.groupValues?.get(1)?.trim().orEmpty()
    val signalDbm = Regex(""""spairport_signal_noise"\s*:\s*"(-?\d+)\s*dBm""")
        .find(currentBlock)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    val linkSpeedMbps = Regex(""""spairport_network_rate"\s*:\s*(\d+)""")
        .find(currentBlock)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    val channel = Regex(""""spairport_network_channel"\s*:\s*"(\d+)""")
        .find(currentBlock)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    val band = when {
        currentBlock.contains("6GHz", ignoreCase = true) -> "6 GHz"
        channel == null -> "Unknown"
        channel in 1..14 -> "2.4 GHz"
        channel >= 36 -> "5 GHz"
        else -> "Unknown"
    }
    return ParsedWifiTelemetry(
        ssid = ssid,
        signalDbm = signalDbm,
        linkSpeedMbps = linkSpeedMbps,
        frequencyBand = band,
        channel = channel
    )
}

private fun readMacWifiDeviceName(): String {
    val output = readCommandOutput("networksetup", "-listallhardwareports").orEmpty()
    val lines = output.lines()
    var pendingWifi = false
    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("Hardware Port:", ignoreCase = true) &&
                trimmed.contains("wi-fi", ignoreCase = true) -> pendingWifi = true
            pendingWifi && trimmed.startsWith("Device:", ignoreCase = true) -> {
                return trimmed.substringAfter(':').trim()
            }
            trimmed.startsWith("Hardware Port:", ignoreCase = true) -> pendingWifi = false
        }
    }
    return ""
}

private fun readMacWifiViaSystemProfiler(): ParsedWifiTelemetry {
    val json = macProfilerJsonOrEmpty()
    if (json.isNotBlank()) {
        val fromJson = readMacWifiFromProfilerJson(json)
        if (fromJson.ssid.isNotBlank() || fromJson.signalDbm != null) {
            return fromJson
        }
    }
    val output = readCommandOutput("system_profiler", "SPAirPortDataType").orEmpty()
    if (output.isBlank()) return ParsedWifiTelemetry()

    val currentSection = output.substringAfter("Current Network Information:", "")
        .substringBefore("Other Local Wi-Fi Networks:")
        .ifBlank { output }

    val ssid = Regex("""(?:Network Name|SSID):\s*\n\s*(\S.+)$""", RegexOption.MULTILINE)
        .find(currentSection)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: Regex("""(?:Network Name|SSID):\s*(.+)$""", RegexOption.MULTILINE)
            .find(currentSection)
            ?.groupValues
            ?.get(1)
            ?.trim()
        ?: Regex("""Current Network Information:\s*\n\s+([^\n:]+):\s*\n\s+(?:PHY Mode|Channel):""", RegexOption.MULTILINE)
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
        .orEmpty()

    val signalLine = Regex("Signal / Noise:\\s*(-?\\d+)\\s*dBm").find(output)
    val signalDbm = signalLine?.groupValues?.get(1)?.toIntOrNull()

    val channelLine = Regex("Channel:\\s*(\\d+)").find(currentSection)
        ?: Regex("Channel:\\s*(\\d+)").find(output)
    val channel = channelLine?.groupValues?.get(1)?.toIntOrNull()

    val txRate = Regex("Transmit Rate:\\s*(\\d+)").find(currentSection)
        ?: Regex("Transmit Rate:\\s*(\\d+)").find(output)
    val linkSpeedMbps = txRate?.groupValues?.get(1)?.toIntOrNull()

    val band = when {
        currentSection.contains("6GHz", ignoreCase = true) ||
            output.contains("6GHz", ignoreCase = true) -> "6 GHz"
        channel == null -> "Unknown"
        channel in 1..14 -> "2.4 GHz"
        channel >= 36 -> "5 GHz"
        else -> "Unknown"
    }

    return ParsedWifiTelemetry(
        ssid = ssid,
        signalDbm = signalDbm,
        linkSpeedMbps = linkSpeedMbps,
        frequencyBand = band,
        channel = channel
    )
}

private fun readMacBootEpochMs(): Long? {
    val output = readCommandOutput("/usr/sbin/sysctl", "-n", "kern.boottime").orEmpty()
    val sec = Regex("sec\\s*=\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    return sec * 1000L
}

private fun readSysctlInt(name: String): Int? = readSysctl(name)?.toIntOrNull()

private fun readSysctlLong(name: String): Long? = readSysctl(name)?.toLongOrNull()

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

private fun isWindowsOs(): Boolean =
    System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

private fun readMacSsidViaNetworkSetup(interfaceName: String): String {
    val output = readCommandOutput("networksetup", "-getairportnetwork", interfaceName).orEmpty()
    val prefix = "Current Wi-Fi Network:"
    return if (output.startsWith(prefix, ignoreCase = true)) {
        output.removePrefix(prefix).trim()
    } else {
        ""
    }
}

private fun readMacAirportInfo(): ParsedWifiTelemetry {
    val output = readCommandOutput(
        "/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport",
        "-I"
    ).orEmpty()
    if (output.isBlank()) return ParsedWifiTelemetry()
    val values = parseKeyColonValueBlock(output)
    val ssid = values["SSID"].orEmpty()
    val rssi = values["agrCtlRSSI"]?.toIntOrNull()
        ?: values["RSSI"]?.toIntOrNull()
    val txRate = values["lastTxRate"]?.toIntOrNull()
        ?: values["maxRate"]?.toIntOrNull()
    val channelRaw = values["channel"].orEmpty()
    val channelNumber = channelRaw.substringBefore(',').trim().toIntOrNull()
    val band = when {
        channelNumber == null -> "Unknown"
        channelNumber in 1..14 -> "2.4 GHz"
        channelNumber >= 36 -> "5 GHz"
        else -> "Unknown"
    }
    return ParsedWifiTelemetry(
        ssid = ssid,
        signalDbm = rssi,
        linkSpeedMbps = txRate,
        frequencyBand = band,
        channel = channelNumber
    )
}

private fun readWindowsWifiNetwork(): NetworkDiagnostics {
    val output = readCommandOutput("netsh", "wlan", "show", "interfaces").orEmpty()
    if (output.isBlank()) {
        return NetworkDiagnostics(interfaceType = "Wi-Fi")
    }
    val values = parseKeyColonValueBlock(output.replace("\r\n", "\n"))
    val state = values["State"].orEmpty()
    if (!state.contains("connected", ignoreCase = true)) {
        return NetworkDiagnostics(interfaceType = "Wi-Fi")
    }
    val ssid = values["SSID"].orEmpty()
    val signalPercent = values["Signal"]?.removeSuffix("%")?.trim()?.toIntOrNull()
    val signalDbm = signalPercent?.let { percentToDbm(it) }
    val channel = values["Channel"]?.toIntOrNull()
    val radioType = values["Radio type"].orEmpty()
    val band = when {
        radioType.contains("802.11ax", ignoreCase = true) && channel != null && channel >= 1 -> {
            if (channel <= 14) "2.4 GHz" else if (channel >= 36) "5 GHz" else "6 GHz"
        }
        channel != null && channel in 1..14 -> "2.4 GHz"
        channel != null && channel >= 36 -> "5 GHz"
        radioType.contains("2.4", ignoreCase = true) -> "2.4 GHz"
        radioType.contains("5", ignoreCase = true) -> "5 GHz"
        radioType.contains("6", ignoreCase = true) -> "6 GHz"
        else -> "Unknown"
    }
    val receiveRate = values["Receive rate (Mbps)"]?.toIntOrNull()
        ?: values["Receive rate"]?.substringBefore(' ')?.toIntOrNull()
    return NetworkDiagnostics(
        interfaceType = "Wi-Fi",
        ssid = ssid,
        signalDbm = signalDbm,
        linkSpeedMbps = receiveRate,
        frequencyBand = band,
        channel = channel
    )
}

private fun parseKeyColonValueBlock(text: String): Map<String, String> {
    return text.lineSequence()
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key.isBlank()) null else key to value
        }
        .toMap()
}

private fun percentToDbm(percent: Int): Int {
    val clamped = percent.coerceIn(0, 100)
    return -100 + (clamped * 0.7).roundToInt()
}

private fun readUptimeSafe(): UptimeDiagnostics =
    runCatching { readUptime() }.getOrDefault(UptimeDiagnostics())

private fun readUptime(): UptimeDiagnostics {
    if (isMacOs()) return readMacUptime()
    return readGenericUptime()
}

private fun readMacUptime(): UptimeDiagnostics {
    return runCatching {
        val bootEpochMs = readMacBootEpochMs()
        if (bootEpochMs != null && bootEpochMs > 0L) {
            val uptimeMs = (System.currentTimeMillis() - bootEpochMs).coerceAtLeast(0L)
            UptimeDiagnostics(uptimeMs = uptimeMs, bootEpochMs = bootEpochMs)
        } else {
            readGenericUptime()
        }
    }.getOrDefault(readGenericUptime())
}

private fun readGenericUptime(): UptimeDiagnostics {
    return runCatching {
        val uptimeMs = ManagementFactory.getRuntimeMXBean().uptime
        val bootEpochMs = (System.currentTimeMillis() - uptimeMs).coerceAtLeast(0L)
        UptimeDiagnostics(uptimeMs = uptimeMs, bootEpochMs = bootEpochMs)
    }.getOrDefault(UptimeDiagnostics())
}

private fun readThermal(): ThermalDiagnostics {
    return ThermalDiagnostics(state = "Not available", temperatureCelsius = null)
}

private fun readMemory(): MemoryDiagnostics {
    if (isMacOs()) return readMacMemory()
    return readGenericMemory()
}

private fun readMacMemory(): MemoryDiagnostics {
    return runCatching {
        val total = readSysctlLong("hw.memsize")
        val pageSize = readSysctlLong("hw.pagesize") ?: 4096L
        val vmStat = readCommandOutput("vm_stat").orEmpty()
        val freePages = Regex("Pages free:\\s+(\\d+)").find(vmStat)?.groupValues?.get(1)?.toLongOrNull()
        val inactivePages = Regex("Pages inactive:\\s+(\\d+)").find(vmStat)?.groupValues?.get(1)?.toLongOrNull()
        val available = if (freePages != null) {
            (freePages + (inactivePages ?: 0L)) * pageSize
        } else {
            null
        }
        val used = if (total != null && available != null) (total - available).coerceAtLeast(0L) else null
        if (total != null) {
            MemoryDiagnostics(totalBytes = total, availableBytes = available, usedBytes = used)
        } else {
            readGenericMemory()
        }
    }.getOrDefault(readGenericMemory())
}

private fun readGenericMemory(): MemoryDiagnostics {
    return runCatching {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val extended = os as? OperatingSystemMXBean
        val total = extended?.totalMemorySize?.takeIf { it > 0L }
        val free = extended?.freeMemorySize?.takeIf { it >= 0L }
        val used = if (total != null && free != null) (total - free).coerceAtLeast(0L) else null
        MemoryDiagnostics(
            totalBytes = total,
            availableBytes = free,
            usedBytes = used
        )
    }.getOrDefault(MemoryDiagnostics())
}

private fun readSysctl(name: String): String? {
    return readCommandOutput("/usr/sbin/sysctl", "-n", name)?.takeIf { it.isNotEmpty() }
}

private fun readCommandOutput(vararg command: String): String? {
    return runCatching {
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .use { it.readText().trim() }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private fun isVirtualLanInterface(iface: NetworkInterface): Boolean {
    val name = iface.name.lowercase()
    val display = iface.displayName?.lowercase().orEmpty()
    if (name.startsWith("lo") ||
        name.startsWith("utun") ||
        name.startsWith("awdl") ||
        name.startsWith("llw") ||
        name.startsWith("gif") ||
        name.startsWith("stf")
    ) {
        return true
    }
    if (name.startsWith("bridge") && !name.matches(Regex("en\\d+"))) {
        return true
    }
    val virtualTokens = listOf(
        "docker",
        "vbox",
        "vmnet",
        "vether",
        "hyper-v",
        "virtualbox",
        "vmware",
        "parallels",
        "virtual",
        "tun",
        "tap"
    )
    return virtualTokens.any { token -> name.contains(token) || display.contains(token) }
}

private fun desktopInterfaceTier(name: String): Int {
    val lowered = name.lowercase()
    return when {
        lowered.matches(Regex("en\\d+")) -> 0
        lowered.startsWith("eth") -> 0
        lowered.contains("ethernet") -> 0
        lowered.startsWith("wlan") -> 1
        lowered.contains("wi-fi") || lowered.contains("wifi") -> 1
        else -> 2
    }
}
