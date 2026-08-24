package com.fileapex.platform

/**
 * One user-grant step shown during first-run onboarding (Android).
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
