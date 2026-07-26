package com.fileapex.network

import com.fileapex.cloud.CloudPresenceHeartbeat
import com.fileapex.cloud.FcmTokenRegistrar
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.di.FileApexServices

/**
 * Single source of truth for internet-based P2P routing permission.
 *
 * When [FileApexServices.settings.cellularRemoteAccessEnabled] is off (default), all remote
 * signaling, FCM wake triggers, and STUN/WebRTC lookups are blocked; peer discovery and file
 * transfers remain on local Wi-Fi/mDNS only ([PeerLanHttpPolicy]).
 *
 * When on, Firebase signaling and FCM/STUN remote paths may run (including over cellular data).
 */
object RemoteAccessRoutingGuard {
    fun isRemoteAccessAllowed(): Boolean =
        FileApexServices.settings.cellularRemoteAccessEnabled.value

    /** Gate before initiating Firebase/Firestore remote peer signaling. */
    fun ensureRemoteSignalingAllowed(): Boolean = isRemoteAccessAllowed()

    /** Gate before dispatching or handling FCM silent wake payloads. */
    fun ensureFcmWakeAllowed(): Boolean = isRemoteAccessAllowed()

    /** Gate before STUN/ICE candidate lookup for WebRTC data channels. */
    fun ensureStunLookupAllowed(): Boolean = isRemoteAccessAllowed()

    /** Called when the user toggles Cellular & Remote Access in Settings. */
    fun onPreferenceChanged(enabled: Boolean) {
        if (enabled) {
            GoogleLinkCoordinator.onRemoteAccessEnabled()
            if (ServerLifecycleManager.isRunning) {
                FcmTokenRegistrar.start()
                CloudPresenceHeartbeat.start()
            }
        } else {
            FcmTokenRegistrar.stop()
            CloudPresenceHeartbeat.stop()
            GoogleLinkCoordinator.onRemoteAccessDisabled()
        }
    }
}
