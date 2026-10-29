package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fileapex.platform.log.Log

/**
 * The AlarmManager watchdog tick. Not exported, so only this app's own alarm can fire it; the boot and unlock
 * broadcasts go to [FileApexWatchdogReceiver].
 */
class FileApexWatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ServiceWatchdogScheduler.ACTION_SERVICE_WATCHDOG) return
        val appContext = context.applicationContext
        Log.i(TAG, "Watchdog alarm")
        if (!ServiceWatchdogScheduler.isWatchdogEnabled(appContext)) {
            ServiceWatchdogScheduler.cancel(appContext)
            return
        }
        if (!FileApexAndroidBootstrap.ensureInitialized(appContext)) {
            Log.w(TAG, "Watchdog alarm skipped - process init not ready")
            ServiceWatchdogScheduler.scheduleNext(appContext)
            return
        }
        BatteryBulletinCoordinator.onProcessStart(appContext)
        ShareServerRestartCoordinator.attemptWatchdogRestart(
            appContext,
            ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
        )
        ServiceWatchdogScheduler.scheduleNext(appContext)
        ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(appContext)
    }

    private companion object {
        const val TAG = "FileApexWatchdogAlarm"
    }
}
