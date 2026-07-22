package com.omninode.platform

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
import android.util.DisplayMetrics
import android.view.WindowManager
import com.omninode.data.settings.androidAppContextOrNull
import com.omninode.domain.diagnostics.BatteryDiagnostics
import com.omninode.domain.diagnostics.DeviceIdentityDiagnostics
import com.omninode.domain.diagnostics.DisplayDiagnostics
import com.omninode.domain.diagnostics.MemoryDiagnostics
import com.omninode.domain.diagnostics.NetworkDiagnostics
import com.omninode.domain.diagnostics.PeerDeviceDiagnostics
import com.omninode.domain.diagnostics.ProcessorDiagnostics
import com.omninode.domain.diagnostics.StorageDiagnostics
import com.omninode.domain.diagnostics.ThermalDiagnostics
import com.omninode.domain.diagnostics.UptimeDiagnostics
import java.io.File
import kotlin.math.roundToInt

actual fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics {
    val context = androidAppContextOrNull()
    return PeerDeviceDiagnostics(
        collectedAtEpochMs = 0L,
        platform = "",
        device = readDeviceIdentity(),
        processor = readProcessor(),
        display = readDisplay(context),
        battery = readBattery(context),
        storage = readStorage(),
        network = readNetwork(context),
        uptime = readUptime(),
        thermal = readThermal(context),
        memory = readMemory(context)
    )
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
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.refreshRate
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        }
        val brightnessRaw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            -1
        )
        val brightnessPercent = brightnessRaw.takeIf { it in 0..255 }?.let {
            ((it * 100f) / 255f).roundToInt().coerceIn(0, 100)
        }
        DisplayDiagnostics(
            resolution = "${metrics.widthPixels} x ${metrics.heightPixels}",
            refreshRateHz = refreshRate?.takeIf { it > 0f },
            brightnessPercent = brightnessPercent
        )
    }.getOrDefault(DisplayDiagnostics())
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
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetworkDiagnostics()
    val network = connectivity.activeNetwork
    val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        ?: return NetworkDiagnostics(interfaceType = "Unknown")

    val interfaceType = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        else -> "Unknown"
    }

    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return NetworkDiagnostics(interfaceType = interfaceType)
    }

    @Suppress("DEPRECATION")
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
        as? android.net.wifi.WifiManager
    @Suppress("DEPRECATION")
    val info = wifiManager?.connectionInfo ?: return NetworkDiagnostics(interfaceType = interfaceType)

    val ssid = info.ssid
        ?.trim('"')
        ?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
        .orEmpty()
    val frequency = info.frequency.takeIf { it > 0 }
    return NetworkDiagnostics(
        interfaceType = interfaceType,
        ssid = ssid,
        signalDbm = info.rssi.takeIf { it != Int.MIN_VALUE },
        linkSpeedMbps = info.linkSpeed.takeIf { it > 0 },
        frequencyBand = frequencyBandLabel(frequency),
        channel = wifiChannel(frequency)
    )
}

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
        val online = File("/sys/devices/system/cpu/online").readText().trim()
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
            ?.readText()
            ?.trim()
            ?.toLongOrNull()
    }.getOrNull() ?: return null
    return (freqKhz / 1000L).toInt().takeIf { it > 0 }
}

private fun readCpuGovernor(): String {
    return runCatching {
        File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            .takeIf { it.canRead() }
            ?.readText()
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
