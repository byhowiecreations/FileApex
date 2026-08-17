package com.fileapex.network

import com.fileapex.domain.pairing.PairingBeacon

internal expect object PairingBeaconTransport {
    fun sendBeaconOnce(beacon: PairingBeacon)
    fun startBroadcast(beacon: PairingBeacon)
    fun stopBroadcast()
    fun startListener(onBeacon: (PairingBeacon) -> Unit)
    fun stopListener()
}

/** Platform log sink — Android uses Logcat (`FileApexPairing`); desktop uses stdout. */
internal expect fun pairingBeaconLog(message: String)
