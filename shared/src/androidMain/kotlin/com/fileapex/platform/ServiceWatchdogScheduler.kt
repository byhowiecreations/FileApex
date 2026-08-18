package com.fileapex.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils

/** AlarmManager FGS recovery for [FileApexWatchdogReceiver]; prefs use device-protected storage for direct boot. */
object ServiceWatchdogScheduler {
    private const val TAG = "ServiceWatchdogScheduler"
    const val ACTION_SERVICE_WATCHDOG = "com.fileapex.action.SERVICE_WATCHDOG"
    private const val REQUEST_CODE = 42_024
    private const val DIRECT_BOOT_PREFS = "fileapex_watchdog_direct_boot"
    private const val KEY_ENABLED_MIRROR = "watchdog_enabled"
    private const val KEY_AUTO_LAUNCH_MIRROR = "auto_launch_on_reboot"
    private const val KEY_EXACT_ALARM_WARNING = "exact_alarm_unavailable"
    private const val KEY_BATTERY_OPTIMIZATION_WARNING = "battery_optimization_active"
    private const val KEY_SHARE_SERVER_HEARTBEAT_EPOCH_MS = "share_server_heartbeat_epoch_ms"
    /** Disk flush cadence — in-memory skip until this age. 15 min stays inside the 25 min stale window. */
    private const val HEARTBEAT_WRITE_MIN_INTERVAL_MS = 15 * 60 * 1000L

    fun scheduleNext(context: Context) {
        scheduleAt(
            context,
            TimeUtils.nextAlarmEpochMs(TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS),
            TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS
        )
    }

    fun scheduleImmediate(context: Context) {
        scheduleAt(
            context,
            TimeUtils.immediateWatchdogAlarmEpochMs(),
            TimeUtils.SERVICE_WATCHDOG_IMMEDIATE_ALARM_DELAY_MS
        )
    }

    private fun scheduleAt(context: Context, triggerAt: Long, delayLabelMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)
        val useExact = canScheduleExactAlarms(alarmManager)
        setExactAlarmWarning(context, !useExact)
        runCatching {
            when {
                useExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                useExact -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                else -> {
                    Log.w(TAG, "Exact alarms unavailable - using inexact watchdog scheduling")
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            }
            Log.i(
                TAG,
                "Scheduled watchdog alarm at UTC=$triggerAt (+${delayLabelMs}ms, exact=$useExact)"
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
        val intent = Intent(context, FileApexWatchdogReceiver::class.java).apply {
            action = ACTION_SERVICE_WATCHDOG
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    fun recordShareServerHeartbeat(context: Context) {
        val prefs = directBootPrefs(context)
        val last = prefs.getLong(KEY_SHARE_SERVER_HEARTBEAT_EPOCH_MS, 0L)
        if (TimeUtils.isWithinWindow(last, HEARTBEAT_WRITE_MIN_INTERVAL_MS)) return
        prefs.edit()
            .putLong(KEY_SHARE_SERVER_HEARTBEAT_EPOCH_MS, TimeUtils.now())
            .commit()
    }

    fun clearShareServerHeartbeat(context: Context) {
        directBootPrefs(context).edit()
            .remove(KEY_SHARE_SERVER_HEARTBEAT_EPOCH_MS)
            .commit()
    }

    fun isShareServerRunning(context: Context): Boolean {
        val lastHeartbeat = directBootPrefs(context)
            .getLong(KEY_SHARE_SERVER_HEARTBEAT_EPOCH_MS, 0L)
        return TimeUtils.isWithinWindow(lastHeartbeat, TimeUtils.SHARE_SERVER_HEARTBEAT_STALE_MS)
    }

    /**
     * Boot-safe watchdog toggle — reads device-protected mirror only (no credential storage).
     */
    fun isWatchdogEnabled(context: Context): Boolean {
        return directBootPrefs(context).getBoolean(KEY_ENABLED_MIRROR, true)
    }

    /**
     * Boot-safe auto-launch toggle — reads device-protected mirror only (no credential storage).
     */
    fun isAutoLaunchOnRebootEnabled(context: Context): Boolean {
        return directBootPrefs(context).getBoolean(KEY_AUTO_LAUNCH_MIRROR, true)
    }

    fun isExactAlarmWarningActive(context: Context): Boolean {
        return directBootPrefs(context).getBoolean(KEY_EXACT_ALARM_WARNING, false)
    }

    /** Live exact-alarm capability; syncs the persisted warning flag for Settings / boot. */
    fun refreshExactAlarmAvailability(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val available = canScheduleExactAlarms(alarmManager)
        setExactAlarmWarning(context, !available)
        return available
    }

    fun isBatteryOptimizationWarningActive(context: Context): Boolean {
        return directBootPrefs(context).getBoolean(KEY_BATTERY_OPTIMIZATION_WARNING, false)
    }

    fun syncBatteryOptimizationWarning(context: Context, restricted: Boolean) {
        directBootPrefs(context).edit()
            .putBoolean(KEY_BATTERY_OPTIMIZATION_WARNING, restricted)
            .commit()
        if (restricted) {
            Log.w(TAG, "Battery optimization active - background server survival may be limited")
        }
    }

    /** Mirror settings into device-protected storage for [LOCKED_BOOT_COMPLETED]. */
    fun syncWatchdogEnabledFromSettings(context: Context) {
        val enabled = runCatching {
            FileApexServices.settings.enableServiceWatchdog.value
        }.getOrDefault(true)
        syncWatchdogEnabledMirror(context, enabled)
    }

    fun syncWatchdogEnabledMirror(context: Context, enabled: Boolean) {
        directBootPrefs(context).edit()
            .putBoolean(KEY_ENABLED_MIRROR, enabled)
            .commit()
    }

    /** Mirror settings into device-protected storage for [LOCKED_BOOT_COMPLETED]. */
    fun syncAutoLaunchOnRebootFromSettings(context: Context) {
        val enabled = runCatching {
            FileApexServices.settings.autoLaunchOnReboot.value
        }.getOrDefault(true)
        syncAutoLaunchOnRebootMirror(context, enabled)
    }

    fun syncAutoLaunchOnRebootMirror(context: Context, enabled: Boolean) {
        directBootPrefs(context).edit()
            .putBoolean(KEY_AUTO_LAUNCH_MIRROR, enabled)
            .commit()
    }

    fun contextOrNull(): Context? = androidAppContextOrNull()

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun setExactAlarmWarning(context: Context, active: Boolean) {
        directBootPrefs(context).edit()
            .putBoolean(KEY_EXACT_ALARM_WARNING, active)
            .commit()
    }

    private fun directBootPrefs(context: Context): android.content.SharedPreferences {
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DIRECT_BOOT_PREFS, Context.MODE_PRIVATE)
    }
}
