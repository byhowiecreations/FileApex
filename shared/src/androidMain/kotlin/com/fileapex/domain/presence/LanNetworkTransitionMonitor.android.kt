package com.fileapex.domain.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.fileapex.platform.androidApplicationContextOrNull

/** Observes Wi‑Fi/Ethernet transitions and triggers one-shot LAN revalidation. */
internal object LanNetworkTransitionMonitor {
    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyTransition("available")
        override fun onLost(network: Network) = notifyTransition("lost")
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                notifyTransition("capabilities")
            }
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
            println("LanNetworkTransitionMonitor: register failed — ${error.message}")
        }
    }

    private fun notifyTransition(reason: String) {
        println("LanNetworkTransitionMonitor: network $reason — revalidating peers")
        PresenceNetworkRevalidator.onLanNetworkTransition()
    }
}
