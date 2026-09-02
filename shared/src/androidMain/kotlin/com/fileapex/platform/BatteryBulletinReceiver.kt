package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest + dynamic receiver for [Intent.ACTION_BATTERY_LOW], [Intent.ACTION_POWER_CONNECTED],
 * and [Intent.ACTION_POWER_DISCONNECTED]. Does not listen to [Intent.ACTION_BATTERY_CHANGED].
 */
class BatteryBulletinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        when (action) {
            Intent.ACTION_BATTERY_LOW -> {
                val pending = goAsync()
                BatteryBulletinCoordinator.onBatteryLow(appContext) { pending.finish() }
            }
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_BATTERY_OKAY -> {
                val pending = goAsync()
                BatteryBulletinCoordinator.onCharging(appContext) { pending.finish() }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val pending = goAsync()
                BatteryBulletinCoordinator.onUnplugged(appContext) { pending.finish() }
            }
        }
    }
}
