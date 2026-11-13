package com.fileapex.platform

/**
 * Policy for requesting Android permissions at point of use instead of blocking first launch.
 */
object OnDemandPermissionPolicy {
    const val blockLaunchWithPermissionScreen: Boolean = false
    const val STORAGE_STEP_ID: String = "manage_external_storage"

    fun needsStoragePromptBeforeLocalFiles(isAndroid: Boolean, hasStoragePermission: Boolean): Boolean =
        isAndroid && !hasStoragePermission

    fun shouldOfferPostPairSetup(pairedCount: Int, steps: List<OnboardingPermissionStep>): Boolean =
        pairedCount > 0 && steps.any { !it.granted && it.id != STORAGE_STEP_ID }
}
