package com.fileapex.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleLinkRestorePolicyTest {

    @Test
    fun probesOnlyWhenLinkedAndNotYetProbed() {
        assertTrue(
            GoogleLinkRestorePolicy.shouldProbeRestoreKey(
                linkedFlag = true,
                alreadyProbedThisInstall = false
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldProbeRestoreKey(
                linkedFlag = true,
                alreadyProbedThisInstall = true
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldProbeRestoreKey(
                linkedFlag = false,
                alreadyProbedThisInstall = false
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldProbeRestoreKey(
                linkedFlag = false,
                alreadyProbedThisInstall = true
            )
        )
    }

    @Test
    fun clearsStaleLinkedFlagWithoutSessionOrRestoreKey() {
        assertTrue(
            GoogleLinkRestorePolicy.shouldClearLinkedFlag(
                linkedFlag = true,
                hasFirebaseSession = false,
                restoredIdToken = false
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldClearLinkedFlag(
                linkedFlag = true,
                hasFirebaseSession = true,
                restoredIdToken = false
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldClearLinkedFlag(
                linkedFlag = true,
                hasFirebaseSession = false,
                restoredIdToken = true
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldClearLinkedFlag(
                linkedFlag = false,
                hasFirebaseSession = false,
                restoredIdToken = false
            )
        )
    }

    @Test
    fun silentGoogleOnlyWhenLinkedWithConfiguredEmail() {
        assertFalse(
            GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                linkedFlag = false,
                configuredEmail = ""
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                linkedFlag = false,
                configuredEmail = "user@example.com"
            )
        )
        assertFalse(
            GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                linkedFlag = true,
                configuredEmail = ""
            )
        )
        assertTrue(
            GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                linkedFlag = true,
                configuredEmail = "user@example.com"
            )
        )
    }
}
