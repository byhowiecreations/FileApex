package com.omninode.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * AlarmManager / boot heartbeat — restarts [FileShareServerService] only (never the UI).
 */
class OmniNodeWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        Log.i(TAG, "Watchdog received action=$action")

        if (!ServiceWatchdogScheduler.isWatchdogEnabled()) {
            ServiceWatchdogScheduler.cancel(appContext)
            return
        }

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            ServiceWatchdogScheduler.ACTION_SERVICE_WATCHDOG -> {
                maybeRestartShareServer(appContext)
                ServiceWatchdogScheduler.scheduleNext(appContext)
            }
        }
    }

    private fun maybeRestartShareServer(context: Context) {
        val serviceClass = "$FILE_SHARE_SERVER_SERVICE"
        if (ServiceWatchdogScheduler.isShareServerRunning(context, serviceClass)) {
            Log.i(TAG, "Share server already running — skip restart")
            return
        }
        runCatching {
            val start = Intent().setClassName(context.packageName, serviceClass)
            ContextCompat.startForegroundService(context, start)
            Log.i(TAG, "Started share-server FGS from watchdog")
        }.onFailure { error ->
            Log.w(TAG, "Watchdog could not start share server :: ${error.message}")
        }
    }

    companion object {
        private const val TAG = "OmniNodeWatchdog"
        private const val FILE_SHARE_SERVER_SERVICE = "com.omninode.network.FileShareServerService"
    }
}
