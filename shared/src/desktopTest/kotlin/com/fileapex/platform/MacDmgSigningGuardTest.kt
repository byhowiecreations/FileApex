package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DMG people download must carry the same stable signature as the app in current/: jpackage signs ad-hoc, so each DMG task swaps the signed app in after the last signing step. */
class MacDmgSigningGuardTest {
    private val build = File("../composeApp/build.gradle.kts").readText()

    private fun body(task: String) = build.substringAfter("tasks.register(\"$task\")").substringBefore("\ntasks.register(")

    @Test fun theSiliconDmgGetsTheSignedAppAfterTheFinalSigningAndBeforeItIsShipped() {
        val b = body("packageSiliconDmg")
        val lastSign = b.lastIndexOf("finalizeMacAppSignature(stagingApp)")
        val swap = b.indexOf("replaceAppInDmg(")
        val ship = b.indexOf("destName = \"FileApex-v\$fileapexVersionName-Silicon.dmg\"")
        assertTrue("swap must exist", swap >= 0)
        assertTrue("swap must follow the last signing step", swap > lastSign)
        assertTrue("swap must come before the DMG is moved to current/", swap < ship)
    }

    @Test fun theIntelDmgGetsTheSignedAppAfterSigningAndBeforeItIsShipped() {
        val b = body("packageIntelDmg")
        val sign = b.indexOf("finalizeMacAppSignature(")
        val swap = b.indexOf("replaceAppInDmg(")
        val ship = b.indexOf("moveToCurrent(")
        assertTrue("the Intel app must be re-signed", sign >= 0)
        assertTrue("swap must follow the signing", swap > sign)
        assertTrue("swap must come before the DMG is moved to current/", swap in 0 until ship)
    }
}
