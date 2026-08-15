package com.fileapex.cloud.drive

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
                delay(DriveRelayPolicy.LEDGER_POLL_INTERVAL_MS)
                runCatching { DriveRelayCoordinator.sweep() }
                    .onFailure { error ->
                        println("DriveSyncScheduler: sweep failed — ${error.message}")
                    }
            }
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
                    println("DriveSyncScheduler: immediate sweep failed — ${error.message}")
                }
        }
        ensureScheduled()
    }
}
