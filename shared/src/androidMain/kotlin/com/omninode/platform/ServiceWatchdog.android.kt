package com.omninode.platform

import android.content.Context
import android.content.SharedPreferences
import com.omninode.data.settings.androidAppContextOrNull

private const val PREFS_NAME = "omninode_service_watchdog"
private const val KEY_CLEAN_STOP = "clean_stop"

object ServiceWatchdogState {
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markCleanStop(context: Context) {
        prefs(context).edit().putBoolean(KEY_CLEAN_STOP, true).apply()
    }

    fun consumeCleanStop(context: Context): Boolean {
        val prefs = prefs(context)
        val clean = prefs.getBoolean(KEY_CLEAN_STOP, false)
        if (clean) {
            prefs.edit().remove(KEY_CLEAN_STOP).apply()
        }
        return clean
    }
}

actual object ServiceWatchdog {
    actual fun markCleanStop() {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        ServiceWatchdogState.markCleanStop(context)
    }

    actual fun scheduleNextAlarmIfEnabled() {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        if (!ServiceWatchdogScheduler.isWatchdogEnabled()) return
        ServiceWatchdogScheduler.scheduleNext(context)
    }

    actual fun cancelAlarm() {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        ServiceWatchdogScheduler.cancel(context)
    }

    actual fun onPreferenceChanged(enabled: Boolean) {
        val context = androidAppContextOrNull() ?: return
        if (enabled) {
            ServiceWatchdogScheduler.scheduleNext(context)
        } else {
            ServiceWatchdogScheduler.cancel(context)
        }
    }
}
