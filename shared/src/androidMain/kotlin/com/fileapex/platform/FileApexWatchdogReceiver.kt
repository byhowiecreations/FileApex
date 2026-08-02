package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager / boot heartbeat — restarts [FileShareServerService] only (never the UI).
 *
 * Listens for [Intent.ACTION_USER_UNLOCKED] and [Intent.ACTION_BOOT_COMPLETED] after credential
 * storage is available. We intentionally do **not** register for
 * [Intent.ACTION_LOCKED_BOOT_COMPLETED]: that broadcast spins up an empty process (Application
 * defers init, receiver skips work), which the system kills before the unlocked boot broadcast
 * queue reaches this app — so auto-launch never runs.
 */
class FileApexWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        Log.i(TAG, "Watchdog received action=$action")

        when (action) {
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_BOOT_COMPLETED -> handleBootAutoLaunch(appContext)
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

    private fun handleBootAutoLaunch(appContext: Context) {
        if (!isUserStorageUnlocked(appContext)) {
            Log.i(TAG, "Storage still locked — skip restart")
            return
        }
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
            ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(appContext)
        }
    }

    companion object {
        private const val TAG = "FileApexWatchdog"
    }
}
