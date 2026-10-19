package com.fileapex.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fileapex.util.NetworkUtils

private var appContext: Context? = null

internal fun androidApplicationContextOrNull(): Context? = appContext

fun initAndroidLanConnectivity(context: Context) {
    appContext = context.applicationContext
}

actual fun isActiveLanConnectivity(): Boolean =
    NetworkUtils.lanBindCandidates().isNotEmpty()

actual fun hasUsableNetwork(): Boolean {
    val connectivity = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val active = connectivity.activeNetwork
        ?: return NetworkUtils.lanBindCandidates().isNotEmpty()
    val caps = connectivity.getNetworkCapabilities(active) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
