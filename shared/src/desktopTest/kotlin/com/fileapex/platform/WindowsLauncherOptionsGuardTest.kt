package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measured on the Windows PC: these class-data options made every start slower or stopped it, so they stay out until a safe way exists. */
class WindowsLauncherOptionsGuardTest {
    private fun repoFile(path: String): File = File(path).takeIf { it.isFile } ?: File("..").resolve(path)

    private val build get() = repoFile("composeApp/build.gradle.kts").readText()

    @Test
    fun noClassDataArchiveOptionIsInTheLauncher() {
        assertFalse("AutoCreateSharedArchive made every start 7.8 s instead of 2.0 s until a clean exit wrote the archive", build.contains("AutoCreateSharedArchive"))
        assertFalse("RecordDynamicDumpInfo stops the JVM when the named archive does not exist", build.contains("RecordDynamicDumpInfo"))
        assertFalse(build.contains("-XX:SharedArchiveFile"))
    }

    @Test
    fun theBuildDoesNotRunTheAppImageToMakeAnArchive() {
        assertFalse(build.contains("-Xshare:dump"))
    }

    @Test
    fun theLauncherKeepsItsMeasuredOptions() {
        assertTrue(build.contains("-Dsun.java2d.d3d=false"))
    }
}
