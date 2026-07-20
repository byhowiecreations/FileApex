package com.omninode.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop/Mac process-owned share server + UDP wake lifecycle around [ServerLifecycleManager].
 * Keeps Android/Desktop wake handling symmetric so neither OS leaves orphaned sockets.
 */
object DesktopShareServerController {
    private val lifecycleLock = Any()
    private var watchdogScope: CoroutineScope? = null
    private var watchdogStarted = false
    private var wakeReceiver: UdpWakeReceiver? = null

    /** Bind the UDP wake listener for the app process lifetime (blocking receive). */
    fun startWakeListener() {
        synchronized(lifecycleLock) {
            if (wakeReceiver != null) return
            val receiver = UdpWakeReceiver(
                onWakeAccepted = {
                    ServerLifecycleManager.ensureRunning(desktopLog)
                },
                onLog = { message -> println("DesktopShareServer: $message") }
            )
            wakeReceiver = receiver
            receiver.start()
            println("DesktopShareServer: UDP wake listener started")
        }
    }

    fun start() {
        ServerLifecycleManager.ensureRunning(desktopLog)
        synchronized(lifecycleLock) {
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
        synchronized(lifecycleLock) {
            wakeReceiver?.stop()
            wakeReceiver = null
            watchdogScope?.cancel()
            watchdogScope = null
            watchdogStarted = false
        }
        ServerLifecycleManager.stop(desktopLog)
        println("DesktopShareServer: shutdown complete")
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
