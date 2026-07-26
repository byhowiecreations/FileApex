package com.fileapex.domain.presence

import com.fileapex.cloud.FcmTokenRegistrar
import com.fileapex.cloud.CloudPresenceHeartbeat
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.network.FileApexMdnsAdvertiser
import com.fileapex.network.FileApexMdnsBrowser
import com.fileapex.network.RemoteAccessRoutingGuard
import com.fileapex.network.ServerLifecycleManager

/**
 * Dual-path background presence bootstrap — mDNS listener, network revalidation, FCM token sync.
 * Started with the share server (FGS on Android, desktop controller on Mac).
 */
object BackgroundPresenceServices {
    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (ServerLifecycleManager.isRunning) {
            val identity = loadLocalIdentity()
            FileApexMdnsAdvertiser.start(identity.sharePort, identity.deviceId)
        }
        FileApexMdnsBrowser.start { host, port, hintedDeviceId ->
            if (FileApexServices.isDatabaseReady()) {
                FileApexServices.presenceMonitor.onMdnsPeerDiscovered(host, port, hintedDeviceId)
            }
        }
        PresenceNetworkRevalidator.ensureRegistered()
        if (RemoteAccessRoutingGuard.isRemoteAccessAllowed()) {
            FcmTokenRegistrar.start()
            CloudPresenceHeartbeat.start()
        }
        if (FileApexServices.isDatabaseReady()) {
            FileApexServices.presenceMonitor.ensureLanPollLoop()
        }
        runCatching { GoogleLinkCoordinator.invalidatePublishedPresenceCache() }
    }

    fun stop(fast: Boolean = false) {
        if (!started) return
        started = false
        FileApexMdnsAdvertiser.stop(fast = fast)
        FileApexMdnsBrowser.stop(fast = fast)
        FcmTokenRegistrar.stop()
        CloudPresenceHeartbeat.stop()
    }

    fun onShareServerStarted(port: Int, deviceId: String) {
        if (!started) return
        FileApexMdnsAdvertiser.start(port, deviceId)
    }

    fun onShareServerStopped() {
        FileApexMdnsAdvertiser.stop()
    }
}
