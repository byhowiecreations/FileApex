package com.fileapex.platform

import com.fileapex.domain.diagnostics.DeviceIdentityDiagnostics
import com.fileapex.domain.diagnostics.GpuDiagnostics
import com.fileapex.domain.diagnostics.NetworkDiagnostics
import com.fileapex.domain.diagnostics.ProcessorDiagnostics
import com.fileapex.presentation.DeviceHardwareProfile
import kotlin.math.roundToInt

/**
 * What the device details say about a Windows PC. Windows keeps these facts in the registry and `netsh`, not in the
 * places the Mac and Linux readers use, so nothing here is shared with them.
 */
internal object WindowsDiagnostics {
    private const val VERSION_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"
    private const val BIOS_KEY = "HARDWARE\\DESCRIPTION\\System\\BIOS"
    private const val CPU_KEY = "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0"
    private const val DISPLAY_CLASS_KEY = "SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}"
    private const val WINDOWS_11_FIRST_BUILD = 22000
    private const val MAX_ADAPTER_SLOTS = 32

    /** Strings boards ship when the maker never filled them in, which is the usual case for a PC built from parts. */
    private val FIRMWARE_PLACEHOLDERS = listOf(
        "system manufacturer", "system product name", "to be filled", "default string",
        "o.e.m.", "not applicable", "not specified", "type1productconfigid"
    )

    /** Adapters that are not a graphics card: Windows' fallback renderer, remote sessions and virtual displays. */
    private val NOT_A_CARD = listOf("microsoft", "virtual", "remote display")

    /** "Windows 11 Pro": the registry product name, corrected to 11 from build 22000 where it still says 10. */
    fun platformName(registry: WindowsRegistryReader, osName: String): String {
        val buildNumber = registry.readLocalMachineString(VERSION_KEY, "CurrentBuildNumber")?.toIntOrNull()
        val product = registry.readLocalMachineString(VERSION_KEY, "ProductName") ?: return "Windows"
        return if (buildNumber != null && buildNumber >= WINDOWS_11_FIRST_BUILD) product.replace("Windows 10", "Windows 11") else product
    }

    fun identity(registry: WindowsRegistryReader, osName: String, osVersion: String): DeviceIdentityDiagnostics {
        val build = registry.readLocalMachineString(VERSION_KEY, "CurrentBuildNumber")
        val revision = registry.readLocalMachineNumber(VERSION_KEY, "UBR")
        val release = registry.readLocalMachineString(VERSION_KEY, "DisplayVersion")
            ?: registry.readLocalMachineString(VERSION_KEY, "ReleaseId")
        val buildText = build?.let { if (revision != null) "$it.$revision" else it }
        val osBuild = if (release == null && buildText == null) {
            listOf(osName, osVersion).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown" }
        } else {
            listOfNotNull(release, buildText?.let { "(build $it)" }).joinToString(" ")
        }
        return DeviceIdentityDiagnostics(
            make = firmwareName(registry, "SystemManufacturer"),
            model = firmwareName(registry, "SystemProductName"),
            kernelVersion = if (build != null) "${osVersion.ifBlank { "10.0" }}.$build" else osVersion.ifBlank { "Unknown" },
            osBuildVersion = osBuild
        )
    }

    /** The device card's make and model: the board's firmware names, empty when the maker never filled them in. */
    fun hardwareProfile(registry: WindowsRegistryReader, platform: String): DeviceHardwareProfile = DeviceHardwareProfile(
        os = "windows",
        platform = platform,
        deviceMake = firmwareName(registry, "SystemManufacturer"),
        deviceModel = firmwareName(registry, "SystemProductName")
    )

    private fun firmwareName(registry: WindowsRegistryReader, name: String): String {
        val value = registry.readLocalMachineString(BIOS_KEY, name)?.trim().orEmpty()
        return if (FIRMWARE_PLACEHOLDERS.any { value.contains(it, ignoreCase = true) }) "" else value
    }

    /** [jvmView] carries the core count and the load the JVM reports; the registry adds the CPU's name and base clock. */
    fun processor(registry: WindowsRegistryReader, jvmView: ProcessorDiagnostics): ProcessorDiagnostics {
        val name = registry.readLocalMachineString(CPU_KEY, "ProcessorNameString").orEmpty().trim()
        val mhz = registry.readLocalMachineNumber(CPU_KEY, "~MHz")?.takeIf { it > 0 }
        val clock = mhz?.let { String.format(java.util.Locale.ROOT, "%.2f GHz", it / 1000.0) }
        return jvmView.copy(
            hardware = name.ifBlank { jvmView.hardware },
            frequencyScaling = listOfNotNull(clock, jvmView.frequencyScaling.takeIf { it.isNotBlank() }).joinToString(" · ")
        )
    }

    /** Every display adapter Windows lists, with its dedicated video memory; the 64-bit value is the one that is right above 4 GB. */
    fun gpus(registry: WindowsRegistryReader): List<GpuDiagnostics> =
        (0 until MAX_ADAPTER_SLOTS).mapNotNull { slot ->
            val key = "$DISPLAY_CLASS_KEY\\${slot.toString().padStart(4, '0')}"
            val name = registry.readLocalMachineString(key, "DriverDesc") ?: return@mapNotNull null
            if (NOT_A_CARD.any { name.contains(it, ignoreCase = true) }) return@mapNotNull null
            val memory = registry.readLocalMachineNumber(key, "HardwareInformation.qwMemorySize")
                ?: registry.readLocalMachineNumber(key, "HardwareInformation.MemorySize")
            GpuDiagnostics(name, memory?.takeIf { it > 0 })
        }

    fun wifi(): NetworkDiagnostics {
        val output = readCommandOutput("netsh", "wlan", "show", "interfaces").orEmpty()
        if (output.isBlank()) return NetworkDiagnostics(interfaceType = "Wi-Fi")
        val values = parseKeyColonValueBlock(output.replace("\r\n", "\n"))
        val state = values["State"].orEmpty()
        if (!state.contains("connected", ignoreCase = true)) return NetworkDiagnostics(interfaceType = "Wi-Fi")
        val ssid = values["SSID"].orEmpty()
        val signalPercent = values["Signal"]?.removeSuffix("%")?.trim()?.toIntOrNull()
        val channel = values["Channel"]?.toIntOrNull()
        val radioType = values["Radio type"].orEmpty()
        val band = when {
            radioType.contains("802.11ax", ignoreCase = true) && channel != null && channel >= 1 ->
                if (channel <= 14) "2.4 GHz" else if (channel >= 36) "5 GHz" else "6 GHz"
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
            signalDbm = signalPercent?.let { percentToDbm(it) },
            linkSpeedMbps = receiveRate,
            frequencyBand = band,
            channel = channel
        )
    }

    private fun parseKeyColonValueBlock(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val key = line.substring(0, colon).trim()
                if (key.isBlank()) null else key to line.substring(colon + 1).trim()
            }
            .toMap()

    private fun percentToDbm(percent: Int): Int = -100 + (percent.coerceIn(0, 100) * 0.7).roundToInt()
}
