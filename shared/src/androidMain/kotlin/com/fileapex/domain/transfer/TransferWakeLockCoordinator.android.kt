package com.fileapex.domain.transfer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.fileapex.platform.androidApplicationContextOrNull

internal actual object TransferWakeLockCoordinator {
    private const val TAG = "TransferWakeLock"
    private const val SAFETY_TIMEOUT_MS = 60 * 60 * 1000L
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var refs = 0

    actual fun acquire() {
        synchronized(lock) {
            if (refs == 0) {
                val context = androidApplicationContextOrNull()
                if (context != null) {
                    runCatching {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        wakeLock = powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "fileapex:transfer_active"
                        )?.apply {
                            setReferenceCounted(false)
                            acquire(SAFETY_TIMEOUT_MS)
                        }
                    }.onFailure { Log.w(TAG, "Failed to acquire PARTIAL_WAKE_LOCK: ${it.message}") }

                    runCatching {
                        val wifiManager = context.applicationContext
                            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        val wifiMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        } else {
                            @Suppress("DEPRECATION")
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF
                        }
                        wifiLock = wifiManager?.createWifiLock(
                            wifiMode,
                            "fileapex:transfer_wifi"
                        )?.apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                    }.onFailure { Log.w(TAG, "Failed to acquire WifiLock: ${it.message}") }
                    Log.i(TAG, "Acquired transfer WakeLock and WifiLock")
                }
            }
            refs++
        }
    }

    actual fun release() {
        synchronized(lock) {
            if (refs == 0) return
            refs--
            if (refs == 0) {
                cleanupLocks()
                Log.i(TAG, "Released transfer WakeLock and WifiLock")
            }
        }
    }

    actual fun releaseAll() {
        synchronized(lock) {
            if (refs > 0) {
                refs = 0
                cleanupLocks()
                Log.i(TAG, "Force released all transfer WakeLocks and WifiLocks")
            }
        }
    }

    private fun cleanupLocks() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        }
        wakeLock = null
        runCatching {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        }
        wifiLock = null
    }
}
