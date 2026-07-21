package com.omninode.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager / boot heartbeat — restarts [FileShareServerService] only (never the UI).
 * [android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED] uses device-protected prefs only.
 */
class OmniNodeWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        Log.i(TAG, "Watchdog received action=$action")

        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            ServiceWatchdogScheduler.ACTION_SERVICE_WATCHDOG -> {
                if (!ServiceWatchdogScheduler.isWatchdogEnabled(appContext)) {
                    ServiceWatchdogScheduler.cancel(appContext)
                    return
                }
                val trigger = when (action) {
                    Intent.ACTION_LOCKED_BOOT_COMPLETED,
                    Intent.ACTION_BOOT_COMPLETED -> ShareServerRestartCoordinator.RestartTrigger.BOOT_COMPLETED
                    else -> ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
                }
                ShareServerRestartCoordinator.attemptWatchdogRestart(appContext, trigger)
                ServiceWatchdogScheduler.scheduleNext(appContext)
            }
        }
    }

    companion object {
        private const val TAG = "OmniNodeWatchdog"
    }
}
