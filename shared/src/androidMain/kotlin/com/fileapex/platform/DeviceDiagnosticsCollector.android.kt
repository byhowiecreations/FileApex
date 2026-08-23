package com.fileapex.platform

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.view.Display
import kotlin.text.Charsets
import com.fileapex.data.settings.androidAppContextOrNull
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
import java.io.File
import kotlin.math.roundToInt

actual fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics {
    val context = androidAppContextOrNull()
    return runCatching {
        PeerDeviceDiagnostics(
            collectedAtEpochMs = 0L,
            platform = "",
            device = runCatching { readDeviceIdentity() }.getOrDefault(DeviceIdentityDiagnostics()),
            processor = runCatching { readProcessor() }.getOrDefault(ProcessorDiagnostics()),
            display = readDisplay(context),
            battery = runCatching { readBattery(context) }
                .getOrDefault(BatteryDiagnostics(chargingState = "Not available")),
            storage = runCatching { readStorage() }.getOrDefault(StorageDiagnostics()),
            network = readNetwork(context),
            uptime = runCatching { readUptime() }.getOrDefault(UptimeDiagnostics()),
            thermal = runCatching { readThermal(context) }.getOrDefault(ThermalDiagnostics()),
            memory = runCatching { readMemory(context) }.getOrDefault(MemoryDiagnostics())
        )
    }.getOrElse {
        PeerDeviceDiagnostics(
            collectedAtEpochMs = 0L,
            device = runCatching { readDeviceIdentity() }.getOrDefault(DeviceIdentityDiagnostics()),
            uptime = runCatching { readUptime() }.getOrDefault(UptimeDiagnostics())
        )
    }
}

private fun readDeviceIdentity(): DeviceIdentityDiagnostics {
    val release = Build.VERSION.RELEASE.trim().ifBlank { "Unknown" }
    val osBuild = buildString {
        append(release)
        append(" (API ")
        append(Build.VERSION.SDK_INT)
        append(')')
        val display = Build.DISPLAY.trim()
        if (display.isNotEmpty()) {
            append(" · ")
            append(display)
        }
    }
    return DeviceIdentityDiagnostics(
        make = Build.MANUFACTURER.trim().ifBlank { "Unknown" },
        model = Build.MODEL.trim().ifBlank { "Unknown" },
        kernelVersion = System.getProperty("os.version").orEmpty().ifBlank { "Unknown" },
        osBuildVersion = osBuild
    )
}

private fun readProcessor(): ProcessorDiagnostics {
    val totalCores = Runtime.getRuntime().availableProcessors().takeIf { it > 0 }
    val activeCores = readOnlineCpuCount() ?: totalCores
    val freqsMhz = readOnlineCpuFrequenciesMhz()
    val scaling = when {
        freqsMhz.isEmpty() -> "Unknown"
        freqsMhz.size == 1 -> "${freqsMhz.first()} MHz"
        else -> "${freqsMhz.min()}–${freqsMhz.max()} MHz"
    }
    val governor = readCpuGovernor()
    val frequencyScaling = if (governor.isNotBlank()) "$scaling ($governor)" else scaling
    return ProcessorDiagnostics(
        architecture = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "Unknown" },
        hardware = Build.HARDWARE.trim().ifBlank { Build.BOARD.trim() },
        activeCoreCount = activeCores,
        totalCoreCount = totalCores,
        frequencyScaling = frequencyScaling
    )
}

private fun readDisplay(context: Context?): DisplayDiagnostics {
    if (context == null) return DisplayDiagnostics()
    return runCatching {
        val appContext = context.applicationContext
        val (width, height) = readDisplayPixelSize(appContext) ?: return@runCatching DisplayDiagnostics()
        val refreshRate = readDisplayRefreshRate(appContext)
        val brightnessPercent = runCatching {
            val brightnessRaw = Settings.System.getInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                -1
            )
            brightnessRaw.takeIf { it in 0..255 }?.let {
                ((it * 100f) / 255f).roundToInt().coerceIn(0, 100)
            }
        }.getOrNull()
        DisplayDiagnostics(
            resolution = "$width x $height",
            refreshRateHz = refreshRate,
            brightnessPercent = brightnessPercent
        )
    }.getOrDefault(DisplayDiagnostics())
}

