package com.fileapex.cloud.drive

/**
 * Platform task scheduler for Drive upload retries and ledger sweeps.
 * Android: WorkManager. Desktop: coroutine loop.
 */
expect object DriveSyncScheduler {
    fun ensureScheduled()

    fun cancel()

    fun enqueueImmediateSweep()
}
