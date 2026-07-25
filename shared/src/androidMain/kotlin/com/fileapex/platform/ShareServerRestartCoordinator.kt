package com.fileapex.platform

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Single source of truth for share-server FGS restart and deferred recovery.
 * Used by [FileApexWatchdogReceiver], [FileShareServerService], and [MainActivity].
 */
object ShareServerRestartCoordinator {
    private const val TAG = "ShareServerRestart"
    private const val FILE_SHARE_SERVER_SERVICE = "com.fileapex.network.FileShareServerService"

    enum class RestartTrigger {
        WATCHDOG_ALARM,
        BOOT_COMPLETED,
        STICKY_RESTART,
        UI_FOREGROUND
    }

    /** True when the OS has restricted background work for this app (API 28+). */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.isBackgroundRestricted
    }

    /**
     * True when the app process is visible enough that background [ContextCompat.startForegroundService]
     * is likely to succeed on API 31+. Alarm/boot paths may still attempt when false.
     */
    fun isProcessEligibleForBackgroundFgs(context: Context): Boolean {
        if (isBackgroundRestricted(context)) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcess = activityManager.runningAppProcesses
            ?.firstOrNull { it.processName == context.packageName }
            ?: return false
        return appProcess.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /**
     * Queue recovery when FGS start or [android.app.Service.startForeground] is blocked.
     * Clears stale heartbeat, marks pending, posts tap-to-restore, and schedules a near-term retry.
     */
    fun deferUntilForeground(context: Context, reason: String) {
        val appContext = context.applicationContext
        Log.i(TAG, "Deferring share-server restart until foreground :: $reason")
        ServiceWatchdogScheduler.clearShareServerHeartbeat(appContext)
        ShareServerPendingStart.mark(appContext)
        ServiceWatchdog.scheduleImmediateAlarmIfEnabled()
    }

    /**
     * Watchdog / boot path — restart [FileShareServerService] or defer without crashing the process.
     */
    fun attemptWatchdogRestart(context: Context, trigger: RestartTrigger) {
        val appContext = context.applicationContext
        if (ServiceWatchdogScheduler.isShareServerRunning(appContext)) {
            Log.i(TAG, "Share server heartbeat fresh — skip restart ($trigger)")
            ShareServerPendingStart.clear(appContext)
            return
        }

        if (shouldDeferBeforeStart(appContext, trigger)) {
            deferUntilForeground(appContext, "preflight_blocked:$trigger")
            return
        }

        runCatching {
            val start = Intent().setClassName(appContext.packageName, FILE_SHARE_SERVER_SERVICE)
            ContextCompat.startForegroundService(appContext, start)
            Log.i(TAG, "Started share-server FGS from $trigger")
        }.onFailure { error ->
            val detail = when (error) {
                is ForegroundServiceStartNotAllowedException -> "fgs_not_allowed"
                else -> error.message ?: error.javaClass.simpleName
            }
            deferUntilForeground(appContext, "start_failed:$trigger:$detail")
        }
    }

    /** Called from [FileShareServerService] when [android.app.Service.startForeground] is blocked. */
    fun onForegroundPromotionBlocked(context: Context, trigger: RestartTrigger) {
        deferUntilForeground(context, "promotion_blocked:$trigger")
    }

    private fun shouldDeferBeforeStart(context: Context, trigger: RestartTrigger): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return when (trigger) {
            RestartTrigger.BOOT_COMPLETED,
            RestartTrigger.STICKY_RESTART -> false
            RestartTrigger.WATCHDOG_ALARM,
            RestartTrigger.UI_FOREGROUND -> !isProcessEligibleForBackgroundFgs(context)
        }
    }
}
