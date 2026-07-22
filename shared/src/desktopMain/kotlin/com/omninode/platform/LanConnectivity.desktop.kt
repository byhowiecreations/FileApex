package com.omninode.platform

import com.omninode.util.NetworkUtils

actual fun isActiveLanConnectivity(): Boolean {
    val host = NetworkUtils.preferredLanIpv4()
    return NetworkUtils.isUsableLanIpv4(host)
}
