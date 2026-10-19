package com.fileapex.platform

import com.fileapex.util.NetworkUtils

actual fun isActiveLanConnectivity(): Boolean {
    val host = NetworkUtils.preferredLanIpv4()
    return NetworkUtils.isUsableLanIpv4(host)
}

actual fun hasUsableNetwork(): Boolean = NetworkUtils.lanIpv4Addresses().isNotEmpty()
