package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Mac build signs with the stable identity when one is configured; an ad-hoc signature changes every build and drops the user's Full Disk Access grant. */
class MacSigningGuardTest {
    private val build = File("../composeApp/build.gradle.kts").readText()

    @Test fun noCodesignCallHardcodesTheAdHocIdentity() {
        assertEquals("every codesign call must take its identity from macSignArgs()", 0, Regex(""""--sign",\s*"-"""").findAll(build).count())
        assertTrue(build.contains("fun macSignArgs()"))
    }

    @Test fun aConfiguredIdentityWithoutItsKeychainFailsTheBuildInsteadOfFallingBack() {
        val body = build.substringAfter("fun macSignArgs()").substringBefore("\n}\n")
        assertTrue(body.contains("FILEAPEX_MAC_SIGN_IDENTITY"))
        assertTrue(body.contains("check(") && body.contains("keychain"))
    }

    @Test fun aFinderFileDoesNotMakeTheTailscaleLibraryRebuild() {
        val inputs = build.substringAfter("val tsnetLibraryInputs").substringBefore("\n}\n")
        assertTrue("a touched .DS_Store must not count as a source change", inputs.contains(".DS_Store"))
    }

    @Test fun aBuildWithNoIdentityFailsUnlessUnsignedWasAskedFor() {
        val body = build.substringAfter("fun macSignArgs()").substringBefore("\n}\n")
        assertTrue(body.contains("FILEAPEX_MAC_UNSIGNED"))
        assertTrue("no identity and no -unsigned must stop the build", Regex("""check\([^\n]*UNSIGNED""").containsMatchIn(body))
    }

    @Test fun theKeychainMustHoldTheCertificatePinnedInTheRepository() {
        val body = build.substringAfter("fun macSignArgs()").substringBefore("\n}\n")
        assertTrue(body.contains("signing-identity.sha1"))
        assertTrue(body.contains("find-identity"))
    }

    @Test fun theFinalDmgCheckMountsTheDmgAndRequiresThePinnedIdentity() {
        val check = build.substringAfter("fun Project.verifyDmgSignature", "")
        assertTrue("verifyDmgSignature missing", check.isNotEmpty())
        val body = check.substringBefore("\n}\n")
        assertTrue(body.contains("hdiutil") && body.contains("detach"))
        assertTrue(body.contains("signing-identity.sha1") || body.contains("pinnedSigningHash"))
        assertTrue(Regex("""verifyDmgSignature\(""").findAll(build).count() >= 3)
    }
}
