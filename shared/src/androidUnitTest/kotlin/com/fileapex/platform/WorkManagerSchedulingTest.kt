package com.fileapex.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The background backup is scheduled and cancelled through WorkManager; this runs the same calls against the real library. */
@RunWith(RobolectricTestRunner::class)
class WorkManagerSchedulingTest {
    private fun manager(): WorkManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            WorkManager.initialize(
                context,
                Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build()
            )
        }
        return WorkManager.getInstance(context)
    }

    private fun states(manager: WorkManager, name: String): List<WorkInfo.State> =
        manager.getWorkInfosForUniqueWork(name).get(20, TimeUnit.SECONDS).map { it.state }

    @Test
    fun aPeriodicBackupIsEnqueuedThenUpdatedThenCancelled() {
        val manager = manager()
        val name = "test-periodic-backup"
        fun request(hours: Long) = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(10, TimeUnit.MINUTES)
            .build()

        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request(6)).result.get(20, TimeUnit.SECONDS)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states(manager, name))

        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request(12)).result.get(20, TimeUnit.SECONDS)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states(manager, name))

        manager.cancelUniqueWork(name).result.get(20, TimeUnit.SECONDS)
        assertTrue(states(manager, name).all { it == WorkInfo.State.CANCELLED })
    }
}
