package com.omninode.network

import com.omninode.data.identity.loadLocalIdentity
import com.omninode.di.OmniNodeServices

/**
 * Process-wide OmniNode share-server lifecycle.
 * Android FileShareServerService and desktop DesktopShareServerController must go through
 * this type so start/stop/ensure transitions share one lock (M10 / M12).
 */
object ServerLifecycleManager {
    private val lock = Any()
    private var serverInstance: OmniNodeServer? = null

    val isRunning: Boolean
        get() = synchronized(lock) { serverInstance?.isRunning == true }

    /**
     * Ensure a single [OmniNodeServer] is listening. Safe to call from UI, service, or watchdog.
     */
    fun ensureRunning(onLog: (String, Throwable?) -> Unit = defaultLog) {
        synchronized(lock) {
            ensureRunningLocked(onLog)
        }
    }

    /**
     * Stop the engine and clear the process instance. Idempotent.
     */
    fun stop(onLog: (String, Throwable?) -> Unit = defaultLog) {
        synchronized(lock) {
            stopLocked(onLog)
        }
    }

    private fun ensureRunningLocked(onLog: (String, Throwable?) -> Unit) {
        val current = serverInstance
        if (current != null && current.isRunning) {
            return
        }
        runCatching { current?.stop() }
        val identity = loadLocalIdentity()
        serverInstance = OmniNodeServer(
            port = identity.sharePort,
            identityProvider = { loadLocalIdentity() },
            onPairingRespond = { scanningDevice ->
                OmniNodeServices.pairingCoordinator.handleInboundScanner(scanningDevice)
            },
            onClusterMerge = { request ->
                OmniNodeServices.pairingCoordinator.mergeIncoming(request)
            },
            onListDevices = {
                OmniNodeServices.deviceRepository.listDevices()
            },
            onLog = onLog
        ).also { it.start() }
        onLog(
            "Share server ensured running on port ${identity.sharePort} " +
                "root=${identity.rootPath}",
            null
        )
    }

    private fun stopLocked(onLog: (String, Throwable?) -> Unit) {
        val current = serverInstance
        serverInstance = null
        if (current != null) {
            runCatching { current.stop() }
                .onFailure { error -> onLog("Error while stopping share server", error) }
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
