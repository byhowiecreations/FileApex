package com.fileapex.network

import java.net.DatagramSocket

/**
 * Opens a UDP listener bound to [WakeProtocol.PORT] and joined to the discovery multicast
 * group on all active non-loopback interfaces.
 */
internal expect fun openWakeListenerSocket(onLog: (String) -> Unit): DatagramSocket?
