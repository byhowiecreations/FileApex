package com.fileapex.platform

/**
 * Android boot auto-launch mirror for direct boot; no-op on desktop.
 */
expect object BootLaunchPreference {
    /** Mirror toggle into device-protected storage for [android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED]. */
    fun onPreferenceChanged(enabled: Boolean)

    /** Sync mirror from persisted settings at app launch. */
    fun syncFromSettings()
}
