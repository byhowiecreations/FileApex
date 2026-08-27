package com.fileapex.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleLinkRestorePolicyTest {

    @Test
    fun probesOncePerInstallWhenSignedOut() {
        assertTrue(
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
    fun alwaysProbesWhenLinkedFlagIsSet() {
        assertTrue(
            GoogleLinkRestorePolicy.shouldProbeRestoreKey(
                linkedFlag = true,
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
    fun silentGoogleOnlyWhenRestoreKeyOrBackedUpEmailExists() {
        assertFalse(
            GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                restoreKeyPresent = false,
                backedUpEmail = ""
            )
        )
        assertTrue(
            GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                restoreKeyPresent = true,
                backedUpEmail = ""
            )
        )
        assertTrue(
            GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                restoreKeyPresent = false,
                backedUpEmail = "user@example.com"
            )
        )
    }
}
