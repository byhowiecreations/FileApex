package com.fileapex.platform

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * JobScheduler fallback heartbeat when AlarmManager delivery is throttled by OEM power managers.
 */
class ShareServerKeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "Keep-alive job fired")
        ShareServerKeepAliveCoordinator.reassertOrRestart(
            applicationContext,
            reason = "job_scheduler"
        )
        ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "ShareServerKeepAliveJob"
    }
}
