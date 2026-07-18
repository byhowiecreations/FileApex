package com.omninode.network

import com.omninode.data.identity.loadLocalIdentity
import com.omninode.di.OmniNodeServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop/Mac process-owned share server lifecycle (Android uses FileShareServerService).
 * Starts OmniNodeServer with pairing + cluster callbacks so QR reverse-pair works.
 */
object DesktopShareServerController {
    private val lock = Any()
    private var serverInstance: OmniNodeServer? = null
    private var watchdogScope: CoroutineScope? = null
    private var watchdogStarted = false

    fun start() {
        synchronized(lock) {
            ensureServerRunningLocked()
            if (!watchdogStarted) {
                watchdogStarted = true
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                watchdogScope = scope
                scope.launch {
                    while (isActive) {
                        delay(5_000)
                        synchronized(lock) {
                            if (serverInstance?.isRunning != true) {
                                println("DesktopShareServer: watchdog restart")
                                ensureServerRunningLocked()
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        // Same teardown as Exit: cancel watchdog so the server cannot resurrect.
        shutdownBlocking()
    }

    fun shutdownBlocking() {
        synchronized(lock) {
            runCatching { serverInstance?.stop() }
            serverInstance = null
            watchdogScope?.cancel()
            watchdogScope = null
            watchdogStarted = false
        }
    }

    private fun ensureServerRunningLocked() {
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
            onLog = { message, error ->
                if (error != null) {
                    println("DesktopShareServer: $message :: ${error.message}")
                    error.printStackTrace()
                } else {
                    println("DesktopShareServer: $message")
                }
            }
        ).also { it.start() }
        println(
            "DesktopShareServer: listening on port ${identity.sharePort} " +
                "root=${identity.rootPath}"
        )
    }
}
