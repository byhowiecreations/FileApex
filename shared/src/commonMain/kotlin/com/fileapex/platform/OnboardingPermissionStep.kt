package com.fileapex.platform

/**
 * One optional permission step (Android), offered after the first pairing and listed in Settings.
 *
 * Title/reason/denied copy is resolved from i18n keys at composition time.
 */
data class OnboardingPermissionStep(
    val id: String,
    val titleKey: String,
    val reasonKey: String,
    val deniedHintKey: String,
    val granted: Boolean
)
