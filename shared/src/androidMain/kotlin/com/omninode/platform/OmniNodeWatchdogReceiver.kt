package com.omninode.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

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
                maybeRestartShareServer(appContext)
                ServiceWatchdogScheduler.scheduleNext(appContext)
            }
        }
    }

    private fun maybeRestartShareServer(context: Context) {
        if (ServiceWatchdogScheduler.isShareServerRunning(context)) {
            Log.i(TAG, "Share server heartbeat fresh — skip restart")
            // Heartbeat says FGS is up — drop a stale tap-to-restore nudge if any.
            ShareServerPendingStart.clear(context)
            return
        }
        runCatching {
            val start = Intent().setClassName(context.packageName, FILE_SHARE_SERVER_SERVICE)
            ContextCompat.startForegroundService(context, start)
            Log.i(TAG, "Started share-server FGS from watchdog")
        }.onFailure { error ->
            // Only nudge the user when the start itself is blocked; in-process promotion
            // failure is marked inside FileShareServerService.
            ShareServerPendingStart.mark(context)
            Log.i(
                TAG,
                "Watchdog FGS start blocked — deferred until foreground :: ${error.message}"
            )
        }
    }

    companion object {
        private const val TAG = "OmniNodeWatchdog"
        private const val FILE_SHARE_SERVER_SERVICE = "com.omninode.network.FileShareServerService"
    }
}
