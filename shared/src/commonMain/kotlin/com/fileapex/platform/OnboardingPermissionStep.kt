package com.fileapex.platform

/**
 * One user-grant step shown during first-run onboarding (Android).
 *
 * [permissionName] is the manifest permission or platform label shown to the user.
 */
data class OnboardingPermissionStep(
    val id: String,
    val permissionName: String,
    val reason: String,
    val deniedHint: String,
    val granted: Boolean
)
