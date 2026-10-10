package com.fileapex.network

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.BackgroundPresenceServices
import com.fileapex.platform.syncDirectShareTargetsFromPeers
import com.fileapex.security.tls.LocalTlsInfo
import com.fileapex.security.tls.TlsFront
import com.fileapex.security.tls.TlsFrontProvider

/**
 * Process-wide FileApex share-server lifecycle.
 * Android FileShareServerService and desktop DesktopShareServerController must go through
 * this type so start/stop/ensure transitions share one lock (M10 / M12).
 */
object ServerLifecycleManager {
    private val lock = Any()
    private var serverInstance: FileApexServer? = null
    private var tlsFront: TlsFront? = null

    val isRunning: Boolean
        get() = synchronized(lock) { serverInstance?.isRunning == true }

    /**
     * Ensure a single [FileApexServer] is listening. Safe to call from UI, service, or watchdog.
     */
    fun ensureRunning(onLog: (String, Throwable?) -> Unit = defaultLog) {
        synchronized(lock) {
            ensureRunningLocked(onLog)
        }
    }

    /**
     * Stop the engine and clear the process instance. Idempotent.
     * @param fast When true (desktop quit), use minimal Ktor drain so the UI thread is not blocked.
     */
    fun stop(onLog: (String, Throwable?) -> Unit = defaultLog, fast: Boolean = false) {
        synchronized(lock) {
            stopLocked(onLog, fast)
        }
    }

    private fun ensureRunningLocked(onLog: (String, Throwable?) -> Unit) {
        val current = serverInstance
        if (current != null && current.isRunning) {
            // Already serving: advertising, shortcuts and the presence services were set up when it started.
            BackgroundPresenceServices.start()
            return
        }
        runCatching { current?.stop() }
        stopTlsFrontLocked(onLog)
        val identity = loadLocalIdentity()
        val front = runCatching { TlsFrontProvider.factory?.invoke(LanInterfaceBinding.shareServerListenHost()) }
            .onFailure { error -> onLog("TLS front unavailable, serving HTTP only", error) }
            .getOrNull()
        val server = FileApexServer(
            port = identity.sharePort,
            bridgePort = front?.bridgePort ?: 0,
            identityProvider = { loadLocalIdentity() },
            onPairingRespond = { scanningDevice ->
                FileApexServices.pairingCoordinator.handleInboundScanner(scanningDevice)
            },
            onPairingRespondComplete = { scanningDevice ->
                FileApexServices.pairingCoordinator.propagatePairingComplete(scanningDevice)
            },
            onClusterMerge = { request ->
                FileApexServices.pairingCoordinator.mergeIncoming(request)
            },
            onClusterPeerRemoved = { record ->
                FileApexServices.pairingCoordinator.handlePeerRemoval(record, forward = true)
            },
            onClusterSelfRemoved = { record ->
                FileApexServices.pairingCoordinator.handleSelfRemoval(record)
            },
            onListDevices = {
                FileApexServices.deviceRepository.listDevices()
            },
            onLog = onLog
        )
        runCatching { server.start() }
            .onFailure { error ->
                onLog("Share server failed to start on port ${identity.sharePort}", error)
                return
            }
        serverInstance = server
        if (front != null) {
            runCatching { front.start() }
                .onSuccess { tlsPort ->
                    tlsFront = front
                    LocalTlsInfo.port = tlsPort
                }
                .onFailure { error ->
                    onLog("TLS front failed to start, serving HTTP only", error)
                    runCatching { front.stop() }
                }
        }
        BackgroundPresenceServices.onShareServerStarted(identity.sharePort, identity.deviceId)
        BackgroundPresenceServices.start()
        syncDirectShareTargetsFromPeers()
        FileApexServices.presenceMonitor.scheduleColdLaunchProbeOnce()
        onLog(
            "Share server ensured running on port ${identity.sharePort} " +
                "root=${identity.rootPath}",
            null
        )
    }

    private fun stopTlsFrontLocked(onLog: (String, Throwable?) -> Unit) {
        val front = tlsFront ?: return
        tlsFront = null
        LocalTlsInfo.port = 0
        runCatching { front.stop() }.onFailure { error -> onLog("Error while stopping TLS front", error) }
        com.fileapex.security.tls.TlsBridgeRegistry.clear()
    }

    private fun stopLocked(onLog: (String, Throwable?) -> Unit, fast: Boolean) {
        BackgroundPresenceServices.stop(fast = fast)
        stopTlsFrontLocked(onLog)
        val current = serverInstance
        serverInstance = null
        if (current != null) {
            runCatching {
                if (fast) {
                    current.stop(gracePeriodMillis = 0, timeoutMillis = 250)
                } else {
                    current.stop()
                }
            }.onFailure { error -> onLog("Error while stopping share server", error) }
        }
    }

    private val defaultLog: (String, Throwable?) -> Unit = { message, error ->
        if (error != null) {
            println("ServerLifecycleManager: $message :: ${error.message}")
            error.printStackTrace()
        } else {
            println("ServerLifecycleManager: $message")
        }
    }
}
