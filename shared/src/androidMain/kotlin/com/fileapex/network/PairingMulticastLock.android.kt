package com.fileapex.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.fileapex.platform.androidApplicationContextOrNull

private const val TAG = "FileApexPairing"

private val lock = Any()
private var multicastLock: WifiManager.MulticastLock? = null
private var refs = 0

internal actual fun acquirePairingMulticastLock() {
    synchronized(lock) {
        if (refs == 0) {
            val context = androidApplicationContextOrNull()
            if (context == null) {
                Log.w(TAG, "multicast lock skipped — no application context")
            } else {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifi == null) {
                    Log.w(TAG, "multicast lock skipped — WifiManager unavailable")
                } else {
                    multicastLock = wifi.createMulticastLock("fileapex-pairing").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    Log.i(TAG, "multicast lock acquired")
                }
            }
        }
        refs++
    }
}

internal actual fun releasePairingMulticastLock() {
    synchronized(lock) {
        if (refs == 0) return
        refs--
        if (refs == 0) {
            runCatching { multicastLock?.release() }
            multicastLock = null
        }
    }
}
