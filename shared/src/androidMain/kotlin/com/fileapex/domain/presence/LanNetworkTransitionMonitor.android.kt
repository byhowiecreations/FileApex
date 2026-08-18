package com.fileapex.domain.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.fileapex.platform.androidApplicationContextOrNull
import com.fileapex.util.TimeUtils

/** Observes Wi‑Fi/Ethernet transitions and triggers one-shot LAN revalidation. */
internal object LanNetworkTransitionMonitor {
    private const val CAPABILITIES_DEBOUNCE_MS = 60_000L

    @Volatile
    private var registered = false

    @Volatile
    private var lastCapabilitiesNotifyAtMs = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyTransition("available")
        override fun onLost(network: Network) = notifyTransition("lost")
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return
            }
            val now = TimeUtils.now()
            if (lastCapabilitiesNotifyAtMs > 0L &&
                now - lastCapabilitiesNotifyAtMs < CAPABILITIES_DEBOUNCE_MS
            ) {
                return
            }
            lastCapabilitiesNotifyAtMs = now
            notifyTransition("capabilities")
        }
    }

    fun ensureRegistered() {
        if (registered) return
        val context = androidApplicationContextOrNull() ?: return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        }.onFailure { error ->
            println("LanNetworkTransitionMonitor: register failed - ${error.message}")
        }
    }

    private fun notifyTransition(reason: String) {
        println("LanNetworkTransitionMonitor: network $reason - revalidating peers")
        PresenceNetworkRevalidator.onLanNetworkTransition()
    }
}
