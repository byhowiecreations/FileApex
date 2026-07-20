package com.omninode.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.omninode.R
import com.omninode.data.identity.LocalIdentity
import com.omninode.platform.ServiceWatchdog
import com.omninode.platform.ServiceWatchdogScheduler
import com.omninode.platform.ServiceWatchdogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the LAN share server alive via [ServerLifecycleManager].
 *
 * Manifest declares `connectedDevice|dataSync`; [promoteToForeground] passes matching
 * [ServiceInfo] flags on every start so OEM morning pruning treats this as a typed FGS.
 * UDP wake listening runs inside this service (not a separate background service).
 *
 * When [enableServiceWatchdog] is on, [ServiceWatchdog] schedules AlarmManager heartbeats
 * on unexpected termination ([onTaskRemoved] / non-clean [onDestroy]).
 */
class FileShareServerService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeReceiver: UdpWakeReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()
        ensureServerRunning()
        startWakeListener()
        startEngineWatchdog()
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        ensureServerRunning()
        if (wakeReceiver == null) {
            startWakeListener()
        }
        ServiceWatchdog.scheduleNextAlarmIfEnabled()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ServiceWatchdogScheduler.isWatchdogEnabled()) {
            Log.i(TAG, "Task removed — scheduling service watchdog alarm")
            ServiceWatchdogScheduler.scheduleNext(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val cleanStop = ServiceWatchdogState.consumeCleanStop(this)
        if (cleanStop || !ServiceWatchdogScheduler.isWatchdogEnabled()) {
            ServiceWatchdog.cancelAlarm()
        } else {
            Log.i(TAG, "Unexpected FGS stop — scheduling service watchdog alarm")
            ServiceWatchdogScheduler.scheduleNext(this)
        }
        wakeReceiver?.stop()
        wakeReceiver = null
        serviceJob.cancel()
        ServerLifecycleManager.stop(androidLog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val notification = buildServerNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeFlags())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceTypeFlags(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return type
    }

    private fun buildServerNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OmniNode Server Active")
                .setContentText("Local WiFi secure ecosystem running...")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("OmniNode Server Active")
                .setContentText("Local WiFi secure ecosystem running...")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build()
        }
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

    /** In-process engine health check (distinct from AlarmManager service watchdog). */
    private fun startEngineWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(ENGINE_WATCHDOG_INTERVAL_MS)
                if (!ServerLifecycleManager.isRunning) {
                    Log.w(TAG, "Engine watchdog detected dead share server — restarting")
                    ensureServerRunning()
                }
            }
        }
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        private const val TAG = "OmniNodeServerService"
        private const val CHANNEL_ID = "OmniNodeServerChannel"
        private const val NOTIFICATION_ID = 1
        private const val ENGINE_WATCHDOG_INTERVAL_MS = 5_000L
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
