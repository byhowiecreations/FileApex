package com.fileapex.platform

/**
 * OEM skins (MIUI/HyperOS, ColorOS) often drop the accessibility toggle or unbind the
 * service while FileApex still thinks clipboard auto-sync is on.
 */
object ClipboardAccessibilityHealthPolicy {
    const val BIND_GRACE_MS = 8_000L

    fun needsReenablePrompt(
        sharingEnabled: Boolean,
        accessibilitySettingEnabled: Boolean,
        serviceListedEnabled: Boolean,
        serviceBound: Boolean,
        elapsedSinceProcessOrUnbindMs: Long
    ): Boolean {
        if (!sharingEnabled || !accessibilitySettingEnabled) return false
        if (serviceListedEnabled && serviceBound) return false
        if (serviceListedEnabled && !serviceBound) {
            return elapsedSinceProcessOrUnbindMs >= BIND_GRACE_MS
        }
        return true
    }
}
