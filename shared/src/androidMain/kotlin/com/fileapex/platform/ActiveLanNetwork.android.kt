package com.fileapex.platform

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address

actual fun activeLanIpv4Addresses(): List<String> {
    val context = androidApplicationContextOrNull() ?: return emptyList()
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val network = connectivity.activeNetwork ?: return emptyList()
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return emptyList()
    val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    if (!onLan) {
        return emptyList()
    }
    val linkProperties = connectivity.getLinkProperties(network) ?: return emptyList()
    return linkProperties.linkAddresses
        .mapNotNull(LinkAddress::getAddress)
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .distinct()
}
