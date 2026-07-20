package com.omninode.network

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.omninode.MainActivity
import com.omninode.R
import com.omninode.data.identity.LocalIdentity
import com.omninode.platform.ServiceWatchdog
import com.omninode.platform.ServiceWatchdogScheduler
import com.omninode.platform.ServiceWatchdogState
import com.omninode.platform.ShareServerPendingStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the LAN share server alive via [ServerLifecycleManager].
 *
 * UI starts pass [EXTRA_FROM_FOREGROUND] and promote immediately (persistent notification).
 * Watchdog / sticky restarts use a guarded path so Android 14/15 background FGS limits do not crash.
 *
 * UDP peer-wake listening lives only in this FGS — there is no separate process-level wake
 * service; peers cannot wake the device via UDP until this service (or a watchdog/UI restart)
 * is running again.
 */
class FileShareServerService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeReceiver: UdpWakeReceiver? = null
    private var engineWatchdogStarted = false
    private var isForegroundPromoted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForegroundPromoted) {
            val fromForeground = isForegroundStart(intent)
            val stickyRestart = intent == null
            val promoted = if (fromForeground) {
                promoteToForegroundFromUi()
            } else {
                promoteToForegroundSafely()
            }
            if (!promoted) {
                handlePromotionFailure(fromForeground = fromForeground, stickyRestart = stickyRestart)
                return START_NOT_STICKY
            }
            isForegroundPromoted = true
            ShareServerPendingStart.clear(this)
        }
        ensureServerRunning()
        recordServiceHeartbeat()
        if (wakeReceiver == null) {
            startWakeListener()
        }
        if (!engineWatchdogStarted) {
            engineWatchdogStarted = true
            startEngineWatchdog()
        }
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ServiceWatchdogScheduler.isWatchdogEnabled(this)) {
            Log.i(TAG, "Task removed — scheduling immediate watchdog recovery")
            ServiceWatchdog.scheduleImmediateAlarmIfEnabled()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val cleanStop = ServiceWatchdogState.consumeCleanStop(this)
        if (cleanStop || !ServiceWatchdogScheduler.isWatchdogEnabled(this)) {
            ServiceWatchdog.cancelAlarm()
            if (cleanStop) {
                ServiceWatchdogScheduler.clearShareServerHeartbeat(this)
            }
        } else {
            Log.i(TAG, "Unexpected FGS stop — scheduling immediate watchdog recovery")
            ServiceWatchdog.scheduleImmediateAlarmIfEnabled()
        }
        wakeReceiver?.stop()
        wakeReceiver = null
        serviceJob.cancel()
        ServerLifecycleManager.stop(androidLog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isForegroundStart(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) == true ||
            intent?.action == ACTION_START

    private fun handlePromotionFailure(fromForeground: Boolean, stickyRestart: Boolean) {
        when {
            stickyRestart -> {
                Log.i(TAG, "Sticky restart blocked — scheduling immediate watchdog recovery")
                ShareServerPendingStart.mark(this)
                ServiceWatchdog.scheduleImmediateAlarmIfEnabled()
            }
            fromForeground -> {
                Log.w(TAG, "Foreground promotion failed from UI — server not started")
            }
            else -> {
                Log.w(TAG, "Background FGS promotion blocked — deferred until app opens")
                ShareServerPendingStart.mark(this)
            }
        }
        stopSelf()
    }

    /** UI path — only catch known FGS policy exceptions; others propagate. */
    private fun promoteToForegroundFromUi(): Boolean {
        return try {
            promoteToForeground()
            true
        } catch (error: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "UI FGS not allowed :: ${error.message}")
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "UI FGS security denied :: ${error.message}")
            false
        }
    }

    private fun promoteToForeground() {
        val notification = buildServerNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeFlags())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Foreground service promoted with ongoing notification")
    }

    /** Guarded promotion for watchdog / boot / sticky restart — must not crash the process. */
    private fun promoteToForegroundSafely(): Boolean {
        return try {
            promoteToForeground()
            true
        } catch (error: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Background FGS not allowed :: ${error.message}")
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Background FGS security denied :: ${error.message}")
            false
        }
    }

    /**
     * API 34+: [CONNECTED_DEVICE] only (LAN peers) — avoids Android 15 dataSync daily quota.
     * API 29–33: [DATA_SYNC] only.
     */
    private fun foregroundServiceTypeFlags(): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
    }

    private fun buildServerNotification(): Notification {
        val contentIntent = openMainActivityPendingIntent()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OmniNode Server Active")
                .setContentText("Local WiFi secure ecosystem running...")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("OmniNode Server Active")
                .setContentText("Local WiFi secure ecosystem running...")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun openMainActivityPendingIntent(): PendingIntent {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, NOTIFICATION_CONTENT_REQUEST, launch, flags)
    }

    private fun startWakeListener() {
        if (wakeReceiver != null) return
        val receiver = UdpWakeReceiver(
            onWakeAccepted = {
                ServerLifecycleManager.ensureRunning(androidLog)
            },
            onLog = { message -> Log.i(TAG, message) }
        )
        wakeReceiver = receiver
        receiver.start()
    }

    private fun ensureServerRunning() {
        ServerLifecycleManager.ensureRunning(androidLog)
    }

    private fun startEngineWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(ENGINE_WATCHDOG_INTERVAL_MS)
                if (!ServerLifecycleManager.isRunning) {
                    Log.w(TAG, "Engine watchdog detected dead share server — restarting")
                    ensureServerRunning()
                }
                if (ServerLifecycleManager.isRunning) {
                    recordServiceHeartbeat()
                }
            }
        }
    }

    private fun recordServiceHeartbeat() {
        ServiceWatchdogScheduler.recordShareServerHeartbeat(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "OmniNode Background Server",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps OmniNode available for local WiFi file sharing"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        private const val TAG = "OmniNodeServerService"
        private const val CHANNEL_ID = "OmniNodeServerChannel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CONTENT_REQUEST = 1_101
        private const val ENGINE_WATCHDOG_INTERVAL_MS = 5_000L
        const val ACTION_START = "com.omninode.action.START_SHARE_SERVER"
        const val EXTRA_FROM_FOREGROUND = "extra_from_foreground"
        const val SERVER_PORT = LocalIdentity.DEFAULT_SHARE_PORT

        private val androidLog: (String, Throwable?) -> Unit = { message, error ->
            if (error != null) {
                Log.e(TAG, message, error)
            } else {
                Log.i(TAG, message)
            }
        }
    }
}
