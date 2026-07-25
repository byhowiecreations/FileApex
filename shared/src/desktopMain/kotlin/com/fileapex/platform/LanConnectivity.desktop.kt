package com.fileapex.platform

import com.fileapex.util.NetworkUtils

actual fun isActiveLanConnectivity(): Boolean {
    val host = NetworkUtils.preferredLanIpv4()
    return NetworkUtils.isUsableLanIpv4(host)
}
