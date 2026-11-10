package com.fileapex.cloud

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.20.10: without the legacy Firestore rules clause a plain Google session can read and write nothing, so a device
 * enrols (gets its device session) before its first Firestore call, and a pairing that enrols it opens the cloud.
 * Plan: docs/planning/per-device-firebase-identity-plan.md, section "Blocker found 2026-11-10".
 */
class EnrolBeforeCloudGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val coordinator = File(root, "shared/src/commonMain/kotlin/com/fileapex/cloud/GoogleLinkCoordinator.kt").readText()
    private val identity = File(root, "shared/src/commonMain/kotlin/com/fileapex/cloud/device/DeviceIdentity.kt").readText()

    private fun body(of: String): String {
        val start = coordinator.indexOf("fun $of(")
        assertTrue("$of not found", start >= 0)
        val next = coordinator.indexOf("\n    private fun ", start + 1).let { if (it < 0) Int.MAX_VALUE else it }
        val nextSuspend = coordinator.indexOf("\n    private suspend fun ", start + 1).let { if (it < 0) Int.MAX_VALUE else it }
        val nextPublic = coordinator.indexOf("\n    suspend fun ", start + 1).let { if (it < 0) Int.MAX_VALUE else it }
        return coordinator.substring(start, minOf(next, nextSuspend, nextPublic, coordinator.length))
    }

    private fun assertBefore(function: String, first: String, then: String) {
        val text = body(function)
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertTrue("$function does not call $first", a >= 0)
        assertTrue("$function does not call $then", b >= 0)
        assertTrue("$function calls $then before $first", a < b)
    }

    @Test
    fun linkingEnrolsBeforeTheRecordIsWritten() = assertBefore("linkWithGoogleIdToken", "DeviceIdentity.ensureEnrolled(", "registerSelf(")

    @Test
    fun restoringEnrolsBeforePresenceAndTheListener() {
        assertBefore("restoreSessionAndListen", "DeviceIdentity.ensureEnrolled(", "patchSelfPresence(")
        assertBefore("restoreSessionAndListen", "DeviceIdentity.ensureEnrolled(", "startCloudSessionLocked(")
    }

    @Test
    fun theSessionNoLongerEnrolsAfterItsFirstWrite() =
        assertFalse("startCloudSessionLocked still enrols after writing", body("startCloudSessionLocked").contains("ensureEnrolled"))

    @Test
    fun aPairingThatEnrolsThisDeviceOpensTheCloud() {
        val onPaired = identity.substring(identity.indexOf("fun onPaired("), identity.indexOf("private val kickWatch"))
        assertTrue("onPaired does not open the cloud after enrolling", onPaired.contains("GoogleLinkCoordinator.refreshCloudRegistry()"))
    }

    @Test
    fun aWaitingDeviceRechecksAfterAPairingInsteadOfTryingOnce() {
        val onPaired = identity.substring(identity.indexOf("fun onPaired("), identity.indexOf("private val kickWatch"))
        assertTrue("onPaired does not hand the wait to the approval rechecks", onPaired.contains("approvalWatch.run()"))
        assertFalse("onPaired still retries once by itself", onPaired.contains("PAIRING_RETRY_MS"))
    }

    @Test
    fun startingOverReplacesTheOldSetupBeforeThisDeviceEnrols() =
        assertBefore("linkWithGoogleIdToken", "DeviceIdentity.startOver(", "DeviceIdentity.ensureEnrolled(")

    private val wiring = File(root, "shared/src/commonMain/kotlin/com/fileapex/di/MembershipWiring.kt").readText()

    @Test
    fun onceTheChainIsLoadedTheDeviceEnrolsAndUploadsAgain() {
        val start = wiring.substring(wiring.indexOf("fun start("))
        val prepared = start.indexOf("running.prepare()")
        val ready = start.indexOf("DeviceIdentity.onChainReady()")
        assertTrue("the schedule does not tell the identity code that the chain is loaded", ready > prepared && prepared >= 0)
    }

    @Test
    fun theRelaysAnswersAreLoggedWithoutIds() {
        for (what in listOf("enrol", "chain upload", "start over")) {
            assertTrue("no log line for $what", identity.contains("\"DeviceIdentity: $what "))
        }
    }
}

