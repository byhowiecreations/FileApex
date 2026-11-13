package com.fileapex.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class AndroidOnDemandPermissionsTest {

    @Test
    fun startupDoesNotBlockAppLaunchOnPermissions() {
        assertFalse(OnDemandPermissionPolicy.blockLaunchWithPermissionScreen)
    }

    @Test
    fun postPairBackgroundBannerOnlyAppearsAfterFirstDeviceWhenStepsRemain() {
        val storageOnlyMissing = listOf(
            OnboardingPermissionStep(
                id = AndroidOnboardingPermissions.ID_MANAGE_EXTERNAL_STORAGE,
                titleKey = "onboard_perm_storage",
                reasonKey = "onboard_storage_reason",
                deniedHintKey = "onboard_storage_denied",
                granted = false,
                isOptional = true
            )
        )
        val notifyMissing = listOf(
            OnboardingPermissionStep(
                id = AndroidOnboardingPermissions.ID_POST_NOTIFICATIONS,
                titleKey = "onboard_perm_notify",
                reasonKey = "onboard_notify_reason",
                deniedHintKey = "onboard_notify_denied",
                granted = false,
                isOptional = true
            )
        )
        assertFalse(OnDemandPermissionPolicy.shouldOfferPostPairSetup(pairedCount = 0, steps = notifyMissing))
        assertFalse(OnDemandPermissionPolicy.shouldOfferPostPairSetup(pairedCount = 1, steps = storageOnlyMissing))
        assertTrue(OnDemandPermissionPolicy.shouldOfferPostPairSetup(pairedCount = 1, steps = notifyMissing))
    }

    @Test
    fun storageReasonDoesNotFalselyClaimTransfersRequireAllFilesAccess() {
        val xml = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("i18n/en.xml")!!
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        assertFalse(xml.contains("cannot browse storage or transfer your files without this permission"))
        assertFalse(xml.contains("All files access is required to browse directories"))
    }
}
