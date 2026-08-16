package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest receiver for [Intent.ACTION_BATTERY_LOW] (background) and dynamic registration for
 * charging / battery-level transitions while the process is alive.
 */
class BatteryBulletinReceiver(
    private val forDynamicRegistration: Boolean = false
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        when (action) {
            Intent.ACTION_BATTERY_LOW -> {
                if (forDynamicRegistration) return
                val pending = goAsync()
                BatteryBulletinCoordinator.onBatteryLow(appContext) { pending.finish() }
            }
            Intent.ACTION_POWER_CONNECTED -> {
                val pending = goAsync()
                BatteryBulletinCoordinator.onCharging(appContext) { pending.finish() }
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                if (!forDynamicRegistration) return
                BatteryBulletinCoordinator.onBatteryChanged(appContext, intent)
            }
        }
    }
}
