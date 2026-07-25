package com.fileapex.domain.presence

import com.fileapex.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Polls primary LAN IPv4 set; fires when interfaces change (no Android NetworkCallback on JVM). */
internal object DesktopLanNetworkTransitionMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    @Volatile
    private var lastSnapshot: Set<String> = emptySet()

    fun ensureRegistered() {
        if (started) return
        started = true
        lastSnapshot = currentSnapshot()
        scope.launch {
            while (isActive) {
                delay(CHECK_MS)
                val next = currentSnapshot()
                if (next != lastSnapshot) {
                    lastSnapshot = next
                    println("DesktopLanNetworkTransitionMonitor: LAN interfaces changed — revalidating")
                    PresenceNetworkRevalidator.onLanNetworkTransition()
                }
            }
        }
    }

    private fun currentSnapshot(): Set<String> =
        NetworkUtils.lanBindCandidates().toSet()
}

private const val CHECK_MS = 5_000L
