package com.omninode.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop/Mac process-owned share server lifecycle wrapper around [ServerLifecycleManager].
 */
object DesktopShareServerController {
    private val watchdogLock = Any()
    private var watchdogScope: CoroutineScope? = null
    private var watchdogStarted = false

    fun start() {
        ServerLifecycleManager.ensureRunning(desktopLog)
        synchronized(watchdogLock) {
            if (!watchdogStarted) {
                watchdogStarted = true
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                watchdogScope = scope
                scope.launch {
                    while (isActive) {
                        delay(5_000)
                        if (!ServerLifecycleManager.isRunning) {
                            println("DesktopShareServer: watchdog restart")
                            ServerLifecycleManager.ensureRunning(desktopLog)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        shutdownBlocking()
    }

    fun shutdownBlocking() {
        synchronized(watchdogLock) {
            watchdogScope?.cancel()
            watchdogScope = null
            watchdogStarted = false
        }
        ServerLifecycleManager.stop(desktopLog)
    }

    private val desktopLog: (String, Throwable?) -> Unit = { message, error ->
        if (error != null) {
            println("DesktopShareServer: $message :: ${error.message}")
            error.printStackTrace()
        } else {
            println("DesktopShareServer: $message")
        }
    }
}
