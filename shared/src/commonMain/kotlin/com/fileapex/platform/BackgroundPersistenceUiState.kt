package com.fileapex.platform

/**
 * Cross-platform snapshot of Android background persistence checks for Compose UI.
 * Populated from [BackgroundPersistenceGuidance.evaluate] on Android; defaults on desktop.
 */
data class BackgroundPersistenceUiState(
    val batteryOptimizationRestricted: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val unusedAppRestrictionsActive: Boolean = false,
    val oemGuidance: OemBackgroundGuidance? = null
) {
    val persistenceRestricted: Boolean
        get() = batteryOptimizationRestricted || backgroundRestricted
}
