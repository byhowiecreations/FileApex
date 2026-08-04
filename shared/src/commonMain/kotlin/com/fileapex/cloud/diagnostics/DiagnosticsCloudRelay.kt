package com.fileapex.cloud.diagnostics

import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics

/**
 * Encrypted cloud relay for Device Details when LAN is unavailable.
 * LAN path remains plaintext HTTP and does not use this API.
 */
expect object DiagnosticsCloudRelay {
    suspend fun fetchPeerDiagnostics(peerDeviceId: String): PeerDeviceDiagnostics

    suspend fun syncCloudOptIn(uid: String, deviceId: String, enabled: Boolean)

    fun startInbox(uid: String, selfDeviceId: String)

    fun stopInbox()

    /** Android FCM wake — process a specific pending relay session immediately. */
    fun onDiagnosticsWake(sessionId: String)
}
