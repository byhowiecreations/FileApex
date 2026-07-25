package com.fileapex.platform

import android.content.Context
import android.content.SharedPreferences
import com.fileapex.data.settings.androidAppContextOrNull

private const val PREFS_NAME = "fileapex_service_watchdog"
private const val KEY_CLEAN_STOP = "clean_stop"
private const val KEY_TIMEOUT_STOP = "timeout_stop"

object ServiceWatchdogState {
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markCleanStop(context: Context) {
        prefs(context).edit().putBoolean(KEY_CLEAN_STOP, true).commit()
    }

    fun consumeCleanStop(context: Context): Boolean {
        val prefs = prefs(context)
        val clean = prefs.getBoolean(KEY_CLEAN_STOP, false)
        if (clean) {
            prefs.edit().remove(KEY_CLEAN_STOP).commit()
        }
        return clean
    }

    /** FGS hit Android dataSync / connectedDevice runtime quota — defer watchdog restart. */
    fun markTimeoutStop(context: Context) {
        prefs(context).edit().putBoolean(KEY_TIMEOUT_STOP, true).commit()
    }

    fun consumeTimeoutStop(context: Context): Boolean {
        val prefs = prefs(context)
        val timedOut = prefs.getBoolean(KEY_TIMEOUT_STOP, false)
        if (timedOut) {
            prefs.edit().remove(KEY_TIMEOUT_STOP).commit()
        }
        return timedOut
    }
}

actual object ServiceWatchdog {
    actual fun markCleanStop() {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        ServiceWatchdogState.markCleanStop(context)
    }

    actual fun scheduleNextAlarmIfEnabled() {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        if (!ServiceWatchdogScheduler.isWatchdogEnabled(context)) return
        ServiceWatchdogScheduler.scheduleNext(context)
    }

    actual fun scheduleImmediateAlarmIfEnabled() {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        if (!ServiceWatchdogScheduler.isWatchdogEnabled(context)) return
        ServiceWatchdogScheduler.scheduleImmediate(context)
    }

    actual fun cancelAlarm() {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        ServiceWatchdogScheduler.cancel(context)
    }

    actual fun onPreferenceChanged(enabled: Boolean) {
        val context = androidAppContextOrNull() ?: return
        ServiceWatchdogScheduler.syncWatchdogEnabledMirror(context, enabled)
        if (enabled) {
            ServiceWatchdogScheduler.scheduleNext(context)
            ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(context)
        } else {
            ServiceWatchdogScheduler.cancel(context)
            ShareServerKeepAliveCoordinator.cancelJobIfNeeded(context)
        }
    }
}
