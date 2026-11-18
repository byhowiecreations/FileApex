package com.fileapex.platform

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.fileapex.util.cancellableCatching

private const val LOW_BATTERY_PERCENT = 15

actual fun isTransferLowPowerMode(): Boolean {
    val context = androidApplicationContextOrNull() ?: return false
    return cancellableCatching("TransferPowerMode isTransferLowPowerMode") {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isPowerSaveMode == true) return@cancellableCatching true
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return@cancellableCatching false
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        level in 0..LOW_BATTERY_PERCENT && !battery.isCharging
    }.getOrDefault(false)
}
