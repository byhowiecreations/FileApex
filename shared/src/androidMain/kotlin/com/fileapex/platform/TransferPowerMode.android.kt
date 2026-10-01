package com.fileapex.platform

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

private const val LOW_BATTERY_PERCENT = 15

actual fun isTransferLowPowerMode(): Boolean {
    val context = androidApplicationContextOrNull() ?: return false
    return runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isPowerSaveMode == true) return@runCatching true
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return@runCatching false
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        level in 0..LOW_BATTERY_PERCENT && !battery.isCharging
    }.getOrDefault(false)
}
