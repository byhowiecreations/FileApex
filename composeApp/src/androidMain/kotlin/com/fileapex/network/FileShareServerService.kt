package com.fileapex.network

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fileapex.MainActivity
import com.fileapex.R
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.presence.PresenceBackgroundWake
import com.fileapex.platform.ServiceWatchdog
import com.fileapex.platform.ServiceWatchdogScheduler
import com.fileapex.platform.ServiceWatchdogState
import com.fileapex.platform.ShareServerKeepAliveCoordinator
import com.fileapex.platform.ShareServerPendingStart
import com.fileapex.platform.ShareServerRestartCoordinator

/**
 * Foreground service that keeps the LAN share server alive via [ServerLifecycleManager].
 *
 * UI starts pass [EXTRA_FROM_FOREGROUND] and promote immediately (persistent notification).
 * Watchdog / sticky restarts use a guarded path so Android 14/15 background FGS limits do not crash.
 *
 * Background recovery uses the **20-minute** AlarmManager watchdog ([ServiceWatchdogScheduler] /
 * [com.fileapex.util.TimeUtils.SERVICE_WATCHDOG_ALARM_INTERVAL_MS]) — not an in-process poll loop.
 * Cloud peer visibility uses the separate **10-minute** Firestore heartbeat
 * ([com.fileapex.cloud.CloudPresenceHeartbeat]).
 *
 * UDP peer-wake listening lives only in this FGS — there is no separate process-level wake
 * service; peers cannot wake the device via UDP until this service (or a watchdog/UI restart)
 * is running again.
 */
class FileShareServerService : Service() {
    private var wakeReceiver: UdpWakeReceiver? = null
    private var isForegroundPromoted = false

