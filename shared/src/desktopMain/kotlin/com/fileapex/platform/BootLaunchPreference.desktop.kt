package com.fileapex.platform

actual object BootLaunchPreference {
    actual fun onPreferenceChanged(enabled: Boolean) = Unit
    actual fun syncFromSettings() = Unit
}
