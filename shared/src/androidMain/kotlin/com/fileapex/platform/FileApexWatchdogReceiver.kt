package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager / boot heartbeat — restarts [FileShareServerService] only (never the UI).
 * [android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED] uses device-protected prefs only.
 */
class FileApexWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        Log.i(TAG, "Watchdog received action=$action")

        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED -> {
                if (!ServiceWatchdogScheduler.isAutoLaunchOnRebootEnabled(appContext)) {
                    Log.i(TAG, "Auto launch on reboot disabled — skipping boot restart")
                    return
                }
                ShareServerRestartCoordinator.attemptWatchdogRestart(
                    appContext,
                    ShareServerRestartCoordinator.RestartTrigger.BOOT_COMPLETED
                )
                if (ServiceWatchdogScheduler.isWatchdogEnabled(appContext)) {
                    ServiceWatchdogScheduler.scheduleNext(appContext)
                }
            }
            ServiceWatchdogScheduler.ACTION_SERVICE_WATCHDOG -> {
                if (!ServiceWatchdogScheduler.isWatchdogEnabled(appContext)) {
                    ServiceWatchdogScheduler.cancel(appContext)
                    return
                }
                ShareServerRestartCoordinator.attemptWatchdogRestart(
                    appContext,
                    ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
                )
                ServiceWatchdogScheduler.scheduleNext(appContext)
            }
        }
    }

    companion object {
        private const val TAG = "FileApexWatchdog"
    }
}
