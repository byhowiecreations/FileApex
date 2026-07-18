package com.omninode.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.omninode.R
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.identity.loadLocalIdentity
import com.omninode.di.OmniNodeServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the OmniNode Ktor lifecycle for the process.
 * The engine is restarted if it ever drops while the service remains alive.
 */
class FileShareServerService : Service() {
    private var serverInstance: OmniNodeServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
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
        runCatching { serverInstance?.stop() }
        serverInstance = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureServerRunning() {
        val current = serverInstance
        if (current != null && current.isRunning) {
            return
        }
        runCatching { current?.stop() }
        val identity = loadLocalIdentity()
        serverInstance = OmniNodeServer(
            port = identity.sharePort,
            identityProvider = { loadLocalIdentity() },
            onPairingRespond = { scanningDevice ->
                OmniNodeServices.pairingCoordinator.handleInboundScanner(scanningDevice)
            },
            onClusterMerge = { request ->
                OmniNodeServices.pairingCoordinator.mergeIncoming(request)
            },
            onListDevices = {
                OmniNodeServices.deviceRepository.listDevices()
            },
            onLog = { message, error ->
                if (error != null) {
                    Log.e(TAG, message, error)
                } else {
                    Log.i(TAG, message)
                }
            }
        ).also { it.start() }
        Log.i(TAG, "Share server ensured running on port ${identity.sharePort}")
    }

    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (serverInstance?.isRunning != true) {
                    Log.w(TAG, "Watchdog detected dead share server — restarting")
                    ensureServerRunning()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OmniNode:ShareServerWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null
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
    }
}