private fun readDisplayPixelSize(context: Context): Pair<Int, Int>? {
    return runCatching {
        readDisplayPixelSizeUnsafe(context)
    }.getOrNull()
}

private fun readDisplayPixelSizeUnsafe(context: Context): Pair<Int, Int>? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val bounds = windowManager?.currentWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return bounds.width() to bounds.height()
        }
    }
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val mode = display.mode
        if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
            return mode.physicalWidth to mode.physicalHeight
        }
    }
    val metrics = context.resources.displayMetrics
    return if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
        metrics.widthPixels to metrics.heightPixels
    } else {
        null
    }
}

private fun readDisplayRefreshRate(context: Context): Float? {
    return runCatching {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        display?.refreshRate?.takeIf { it.isFinite() && it > 0f }
    }.getOrNull()
}

private fun readBattery(context: Context?): BatteryDiagnostics {
    if (context == null) {
        return BatteryDiagnostics(chargingState = "Not available")
    }
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return BatteryDiagnostics(chargingState = "Unknown")
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) {
        ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    } else {
        null
    }
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
    val temperature = tempRaw.takeIf { it > 0 }?.let { it / 10.0 }
    val chargingState = when {
        status == BatteryManager.BATTERY_STATUS_FULL -> "Full"
        plugged == BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        plugged == BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
            status == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Discharging"
        else -> "Unknown"
    }
    return BatteryDiagnostics(
        levelPercent = percent,
        chargingState = chargingState,
        temperatureCelsius = temperature
    )
}

private fun readStorage(): StorageDiagnostics {
    return runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.blockSizeLong * stat.blockCountLong
        val available = stat.blockSizeLong * stat.availableBlocksLong
        StorageDiagnostics(
            usedBytes = (total - available).coerceAtLeast(0L),
            totalBytes = total
        )
    }.getOrDefault(StorageDiagnostics())
}

private fun readNetwork(context: Context?): NetworkDiagnostics {
    if (context == null) return NetworkDiagnostics()
    return runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching NetworkDiagnostics()
        val network = connectivity.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
            ?: return@runCatching NetworkDiagnostics(interfaceType = "Unknown")

        val interfaceType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Unknown"
        }

        when (interfaceType) {
            "Wi-Fi" -> readWifiNetwork(context, capabilities)
            "Cellular" -> readCellularNetwork(context)
            else -> NetworkDiagnostics(interfaceType = interfaceType)
        }
    }.getOrDefault(NetworkDiagnostics())
}

private fun readWifiNetwork(
    context: Context,
    capabilities: NetworkCapabilities
): NetworkDiagnostics {
    val appContext = context.applicationContext
    val wifiInfo = readWifiInfo(appContext, capabilities)
    val ssid = normalizeAndroidSsid(wifiInfo?.ssid)
        .ifBlank { readAndroidSsidFallback(appContext) }

    val frequency = wifiInfo?.frequency?.takeIf { it > 0 }
    return NetworkDiagnostics(
        interfaceType = "Wi-Fi",
        ssid = ssid,
        signalDbm = wifiInfo?.rssi?.takeIf { it != Int.MIN_VALUE && it != 0 },
        linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
        frequencyBand = frequencyBandLabel(frequency),
        channel = wifiChannel(frequency)
    )
}

private fun readWifiInfo(
    context: Context,
    capabilities: NetworkCapabilities
): android.net.wifi.WifiInfo? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !AndroidRuntimePermissions.hasNearbyWifiDevices(context)
    ) {
        return null
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        (capabilities.transportInfo as? android.net.wifi.WifiInfo)?.let { return it }
    }
    @Suppress("DEPRECATION")
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    @Suppress("DEPRECATION")
    return wifiManager?.connectionInfo
}

