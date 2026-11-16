package com.fileapex.platform

/**
 * Android AlarmManager watchdog hooks; no-op on desktop.
 */
expect object ServiceWatchdog {
    /** User intentionally stopped the share-server FGS (do not schedule watchdog). */
    fun markCleanStop()

    /** Schedule the next watchdog alarm when the preference is enabled. */
    fun scheduleNextAlarmIfEnabled()

    /** Schedule a near-term watchdog retry (sticky restart / blocked background FGS). */
    fun scheduleImmediateAlarmIfEnabled()

    fun cancelAlarm()

    fun onPreferenceChanged(enabled: Boolean)
}
