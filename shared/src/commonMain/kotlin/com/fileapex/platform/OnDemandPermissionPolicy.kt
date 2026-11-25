package com.fileapex.platform

/**
 * Policy for requesting Android permissions at point of use instead of blocking first launch.
 */
object OnDemandPermissionPolicy {
    const val STORAGE_STEP_ID: String = "manage_external_storage"

    fun needsStoragePromptBeforeLocalFiles(isAndroid: Boolean, hasStoragePermission: Boolean): Boolean =
        isAndroid && !hasStoragePermission

    fun shouldOfferPostPairSetup(
        pairedCount: Int,
        steps: List<OnboardingPermissionStep>,
        updateCheckPending: Boolean = false,
        criticalNoticePending: Boolean = false,
        bannerDismissed: Boolean = false
    ): Boolean =
        pairedCount > 0 && !bannerDismissed && (
            steps.any { !it.granted && it.id != STORAGE_STEP_ID } ||
                updateCheckPending ||
                criticalNoticePending
            )

    fun runWithStoragePermission(
        hasStoragePermission: Boolean,
        onRequestStoragePermission: () -> Unit,
        action: () -> Unit
    ) {
        if (hasStoragePermission) action() else onRequestStoragePermission()
    }
}
