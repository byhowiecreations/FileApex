package com.omninode.platform

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.omninode.data.settings.androidAppContextOrNull
import com.omninode.di.OmniNodeServices
import com.omninode.util.TimeUtils

/**
 * AlarmManager heartbeat for [OmniNodeWatchdogReceiver].
 * All trigger times route through [TimeUtils].
 */
object ServiceWatchdogScheduler {
    private const val TAG = "ServiceWatchdogScheduler"
    const val ACTION_SERVICE_WATCHDOG = "com.omninode.action.SERVICE_WATCHDOG"
    private const val REQUEST_CODE = 42_024

    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = TimeUtils.nextAlarmEpochMs()
        val pendingIntent = pendingIntent(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.i(
                TAG,
                "Scheduled watchdog alarm at UTC=$triggerAt " +
                    "(+${TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS}ms)"
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule watchdog alarm :: ${error.message}")
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarmManager.cancel(pendingIntent(context))
            Log.i(TAG, "Cancelled watchdog alarm")
        }.onFailure { error ->
            Log.w(TAG, "Failed to cancel watchdog alarm :: ${error.message}")
        }
    }

    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, OmniNodeWatchdogReceiver::class.java).apply {
            action = ACTION_SERVICE_WATCHDOG
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    fun isShareServerRunning(context: Context, serviceClassName: String): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == serviceClassName
        }
    }

    fun isWatchdogEnabled(): Boolean =
        runCatching { OmniNodeServices.settings.enableServiceWatchdog.value }
            .getOrDefault(true)

    fun contextOrNull(): Context? = androidAppContextOrNull()
}
