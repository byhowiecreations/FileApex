package com.omninode.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.omninode.util.NetworkUtils

private var appContext: Context? = null

fun initAndroidLanConnectivity(context: Context) {
    appContext = context.applicationContext
}

actual fun isActiveLanConnectivity(): Boolean {
    val host = NetworkUtils.preferredLanIpv4()
    if (!NetworkUtils.isUsableLanIpv4(host)) {
        return false
    }
    val context = appContext ?: return true
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val network = connectivity.activeNetwork ?: return false
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
