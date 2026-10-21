package com.fileapex.domain.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.fileapex.platform.androidApplicationContextOrNull
import java.net.Inet4Address

/** Observes Wi‑Fi/Ethernet transitions and triggers one-shot LAN revalidation. */
internal object LanNetworkTransitionMonitor {
    @Volatile
    private var registered = false

    private val filter = LanTransitionFilter()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (filter.onAvailable(network.toString())) notifyTransition("available")
        }

        override fun onLost(network: Network) {
            if (filter.onLost(network.toString())) notifyTransition("lost")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (filter.onCapabilities(network.toString(), validated)) notifyTransition("validated")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val ipv4 = linkProperties.linkAddresses
                .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                .toSet()
            if (filter.onLinkAddresses(network.toString(), ipv4)) notifyTransition("address")
        }
    }

    @Synchronized fun ensureRegistered() {
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
