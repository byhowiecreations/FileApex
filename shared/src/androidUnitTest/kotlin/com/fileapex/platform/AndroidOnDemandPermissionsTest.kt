package com.fileapex.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class AndroidOnDemandPermissionsTest {

    @Test
    fun postPairBackgroundBannerOnlyAppearsAfterFirstDeviceWhenStepsRemain() {
        val storageOnlyMissing = listOf(
            OnboardingPermissionStep(
                id = AndroidOnboardingPermissions.ID_MANAGE_EXTERNAL_STORAGE,
                titleKey = "onboard_perm_storage",
                reasonKey = "onboard_storage_reason",
                deniedHintKey = "onboard_storage_denied",
                granted = false
            )
        )
        val notifyMissing = listOf(
            OnboardingPermissionStep(
                id = AndroidOnboardingPermissions.ID_POST_NOTIFICATIONS,
                titleKey = "onboard_perm_notify",
                reasonKey = "onboard_notify_reason",
                deniedHintKey = "onboard_notify_denied",
                granted = false
            )
        )
        assertFalse(OnDemandPermissionPolicy.shouldOfferPostPairSetup(pairedCount = 0, steps = notifyMissing))
        assertFalse(OnDemandPermissionPolicy.shouldOfferPostPairSetup(pairedCount = 1, steps = storageOnlyMissing))
        assertTrue(OnDemandPermissionPolicy.shouldOfferPostPairSetup(pairedCount = 1, steps = notifyMissing))

        assertFalse(
            OnDemandPermissionPolicy.shouldOfferPostPairSetup(
                pairedCount = 0,
                steps = emptyList(),
                updateCheckPending = true,
                criticalNoticePending = true
            )
        )
        assertTrue(
            OnDemandPermissionPolicy.shouldOfferPostPairSetup(
                pairedCount = 1,
                steps = emptyList(),
                updateCheckPending = true,
                criticalNoticePending = false
            )
        )
        assertTrue(
            OnDemandPermissionPolicy.shouldOfferPostPairSetup(
                pairedCount = 1,
                steps = storageOnlyMissing,
                updateCheckPending = false,
                criticalNoticePending = true
            )
        )
        assertFalse(
            OnDemandPermissionPolicy.shouldOfferPostPairSetup(
                pairedCount = 1,
                steps = storageOnlyMissing,
                updateCheckPending = false,
                criticalNoticePending = false
            )
        )
        assertFalse(
            OnDemandPermissionPolicy.shouldOfferPostPairSetup(
                pairedCount = 1,
                steps = notifyMissing,
                updateCheckPending = true,
                criticalNoticePending = true,
                bannerDismissed = true
            )
        )
    }

    @Test
    fun shareAsLinkAndChooseFilesGateBehindStoragePermission() {
        var requested = 0
        var opened = 0
        OnDemandPermissionPolicy.runWithStoragePermission(
            hasStoragePermission = false,
            onRequestStoragePermission = { requested++ },
            action = { opened++ }
        )
        assertTrue(requested == 1 && opened == 0)

        OnDemandPermissionPolicy.runWithStoragePermission(
            hasStoragePermission = true,
            onRequestStoragePermission = { requested++ },
            action = { opened++ }
        )
        assertTrue(requested == 1 && opened == 1)
    }

    @Test
    fun storageReasonDoesNotFalselyClaimTransfersRequireAllFilesAccess() {
        val xml = java.io.File("src/commonMain/composeResources/files/i18n/en.xml").readText(StandardCharsets.UTF_8)
        assertFalse(xml.contains("cannot browse storage or transfer your files without this permission"))
        assertFalse(xml.contains("All files access is required to browse directories"))
    }
}
