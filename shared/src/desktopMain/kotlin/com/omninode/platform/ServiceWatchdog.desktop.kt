package com.omninode.platform

actual object ServiceWatchdog {
    actual fun markCleanStop() = Unit
    actual fun scheduleNextAlarmIfEnabled() = Unit
    actual fun scheduleImmediateAlarmIfEnabled() = Unit
    actual fun cancelAlarm() = Unit
    actual fun onPreferenceChanged(enabled: Boolean) = Unit
}
