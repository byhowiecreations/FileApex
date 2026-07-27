package com.fileapex.platform

import com.fileapex.data.settings.androidAppContextOrNull

actual object BootLaunchPreference {
    actual fun onPreferenceChanged(enabled: Boolean) {
        val context = androidAppContextOrNull() ?: return
        ServiceWatchdogScheduler.syncAutoLaunchOnRebootMirror(context, enabled)
    }

    actual fun syncFromSettings() {
        val context = androidAppContextOrNull() ?: return
        ServiceWatchdogScheduler.syncAutoLaunchOnRebootFromSettings(context)
    }
}
