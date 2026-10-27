package com.fileapex.network

/**
 * Sends the signed wake hellos ([WakeHelloOutbox]) as a UDP broadcast so sleeping Android peers
 * can start their local share server.
 */
expect fun sendWakeBroadcast()
