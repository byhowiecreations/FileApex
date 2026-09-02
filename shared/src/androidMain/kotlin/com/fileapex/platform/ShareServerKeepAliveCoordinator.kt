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
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * OEM-resistant share-server keep-alive: freeze-guard receivers,
 * JobScheduler fallback, and foreground re-assertion on network resume.
 *
 * Do not conflate timers:
 * - 10 min: [com.fileapex.cloud.CloudPresenceHeartbeat] Firestore cloud presence only.
 * - 20 min: [ServiceWatchdogScheduler] AlarmManager FGS recovery ([TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS]).
 * - Battery job: unscheduled above 25%; 30/20/10 min step-down from 25% to the 15% alert.
 *
 * No in-process polling loop; recovery is event-driven (alarms, FCM, network, freeze-guard).
 */
object ShareServerKeepAliveCoordinator {
    private const val TAG = "ShareServerKeepAlive"
    private const val FILE_SHARE_SERVER_SERVICE = "com.fileapex.network.FileShareServerService"
    const val ACTION_REASSERT = "com.fileapex.action.REASSERT_SHARE_SERVER"
    private const val JOB_ID = 42_025
    private const val NETWORK_REASSERT_DEBOUNCE_MS = 60_000L

    @Volatile
    private var lastNetworkReassertAtMs = 0L

    @Volatile
    private var freezeGuardRegistered = false

    @Volatile
    private var freezeGuardReceiver: ShareServerFreezeGuardReceiver? = null

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun onForegroundServiceActive(context: Context) {
        val appContext = context.applicationContext
        registerNetworkCallback(appContext)
        scheduleJobIfNeeded(appContext)
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
    }

    fun onForegroundServiceInactive(context: Context, retainRecoveryJob: Boolean = false) {
        val appContext = context.applicationContext
        unregisterNetworkCallback(appContext)
        // Battery backstop must survive a clean FGS stop — Motorola withholds BATTERY_LOW
        // from stopped apps, so this job is the wake that can still read BatteryManager.
        scheduleJobIfNeeded(appContext)
        if (!retainRecoveryJob) {
            Log.i(TAG, "FGS inactive - keep-alive job retained for battery bulletin backstop")
        }
    }

    /** Register freeze-guard at app launch — survives FGS death for OEM recovery. */
    fun registerFreezeGuardIfNeeded(context: Context) {
        registerFreezeGuard(context.applicationContext)
    }

    fun unregisterFreezeGuardIfNeeded(context: Context) {
        unregisterFreezeGuard(context.applicationContext)
    }

    /**
     * Called from freeze-guard receivers, JobScheduler, and network transitions to restore
     * foreground promotion or restart the FGS when the heartbeat is stale.
     */
    fun reassertOrRestart(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!ServiceWatchdogScheduler.isWatchdogEnabled(appContext)) {
            Log.i(TAG, "Keep-alive skipped - watchdog disabled ($reason)")
            return
        }
        if (ShareServerPendingStart.isPending(appContext)) {
            Log.i(TAG, "Pending foreground start - attempting watchdog restart ($reason)")
            ShareServerRestartCoordinator.attemptWatchdogRestart(
                appContext,
                restartTriggerForReason(reason)
            )
            return
        }
        if (!ServiceWatchdogScheduler.isShareServerRunning(appContext)) {
            Log.i(TAG, "Stale share-server heartbeat - attempting watchdog restart ($reason)")
            ShareServerRestartCoordinator.attemptWatchdogRestart(
                appContext,
                restartTriggerForReason(reason)
            )
            return
        }
        reassertForegroundService(appContext, reason)
    }

    private fun restartTriggerForReason(reason: String): ShareServerRestartCoordinator.RestartTrigger {
        return if (reason.startsWith("freeze_guard:")) {
            ShareServerRestartCoordinator.RestartTrigger.SCREEN_WAKE
        } else {
            ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
        }
    }

    fun scheduleJobIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val snapshot = BatteryBulletinCoordinator.currentSnapshot(appContext)
        val intervalMs = BatteryBulletinPolicy.jobIntervalMs(
            levelPercent = snapshot.levelPercent,
            charging = snapshot.charging,
            alreadyAlertedThisCycle = BatteryBulletinCoordinator.isAlertedThisCycle(appContext)
        )
        val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (intervalMs == null) {
            if (scheduler.getPendingJob(JOB_ID) != null) {
                cancelJob(appContext)
            }
            return
        }
        val pending = scheduler.getPendingJob(JOB_ID)
        if (pending != null &&
            !pending.isPeriodic &&
            pending.minLatencyMillis <= intervalMs
        ) {
            return
        }
        val component = ComponentName(appContext, ShareServerKeepAliveJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, component)
            .setPersisted(true)
            .setMinimumLatency(intervalMs)
            .setOverrideDeadline(intervalMs + intervalMs / 2)
        runCatching {
            scheduler.schedule(builder.build())
            Log.i(
                TAG,
                "Scheduled battery backstop job in ${intervalMs}ms " +
                    "(level=${snapshot.levelPercent ?: "?"})"
            )
        }.onFailure { error ->
            Log.w(TAG, "JobScheduler schedule failed :: ${error.message}")
        }
    }

    fun cancelJobIfNeeded(context: Context) {
        // Watchdog-off: keep a job only while the battery step-down is active.
        scheduleJobIfNeeded(context)
    }

    private fun cancelJob(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        runCatching {
            scheduler.cancel(JOB_ID)
            Log.i(TAG, "Cancelled battery backstop job (above 25% or already alerted)")
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
                ContextCompat.RECEIVER_EXPORTED
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
        val now = com.fileapex.util.TimeUtils.now()
        if (event != "available" &&
            lastNetworkReassertAtMs > 0L &&
            now - lastNetworkReassertAtMs < NETWORK_REASSERT_DEBOUNCE_MS
        ) {
            return
        }
        lastNetworkReassertAtMs = now
        val context = ServiceWatchdogScheduler.contextOrNull() ?: return
        reassertOrRestart(context, reason = "network:$event")
        if (com.fileapex.di.FileApexServices.isDatabaseReady()) {
            com.fileapex.di.FileApexServices.presenceMonitor.refreshPeersOnForeground()
            com.fileapex.di.FileApexServices.transferQueue.scheduleDrain()
        }
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
