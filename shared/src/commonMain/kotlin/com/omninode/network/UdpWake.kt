package com.omninode.network

/**
 * Sends [WakeProtocol.PAYLOAD] as a UDP broadcast so sleeping Android peers
 * can start their local share server.
 */
expect fun sendWakeBroadcast()