    override fun onCreate() {
        super.onCreate()
        ensureServerNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromForeground = isForegroundStart(intent)
        val reassert = intent?.action == ShareServerKeepAliveCoordinator.ACTION_REASSERT
        val stickyRestart = intent == null
        if (reassert && isForegroundPromoted) {
            runCatching { promoteToForeground() }
                .onFailure { error ->
                    Log.w(TAG, "Foreground re-assert refresh failed :: ${error.message}")
                }
            ensureServerRunning()
            recordServiceHeartbeat()
            ServiceWatchdog.scheduleNextAlarmIfEnabled()
            return START_STICKY
        }
        if (!isForegroundPromoted) {
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
            ShareServerKeepAliveCoordinator.onForegroundServiceActive(this)
        } else if (fromForeground) {
            // Re-post after POST_NOTIFICATIONS grant (or UI reopen). First promote may have
            // succeeded while notifications were still denied, leaving no visible ongoing alert.
            runCatching { promoteToForeground() }
                .onFailure { error ->
                    Log.w(TAG, "Foreground notification refresh failed :: ${error.message}")
                }
            ShareServerPendingStart.clear(this)
        }
        ensureServerRunning()
        recordServiceHeartbeat()
        if (wakeReceiver == null) {
            startWakeListener()
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

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(
            TAG,
            "FGS runtime quota exceeded (type=$fgsType, startId=$startId) — " +
                "stopping cleanly and scheduling deferred watchdog restart"
        )
        ServiceWatchdogState.markTimeoutStop(this)
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
        stopSelf(startId)
    }

    override fun onDestroy() {
        ShareServerKeepAliveCoordinator.onForegroundServiceInactive(this)
        val cleanStop = ServiceWatchdogState.consumeCleanStop(this)
        val timeoutStop = ServiceWatchdogState.consumeTimeoutStop(this)
        when {
            cleanStop || !ServiceWatchdogScheduler.isWatchdogEnabled(this) -> {
                ServiceWatchdog.cancelAlarm()
                if (cleanStop) {
                    ServiceWatchdogScheduler.clearShareServerHeartbeat(this)
                }
            }
            timeoutStop -> {
                Log.i(TAG, "FGS stopped after runtime timeout — deferred watchdog restart pending")
            }
            else -> {
                Log.i(TAG, "Unexpected FGS stop — scheduling immediate watchdog recovery")
                ServiceWatchdog.scheduleImmediateAlarmIfEnabled()
            }
        }
        wakeReceiver?.stop()
        wakeReceiver = null
        ServerLifecycleManager.stop(androidLog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isForegroundStart(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) == true ||
            intent?.action == ACTION_START

    private fun handlePromotionFailure(fromForeground: Boolean, stickyRestart: Boolean) {
        if (fromForeground) {
            Log.w(TAG, "Foreground promotion failed from UI — server not started")
            stopSelf()
            return
        }
        val trigger = if (stickyRestart) {
            ShareServerRestartCoordinator.RestartTrigger.STICKY_RESTART
        } else {
            ShareServerRestartCoordinator.RestartTrigger.WATCHDOG_ALARM
        }
        ShareServerRestartCoordinator.onForegroundPromotionBlocked(this, trigger)
        stopSelf()
    }

    /** UI path — must not crash the app when FGS/notification policy blocks promotion. */
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
        } catch (error: IllegalStateException) {
            Log.w(TAG, "UI FGS illegal state :: ${error.message}")
            false
        }
    }

    private fun promoteToForeground() {
        ensureServerNotificationChannel()
        val notification = buildServerNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val preferred = preferredForegroundServiceType()
            try {
                startForeground(NOTIFICATION_ID, notification, preferred)
            } catch (error: SecurityException) {
                // targetSdk 36+: connectedDevice also needs Wi‑Fi/BT/NFC companion permission.
                // Fall back to dataSync so the ongoing notification and server still come up.
                if (preferred == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
                    Log.w(
                        TAG,
                        "connectedDevice FGS denied — falling back to dataSync :: ${error.message}"
                    )
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    throw error
                }
            }
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        // Re-notify when POST_NOTIFICATIONS is granted; never crash if still denied.
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(TAG, "Foreground notification refresh skipped :: ${error.message}")
        }
        ShareServerPendingStart.clear(this)
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
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Background FGS illegal state :: ${error.message}")
            false
        }
    }

    /**
     * API 34+: prefer [CONNECTED_DEVICE] (LAN peers) to avoid Android 15 dataSync daily quota.
     * API 29–33: [DATA_SYNC] only.
     */
    private fun preferredForegroundServiceType(): Int {
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FileApex Server Active")
            .setContentText("Local WiFi secure ecosystem running...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
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
                PresenceBackgroundWake.onRemoteWakeSignal(sourceDeviceId = null)
            },
            onLog = { message -> Log.i(TAG, message) }
        )
        wakeReceiver = receiver
        receiver.start()
    }

    private fun ensureServerRunning() {
        ServerLifecycleManager.ensureRunning(androidLog)
    }

    /** Records FGS liveness for the 20-minute AlarmManager recovery watchdog (not cloud presence). */
    private fun recordServiceHeartbeat() {
        ServiceWatchdogScheduler.recordShareServerHeartbeat(this)
    }

    /**
     * Android never upgrades channel importance after first create. Earlier silent/min builds
     * could leave [LEGACY_CHANNEL_ID] invisible while the recovery channel still showed alerts.
     */
    private fun ensureServerNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(LEGACY_LOW_IMPORTANCE_CHANNEL_ID)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null || existing.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            if (existing != null) {
                manager.deleteNotificationChannel(CHANNEL_ID)
            }
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FileApex Server",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Persistent alert while the FileApex share server is running"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
            Log.i(TAG, "Created FGS notification channel $CHANNEL_ID importance=DEFAULT")
        }
    }

    companion object {
        private const val TAG = "FileApexServerService"
        /** New id — legacy channels may be stuck at silent/min importance. */
        private const val CHANNEL_ID = "fileapex_share_server_active_v2"
        private const val LEGACY_LOW_IMPORTANCE_CHANNEL_ID = "fileapex_share_server_active"
        private const val LEGACY_CHANNEL_ID = "FileApexServerChannel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CONTENT_REQUEST = 1_101
        const val ACTION_START = "com.fileapex.action.START_SHARE_SERVER"
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
