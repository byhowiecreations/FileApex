package com.fileapex.network

/** Registers this node on LAN via mDNS/Bonjour when the share server is running. */
expect object FileApexMdnsAdvertiser {
    fun start(port: Int, deviceId: String)
    fun stop(fast: Boolean = false)
}
