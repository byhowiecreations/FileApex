package com.fileapex.network

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.BackgroundPresenceServices

/**
 * Process-wide FileApex share-server lifecycle.
 * Android FileShareServerService and desktop DesktopShareServerController must go through
 * this type so start/stop/ensure transitions share one lock (M10 / M12).
 */
object ServerLifecycleManager {
    private val lock = Any()
    private var serverInstance: FileApexServer? = null

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
            val identity = loadLocalIdentity()
            BackgroundPresenceServices.onShareServerStarted(identity.sharePort, identity.deviceId)
            BackgroundPresenceServices.start()
            return
        }
        runCatching { current?.stop() }
        val identity = loadLocalIdentity()
        val server = FileApexServer(
            port = identity.sharePort,
            identityProvider = { loadLocalIdentity() },
            onPairingRespond = { scanningDevice ->
                FileApexServices.pairingCoordinator.handleInboundScanner(scanningDevice)
            },
            onClusterMerge = { request ->
                FileApexServices.pairingCoordinator.mergeIncoming(request)
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
        BackgroundPresenceServices.onShareServerStarted(identity.sharePort, identity.deviceId)
        BackgroundPresenceServices.start()
        FileApexServices.presenceMonitor.scheduleColdLaunchProbeOnce()
        onLog(
            "Share server ensured running on port ${identity.sharePort} " +
                "root=${identity.rootPath}",
            null
        )
    }

    private fun stopLocked(onLog: (String, Throwable?) -> Unit, fast: Boolean) {
        BackgroundPresenceServices.stop(fast = fast)
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
