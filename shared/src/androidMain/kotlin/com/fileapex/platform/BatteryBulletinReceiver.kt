package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest + dynamic receiver for [Intent.ACTION_BATTERY_LOW], [Intent.ACTION_POWER_CONNECTED],
 * and [Intent.ACTION_POWER_DISCONNECTED]. Does not listen to [Intent.ACTION_BATTERY_CHANGED].
 */
class BatteryBulletinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "received ${action.substringAfterLast('.')}")
        val appContext = context.applicationContext
        when (action) {
            Intent.ACTION_BATTERY_LOW -> {
                val pending = goAsync()
                BatteryBulletinCoordinator.onBatteryLow(appContext) { pending.finish() }
            }
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_BATTERY_OKAY -> {
                val pending = goAsync()
                if (action == Intent.ACTION_POWER_CONNECTED) {
                    ShareServerKeepAliveCoordinator.onPowerConnected(appContext)
                }
                BatteryBulletinCoordinator.onCharging(appContext) { pending.finish() }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val pending = goAsync()
                BatteryBulletinCoordinator.onUnplugged(appContext) { pending.finish() }
            }
        }
    }

    private companion object {
        private const val TAG = "BatteryBulletinReceiver"
    }
}
