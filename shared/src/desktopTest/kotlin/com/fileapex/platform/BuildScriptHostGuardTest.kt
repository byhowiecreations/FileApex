package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** A remote build host that cannot check out the pushed commit must fail the build, never build whatever it had and ship it as new. */
class BuildScriptHostGuardTest {
    private val script = File("../scripts/build-fileapex.sh").readText()
    private val windows = script.substringAfter("build_full.ps1\" <<'EOF'").substringBefore("\nEOF")
    private val linux = script.substringAfter("build_full_linux.sh\" <<'EOF'").substringBefore("\nEOF")

    @Test fun theWindowsHostStopsWhenFetchOrCheckoutFails() {
        val beforeBuild = windows.substringBefore("gradlew.bat")
        assertTrue(beforeBuild.indexOf("git fetch origin") < beforeBuild.indexOf("git checkout"))
        assertTrue("each git step must be checked", Regex("""\${'$'}LASTEXITCODE -ne 0""").findAll(beforeBuild).count() >= 2)
    }

    @Test fun bothHostsBuildExactlyTheCommitThatWasPushed() {
        assertTrue(windows.contains("rev-parse HEAD") && windows.contains("Expected"))
        assertTrue(linux.contains("rev-parse HEAD") && linux.contains("EXPECTED"))
        assertTrue(script.contains("-Expected"))
    }

    @Test fun theWindowsHostClearsItsOldInstallerBeforeBuilding() {
        assertTrue(windows.substringBefore("gradlew.bat").contains("Remove-Item"))
    }

    @Test fun aFailedWindowsBuildStopsTheRun() {
        val call = script.substringAfter("-File build_full.ps1 -Branch").substringBefore("scp -q")
        assertTrue("the ssh call must return from the function when the build fails", call.contains("|| {") && call.contains("return 1"))
    }
}
