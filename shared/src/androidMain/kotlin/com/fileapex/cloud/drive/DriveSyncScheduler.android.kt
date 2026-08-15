package com.fileapex.cloud.drive

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fileapex.data.settings.androidAppContextOrNull
import java.util.concurrent.TimeUnit

actual object DriveSyncScheduler {
    actual fun ensureScheduled() {
        val context = androidAppContextOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<DriveSyncWorker>(
            DriveRelayPolicy.LEDGER_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    actual fun cancel() {
        val context = androidAppContextOrNull() ?: return
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_PERIODIC)
        manager.cancelUniqueWork(UNIQUE_ONE_SHOT)
    }

    actual fun enqueueImmediateSweep() {
        val context = androidAppContextOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val oneShot = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONE_SHOT,
            ExistingWorkPolicy.KEEP,
            oneShot
        )
        ensureScheduled()
    }

    private const val UNIQUE_PERIODIC = "fileapex_drive_sync"
    private const val UNIQUE_ONE_SHOT = "fileapex_drive_sync_now"
}

class DriveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            DriveRelayCoordinator.sweep()
            Result.success()
        }.getOrElse { error ->
            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
            println("DriveSyncWorker: ${error.message}")
            Result.retry()
        }
    }
}
