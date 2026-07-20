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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process-alive share server via [ServerLifecycleManager].
 * Does not hold a persistent PARTIAL_WAKE_LOCK — wake packets use a short-lived lock in
 * [com.omninode.platform.OmniNodeWakeService] only during UDP signal processing.
 */
class FileShareServerService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OmniNode Server Active")
                .setContentText("Local WiFi secure ecosystem running...")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        ensureServerRunning()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureServerRunning()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        ServerLifecycleManager.stop(androidLog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureServerRunning() {
        ServerLifecycleManager.ensureRunning(androidLog)
    }

    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!ServerLifecycleManager.isRunning) {
                    Log.w(TAG, "Watchdog detected dead share server — restarting")
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
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        private const val TAG = "OmniNodeServerService"
        private const val CHANNEL_ID = "OmniNodeServerChannel"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_INTERVAL_MS = 5_000L
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
