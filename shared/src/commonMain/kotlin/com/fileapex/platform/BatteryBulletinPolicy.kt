package com.fileapex.platform

/**
 * Low-battery bulletin rules. Android OEMs (Motorola, some Honor) withhold
 * [android.content.Intent.ACTION_BATTERY_LOW] from stopped apps, so callers must also
 * evaluate the current level on process/service start and on the keep-alive job.
 *
 * No dedicated JobScheduler wake above [STEP_DOWN_PERCENT] — FGS / FCM / UI / boot /
 * freeze-guard already run [BatteryBulletinCoordinator.onProcessStart].
 */
object BatteryBulletinPolicy {
    const val LOW_THRESHOLD_PERCENT = 15
    const val STEP_DOWN_PERCENT = 25

    const val JOB_INTERVAL_AT_25_MS = 30 * 60 * 1000L
    const val JOB_INTERVAL_AT_20_MS = 20 * 60 * 1000L
    const val JOB_INTERVAL_AT_15_MS = 10 * 60 * 1000L

    fun shouldPostAlert(
        levelPercent: Int?,
        charging: Boolean,
        alreadyAlertedThisCycle: Boolean
    ): Boolean {
        if (charging || alreadyAlertedThisCycle) return false
        val level = levelPercent ?: return false
        return level <= LOW_THRESHOLD_PERCENT
    }

    /** Clear the discharge-cycle latch only after a real charge above the threshold. */
    fun shouldClearAlertedCycle(charging: Boolean, levelPercent: Int?): Boolean {
        if (!charging) return false
        val level = levelPercent ?: return true
        return level > LOW_THRESHOLD_PERCENT
    }

    /**
     * One-shot JobScheduler delay, or null to leave the job unscheduled.
     * Step-down starts at 25%: 30 min → 20 min → 10 min at the 15% alert band.
     */
    fun jobIntervalMs(
        levelPercent: Int?,
        charging: Boolean,
        alreadyAlertedThisCycle: Boolean
    ): Long? {
        if (charging || alreadyAlertedThisCycle) return null
        val level = levelPercent ?: return null
        return when {
            level <= LOW_THRESHOLD_PERCENT -> JOB_INTERVAL_AT_15_MS
            level <= 20 -> JOB_INTERVAL_AT_20_MS
            level <= STEP_DOWN_PERCENT -> JOB_INTERVAL_AT_25_MS
            else -> null
        }
    }
}
