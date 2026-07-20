package com.omninode.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.omninode.network.ServerLifecycleManager
import com.omninode.network.UdpWakeReceiver

/**
 * Process-owned UDP wake listener. Holds [OmniNode:ShareServerWakeLock] only for the
 * short processing window after a coalesced wake packet — never for the service lifetime.
 */
class OmniNodeWakeService : Service() {
    private var wakeReceiver: UdpWakeReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val receiver = UdpWakeReceiver(
            onWakeAccepted = { processWakeSignal() },
            onLog = { message -> Log.i(TAG, message) }
        )
        wakeReceiver = receiver
        receiver.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wakeReceiver?.stop()
        wakeReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun processWakeSignal() {
        withShortLivedWakeLock {
            // Foreground share service owns ServerLifecycleManager while running;
            // ensureRunning here covers the race before the FGS attaches.
            ServerLifecycleManager.ensureRunning(androidLog)
            startShareServer()
        }
    }

    private fun withShortLivedWakeLock(block: () -> Unit) {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OmniNode:ShareServerWakeLock"
        ).apply {
            setReferenceCounted(true)
            acquire(PROCESS_WAKE_LOCK_MS)
        }
        try {
            block()
        } finally {
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
    }

    private fun startShareServer() {
        val intent = Intent().setClassName(packageName, FILE_SHARE_SERVER_SERVICE)
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        private const val TAG = "OmniNodeWakeService"
        private const val PROCESS_WAKE_LOCK_MS = 10_000L
        private const val FILE_SHARE_SERVER_SERVICE =
            "com.omninode.network.FileShareServerService"

        private val androidLog: (String, Throwable?) -> Unit = { message, error ->
            if (error != null) {
                Log.e(TAG, message, error)
            } else {
                Log.i(TAG, message)
            }
        }
    }
}
