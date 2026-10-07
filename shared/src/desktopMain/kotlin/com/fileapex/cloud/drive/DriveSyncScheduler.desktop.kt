package com.fileapex.cloud.drive

import com.fileapex.platform.collectFastBatteryDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual object DriveSyncScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    actual fun ensureScheduled() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs())
                runCatching { DriveRelayCoordinator.sweep() }
                    .onFailure { error ->
                        println("DriveSyncScheduler: sweep failed - ${error.message}")
                    }
            }
        }
    }

    // Battery and unknown power sources keep the slow cadence so a laptop is never woken more often.
    private fun pollIntervalMs(): Long {
        val state = runCatching { collectFastBatteryDiagnostics().chargingState }.getOrDefault("")
        return if (state == "AC" || state == "Full") {
            DriveRelayPolicy.LEDGER_POLL_INTERVAL_AC_MS
        } else {
            DriveRelayPolicy.LEDGER_POLL_INTERVAL_MS
        }
    }

    actual fun cancel() {
        loopJob?.cancel()
        loopJob = null
    }

    actual fun enqueueImmediateSweep() {
        scope.launch {
            runCatching { DriveRelayCoordinator.sweep() }
                .onFailure { error ->
                    println("DriveSyncScheduler: immediate sweep failed - ${error.message}")
                }
        }
        ensureScheduled()
    }
}
