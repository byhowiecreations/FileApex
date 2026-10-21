package com.fileapex.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkUpdateVerdictTest {
    private fun judge(
        archivePackage: String? = "com.fileapex",
        archiveVersion: Long = 211,
        installedVersion: Long = 210,
        signaturesMatch: Boolean? = true
    ) = ApkUpdateVerdict.judge(archivePackage, "com.fileapex", archiveVersion, installedVersion, signaturesMatch)

    @Test
    fun newerBuildFromTheSameAppIsAccepted() {
        assertEquals(ApkUpdateVerdict.ACCEPT, judge())
        assertEquals(ApkUpdateVerdict.ACCEPT, judge(archiveVersion = 210))
        assertEquals(ApkUpdateVerdict.ACCEPT, judge(signaturesMatch = null))
    }

    @Test
    fun anotherPackageIsRejected() {
        assertEquals(ApkUpdateVerdict.WRONG_PACKAGE, judge(archivePackage = "com.other"))
        assertEquals(ApkUpdateVerdict.WRONG_PACKAGE, judge(archivePackage = null))
    }

    @Test
    fun differentSignerAndOlderVersionAreRejected() {
        assertEquals(ApkUpdateVerdict.SIGNATURE_MISMATCH, judge(signaturesMatch = false))
        assertEquals(ApkUpdateVerdict.OLDER_VERSION, judge(archiveVersion = 209))
    }
}
