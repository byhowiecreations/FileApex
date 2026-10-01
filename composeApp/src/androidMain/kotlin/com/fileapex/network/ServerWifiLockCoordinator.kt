package com.fileapex.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Holds a [WifiManager.WifiLock] for the lifetime of the share server FGS so the Wi-Fi radio
 * stays responsive to incoming TCP connections while the screen is off. Without this, Android's
 * Wi-Fi power-save mode (DTIM sleep) delays incoming connections by tens of seconds.
 *
 * The lock is released and re-acquired on network transitions so we never hold a stale reference
 * after the Wi-Fi adapter resets.
 */
internal object ServerWifiLockCoordinator {
    private const val TAG = "ServerWifiLock"
    private const val LOCK_TAG = "fileapex:server_wifi"

    private val lock = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeContext: Context? = null

    fun acquire(context: Context) {
        synchronized(lock) {
            val appContext = context.applicationContext
            activeContext = appContext
            acquireLockInternal(appContext)
            registerNetworkCallback(appContext)
        }
    }

    fun release(context: Context) {
        synchronized(lock) {
            unregisterNetworkCallback(context.applicationContext)
            releaseLockInternal()
            activeContext = null
        }
    }

    private fun acquireLockInternal(appContext: Context) {
        if (wifiLock?.isHeld == true) return
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                Log.w(TAG, "WifiManager unavailable")
                return
            }
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Acquired server WifiLock (mode=$mode)")
        } catch (error: SecurityException) {
            // Samsung/MIUI/HyperOS aggressive battery management can strip lock permissions
            Log.w(TAG, "WifiLock denied by OEM policy: ${error.message}")
            wifiLock = null
        } catch (error: Exception) {
            Log.w(TAG, "WifiLock acquire failed: ${error.message}")
            wifiLock = null
        }
    }

    private fun releaseLockInternal() {
        val held = wifiLock ?: return
        try {
            if (held.isHeld) {
                held.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "WifiLock release failed: ${error.message}")
        }
        wifiLock = null
        Log.i(TAG, "Released server WifiLock")
    }

    private fun registerNetworkCallback(appContext: Context) {
        if (networkCallback != null) return
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    val ctx = activeContext ?: return
                    if (wifiLock?.isHeld != true) {
                        Log.i(TAG, "Network available - re-acquiring WifiLock")
                        acquireLockInternal(ctx)
                    }
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    Log.i(TAG, "Network lost - releasing stale WifiLock")
                    releaseLockInternal()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                synchronized(lock) {
                    val ctx = activeContext ?: return
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        wifiLock?.isHeld != true
                    ) {
                        Log.i(TAG, "Wi-Fi capabilities restored - re-acquiring WifiLock")
                        acquireLockInternal(ctx)
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (error: Exception) {
            Log.w(TAG, "Network callback registration failed: ${error.message}")
        }
    }

    private fun unregisterNetworkCallback(appContext: Context) {
        val callback = networkCallback ?: return
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (error: Exception) {
            Log.w(TAG, "Network callback unregister failed: ${error.message}")
        }
        networkCallback = null
    }
}