private fun readAndroidSsidFallback(context: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return ""
    }
    @Suppress("DEPRECATION")
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
    @Suppress("DEPRECATION")
    return normalizeAndroidSsid(wifiManager.connectionInfo?.ssid)
}

private fun readCellularNetwork(context: Context): NetworkDiagnostics {
    return runCatching {
        readCellularNetworkUnsafe(context)
    }.getOrDefault(NetworkDiagnostics(interfaceType = "Cellular"))
}

private fun readCellularNetworkUnsafe(context: Context): NetworkDiagnostics {
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        ?: return NetworkDiagnostics(interfaceType = "Cellular")

    val carrier = telephony.networkOperatorName.trim().ifBlank {
        telephony.simOperatorName.trim()
    }

    @Suppress("DEPRECATION")
    val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        telephony.dataNetworkType
    } else {
        telephony.networkType
    }
    val generation = cellularGenerationLabel(networkType)

    val signalDbm = readCellularSignalDbm(context, telephony)
    val (frequencyMhz, cellBand) = readCellularBandInfo(context, telephony)

    return NetworkDiagnostics(
        interfaceType = "Cellular",
        networkGeneration = generation,
        carrier = carrier,
        signalDbm = signalDbm,
        frequencyMhz = frequencyMhz,
        cellBand = cellBand
    )
}

private fun normalizeAndroidSsid(raw: String?): String {
    return raw
        ?.trim('"')
        ?.takeUnless { it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) }
        .orEmpty()
}

private fun readCellularSignalDbm(
    context: Context,
    telephony: android.telephony.TelephonyManager
): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
        != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    return runCatching {
        telephony.signalStrength?.cellSignalStrengths
            ?.mapNotNull { it.dbm.takeIf { dbm -> dbm != Int.MAX_VALUE && dbm != 0 } }
            ?.maxOrNull()
    }.getOrNull()
}

private fun readCellularBandInfo(
    context: Context,
    telephony: android.telephony.TelephonyManager
): Pair<Int?, String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
        return null to ""
    }
    if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
        != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return null to ""
    }
    return runCatching {
        @Suppress("DEPRECATION")
        val cellInfoList = telephony.allCellInfo.orEmpty()
        val serving = cellInfoList.firstOrNull { it.isRegistered } ?: cellInfoList.firstOrNull()
        when (val info = serving) {
            is android.telephony.CellInfoLte -> {
                val identity = info.cellIdentity as android.telephony.CellIdentityLte
                val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.bands.firstOrNull()?.let { lteBandLabel(it) }.orEmpty()
                } else {
                    ""
                }
                @Suppress("DEPRECATION")
                val freq = identity.earfcn.takeIf { it != Int.MAX_VALUE && it > 0 }
                Pair(freq, band)
            }
            is android.telephony.CellInfoNr -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val identity = info.cellIdentity as android.telephony.CellIdentityNr
                    val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        identity.bands.firstOrNull()?.let { nrBandLabel(it) }.orEmpty()
                    } else {
                        ""
                    }
                    val freq = identity.nrarfcn.takeIf { it != Int.MAX_VALUE && it > 0 }
                    Pair(freq, band)
                } else {
                    Pair(null, "")
                }
            }
            else -> Pair(null, "")
        }
    }.getOrDefault(null to "")
}

private fun cellularGenerationLabel(networkType: Int): String {
    return when (networkType) {
        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP,
        android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
        android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA,
        android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA,
        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        android.telephony.TelephonyManager.NETWORK_TYPE_EDGE,
        android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            networkType == android.telephony.TelephonyManager.NETWORK_TYPE_NR
        ) {
            "5G NR"
        } else {
            "Unknown"
        }
    }
}

