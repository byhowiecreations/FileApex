package com.fileapex.platform

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * SSOT for OEM-resistant share-server keep-alive: freeze-guard receivers,
 * JobScheduler fallback, and foreground re-assertion on network resume.
 *
 * Timer responsibilities (do not conflate):
 * - **10 min** — [com.fileapex.cloud.CloudPresenceHeartbeat] Firestore cloud presence only.
 * - **20 min** — [ServiceWatchdogScheduler] AlarmManager FGS recovery ([TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS]).
 *
 * There is no in-process polling loop; recovery is event-driven (alarms, FCM, network, freeze-guard).
 */
object ShareServerKeepAliveCoordinator {
    private const val TAG = "ShareServerKeepAlive"
    private const val FILE_SHARE_SERVER_SERVICE = "com.fileapex.network.FileShareServerService"
    const val ACTION_REASSERT = "com.fileapex.action.REASSERT_SHARE_SERVER"
    private const val JOB_ID = 42_025
    private const val JOB_INTERVAL_MS = 15 * 60 * 1000L

    @Volatile
    private var freezeGuardRegistered = false

    @Volatile
    private var freezeGuardReceiver: ShareServerFreezeGuardReceiver? = null

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun onForegroundServiceActive(context: Context) {
        val appContext = context.applicationContext
        registerFreezeGuard(appContext)
        registerNetworkCallback(appContext)
        scheduleJobIfNeeded(appContext)
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
    }

    fun onForegroundServiceInactive(context: Context) {
        val appContext = context.applicationContext
        unregisterFreezeGuard(appContext)
        unregisterNetworkCallback(appContext)
        cancelJob(appContext)
    }

    /**
     * Called from freeze-guard receivers, JobScheduler, and network transitions to restore
     * foreground promotion or restart the FGS when the heartbeat is stale.
     */
    fun reassertOrRestart(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!ServiceWatchdogScheduler.isWatchdogEnabled(appContext)) {
            Log.i(TAG, "Keep-alive skipped — watchdog disabled ($reason)")
            return
        }
        if (ShareServerPendingStart.isPending(appContext)) {
            Log.i(TAG, "Pending foreground start — attempting watchdog restart ($reason)")
            ShareServerRestartCoordinator.attemptWatchdogRestart(
                appContext,
                ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
            )
            return
        }
        if (!ServiceWatchdogScheduler.isShareServerRunning(appContext)) {
            Log.i(TAG, "Stale share-server heartbeat — attempting watchdog restart ($reason)")
            ShareServerRestartCoordinator.attemptWatchdogRestart(
                appContext,
                ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
            )
            return
        }
        reassertForegroundService(appContext, reason)
    }

    fun scheduleJobIfNeeded(context: Context) {
        if (!ServiceWatchdogScheduler.isWatchdogEnabled(context)) return
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val component = ComponentName(context, ShareServerKeepAliveJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setPeriodic(JOB_INTERVAL_MS, JOB_INTERVAL_MS)
        } else {
            @Suppress("DEPRECATION")
            builder.setPeriodic(JOB_INTERVAL_MS)
        }
        runCatching {
            scheduler.schedule(builder.build())
            Log.i(TAG, "Scheduled keep-alive JobScheduler heartbeat (${JOB_INTERVAL_MS}ms)")
        }.onFailure { error ->
            Log.w(TAG, "JobScheduler schedule failed :: ${error.message}")
        }
    }

    fun cancelJobIfNeeded(context: Context) {
        cancelJob(context)
    }

    private fun cancelJob(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        runCatching {
            scheduler.cancel(JOB_ID)
            Log.i(TAG, "Cancelled keep-alive JobScheduler heartbeat")
        }.onFailure { error ->
            Log.w(TAG, "JobScheduler cancel failed :: ${error.message}")
        }
    }

    private fun registerFreezeGuard(context: Context) {
        if (freezeGuardRegistered) return
        val receiver = ShareServerFreezeGuardReceiver()
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                ShareServerFreezeGuardReceiver.intentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            freezeGuardReceiver = receiver
            freezeGuardRegistered = true
            Log.i(TAG, "Registered freeze-guard receiver")
        }.onFailure { error ->
            Log.w(TAG, "Freeze-guard registration failed :: ${error.message}")
        }
    }

    private fun unregisterFreezeGuard(context: Context) {
        val receiver = freezeGuardReceiver ?: return
        runCatching {
            context.unregisterReceiver(receiver)
            Log.i(TAG, "Unregistered freeze-guard receiver")
        }.onFailure { error ->
            Log.w(TAG, "Freeze-guard unregister failed :: ${error.message}")
        }
        freezeGuardReceiver = null
        freezeGuardRegistered = false
    }

    private fun registerNetworkCallback(context: Context) {
        if (networkCallback != null) return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkEvent("available")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                ) {
                    onNetworkEvent("capabilities")
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivity.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "Registered keep-alive network callback")
        }.onFailure { error ->
            Log.w(TAG, "Network callback registration failed :: ${error.message}")
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        val callback = networkCallback ?: return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            connectivity.unregisterNetworkCallback(callback)
            Log.i(TAG, "Unregistered keep-alive network callback")
        }.onFailure { error ->
            Log.w(TAG, "Network callback unregister failed :: ${error.message}")
        }
        networkCallback = null
    }

    private fun onNetworkEvent(event: String) {
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        reassertOrRestart(context, reason = "network:$event")
    }

    private fun reassertForegroundService(context: Context, reason: String) {
        runCatching {
            val intent = Intent().setClassName(context.packageName, FILE_SHARE_SERVER_SERVICE).apply {
                action = ACTION_REASSERT
            }
            ContextCompat.startForegroundService(context, intent)
            Log.i(TAG, "Dispatched foreground re-assert ($reason)")
        }.onFailure { error ->
            Log.w(TAG, "Foreground re-assert failed ($reason) :: ${error.message}")
            ShareServerRestartCoordinator.attemptWatchdogRestart(
                context,
                ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
            )
        }
    }
}
