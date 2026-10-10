package com.fileapex.platform

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.fileapex.domain.backup.BackupStatus
import com.fileapex.i18n.AppI18n
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import com.fileapex.domain.backup.BackupConfig
import com.fileapex.domain.backup.BackupEngine
import java.util.concurrent.TimeUnit

actual fun backupSupported(): Boolean = true

actual fun backupStateDirectory(): String {
    val context = androidAppContextOrNull() ?: error("Android context not ready")
    return java.io.File(context.filesDir, "backup").also { it.mkdirs() }.absolutePath
}

actual object BackupScheduler {
    private const val PERIODIC = "fileapex_folder_backup"
    private const val ONE_SHOT = "fileapex_folder_backup_now"
    private const val FIRST_RUN_DELAY_MINUTES = 15L

    private fun constraints(config: BackupConfig) = Constraints.Builder()
        .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    actual fun apply(config: BackupConfig) {
        val context = androidAppContextOrNull() ?: return
        val manager = WorkManager.getInstance(context)
        if (!config.isRunnable) {
            manager.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(config.intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints(config))
            // Leaves time to finish setup (more folders, another destination) before the first run;
            // "Back up now" starts one immediately.
            .setInitialDelay(FIRST_RUN_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        // UPDATE so a changed interval or Wi-Fi rule takes effect without waiting out the old schedule.
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    actual fun runNow(config: BackupConfig) {
        val context = androidAppContextOrNull() ?: return
        if (!config.isRunnable) return
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(config))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result = runCatching {
        // Running as a foreground service lifts the roughly ten-minute worker limit, so a first backup
        // finishes in one go. Android may refuse that for a run started in the background; that run
        // stops a little early instead, saves its progress, and the rest follows.
        val foreground = runCatching { setForeground(foregroundInfo(null)) }.isSuccess
        val result = coroutineScope {
            val progress = launch {
                var lastShown = 0L
                BackupEngine.status.collect { status ->
                    val now = System.currentTimeMillis()
                    if (foreground && status is BackupStatus.Running && now - lastShown > NOTIFICATION_INTERVAL_MS) {
                        lastShown = now
                        runCatching { setForeground(foregroundInfo(status)) }
                    }
                }
            }
            try {
                BackupEngine.run(timeBudgetMs = if (foreground) Long.MAX_VALUE else RUN_BUDGET_MS)
            } finally {
                progress.cancel()
            }
        }
        if (result.hasMore || result.unavailable) Result.retry() else Result.success()
    }.getOrElse { error ->
        if (error is kotlin.coroutines.cancellation.CancellationException) throw error
        Result.retry()
    }

    private fun foregroundInfo(progress: BackupStatus.Running?): ForegroundInfo {
        AndroidNotificationChannels.ensureBackupChannel(applicationContext)
        val builder = NotificationCompat.Builder(applicationContext, AndroidNotificationChannels.FOLDER_BACKUP)
            .setSmallIcon(AndroidNotificationChannels.smallIcon)
            .setContentTitle(AppI18n.t("backup_notification_title"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (progress != null && progress.total > 0) {
            builder.setContentText(AppI18n.t("backup_running", progress.done + 1, progress.total))
            builder.setProgress(progress.total, progress.done, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val RUN_BUDGET_MS = 8 * 60 * 1000L
        const val NOTIFICATION_INTERVAL_MS = 1500L
        const val NOTIFICATION_ID = 7312
    }
}