private fun lteBandLabel(band: Int): String = "Band $band"

private fun nrBandLabel(band: Int): String = "n$band"

private fun readUptime(): UptimeDiagnostics {
    val uptimeMs = SystemClock.elapsedRealtime()
    val bootEpochMs = (System.currentTimeMillis() - uptimeMs).coerceAtLeast(0L)
    return UptimeDiagnostics(uptimeMs = uptimeMs, bootEpochMs = bootEpochMs)
}

private fun readThermal(context: Context?): ThermalDiagnostics {
    if (context == null) return ThermalDiagnostics(state = "Not available")
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
        when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> "Nominal"
            PowerManager.THERMAL_STATUS_MODERATE -> "Fair"
            PowerManager.THERMAL_STATUS_SEVERE -> "Serious"
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical"
            else -> "Unknown"
        }
    } else {
        "Not available"
    }
    val batteryTemp = readBattery(context).temperatureCelsius
    return ThermalDiagnostics(state = state, temperatureCelsius = batteryTemp)
}

private fun readMemory(context: Context?): MemoryDiagnostics {
    if (context == null) return MemoryDiagnostics()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return MemoryDiagnostics()
    val info = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(info)
    val total = info.totalMem.takeIf { it > 0L }
    val available = info.availMem.takeIf { it >= 0L }
    val used = if (total != null && available != null) (total - available).coerceAtLeast(0L) else null
    return MemoryDiagnostics(
        totalBytes = total,
        availableBytes = available,
        usedBytes = used
    )
}

private fun readOnlineCpuCount(): Int? {
    return runCatching {
        val online = File("/sys/devices/system/cpu/online").readText(Charsets.UTF_8).trim()
        parseCpuRangeCount(online)
    }.getOrNull()
}

private fun readOnlineCpuFrequenciesMhz(): List<Int> {
    val cpuDir = File("/sys/devices/system/cpu")
    if (!cpuDir.isDirectory) return emptyList()
    return cpuDir.listFiles()
        .orEmpty()
        .mapNotNull { file ->
            val name = file.name
            if (!name.startsWith("cpu")) return@mapNotNull null
            val index = name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
            readCpuFrequencyMhz(index)
        }
        .sorted()
}

private fun readCpuFrequencyMhz(cpuIndex: Int): Int? {
    val freqKhz = runCatching {
        File("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/scaling_cur_freq")
            .takeIf { it.canRead() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.toLongOrNull()
    }.getOrNull() ?: return null
    return (freqKhz / 1000L).toInt().takeIf { it > 0 }
}

private fun readCpuGovernor(): String {
    return runCatching {
        File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            .takeIf { it.canRead() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            .orEmpty()
    }.getOrDefault("")
}

private fun parseCpuRangeCount(spec: String): Int? {
    if (spec.isBlank()) return null
    var count = 0
    for (part in spec.split(',')) {
        val trimmed = part.trim()
        if (trimmed.contains('-')) {
            val bounds = trimmed.split('-', limit = 2)
            val start = bounds.getOrNull(0)?.toIntOrNull() ?: continue
            val end = bounds.getOrNull(1)?.toIntOrNull() ?: continue
            count += (end - start + 1).coerceAtLeast(0)
        } else {
            trimmed.toIntOrNull()?.let { count += 1 }
        }
    }
    return count.takeIf { it > 0 }
}

private fun frequencyBandLabel(frequencyMhz: Int?): String {
    if (frequencyMhz == null || frequencyMhz <= 0) return "Unknown"
    return when {
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5900 -> "5 GHz"
        frequencyMhz >= 5925 -> "6 GHz"
        else -> "Unknown"
    }
}

private fun wifiChannel(frequencyMhz: Int?): Int? {
    if (frequencyMhz == null || frequencyMhz <= 0) return null
    return when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5170..5825 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5925..7125 -> (frequencyMhz - 5950) / 5
        else -> null
    }
}
