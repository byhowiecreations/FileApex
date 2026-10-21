package com.fileapex.cloud.diagnostics

import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics

actual object DiagnosticsCloudRelay {
    actual suspend fun fetchPeerDiagnostics(peerDeviceId: String): PeerDeviceDiagnostics =
        DiagnosticsCloudRelayJvm.fetchPeerDiagnostics(peerDeviceId)

    actual suspend fun syncCloudOptIn(uid: String, deviceId: String, enabled: Boolean) =
        DiagnosticsCloudRelayJvm.syncCloudOptIn(uid, deviceId, enabled)

    actual fun startInbox(uid: String, selfDeviceId: String) =
        DiagnosticsCloudRelayJvm.startInbox(uid, selfDeviceId)

    actual fun stopInbox() = DiagnosticsCloudRelayJvm.stopInbox()

    actual fun onDiagnosticsWake(sessionId: String) =
        DiagnosticsCloudRelayJvm.onDiagnosticsWake(sessionId)
}
