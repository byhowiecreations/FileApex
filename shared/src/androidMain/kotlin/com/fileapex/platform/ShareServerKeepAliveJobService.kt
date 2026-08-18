package com.fileapex.platform

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * Scheduled only during the 25%→15% battery step-down. Reads BatteryManager and posts if
 * already <=15% because Motorola withholds ACTION_BATTERY_LOW from stopped apps.
 */
class ShareServerKeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "Keep-alive job fired")
        if (ServiceWatchdogScheduler.isWatchdogEnabled(applicationContext)) {
            ShareServerKeepAliveCoordinator.reassertOrRestart(
                applicationContext,
                reason = "job_scheduler"
            )
        }
        val initialized = FileApexAndroidBootstrap.ensureInitialized(applicationContext)
        if (!initialized) {
            ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(applicationContext)
            jobFinished(params, false)
            return false
        }
        BatteryBulletinCoordinator.onProcessStart(applicationContext) {
            ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "ShareServerKeepAliveJob"
    }
}
