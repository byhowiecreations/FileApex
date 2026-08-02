package com.fileapex.network

object WakeProtocol {
    const val PORT: Int = 8888
    const val PAYLOAD: String = "WAKE_FILEAPEX"
    const val BROADCAST_ADDRESS: String = "255.255.255.255"
    /** LAN discovery multicast — joined on every active interface for inbound wake/heartbeat UDP. */
    const val MULTICAST_ADDRESS: String = "239.255.0.88"
}
