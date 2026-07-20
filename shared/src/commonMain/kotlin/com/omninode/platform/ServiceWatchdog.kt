package com.omninode.platform

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

    /** Cancel pending watchdog alarms. */
    fun cancelAlarm()

    /** Apply preference toggle (schedule or cancel). */
    fun onPreferenceChanged(enabled: Boolean)
}
