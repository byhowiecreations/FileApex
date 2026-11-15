package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android release runtime leaves out kotlin-reflect for the same reason the desktop one does (see DesktopLibraryTrimGuardTest). */
class AndroidLibraryTrimGuardTest {
    private fun repoFile(path: String): File = File(path).takeIf { it.isFile } ?: File("..").resolve(path)

    @Test
    fun kotlinReflectIsLeftOutOfTheAndroidReleaseRuntime() {
        val build = repoFile("composeApp/build.gradle.kts").readText()
        assertTrue(build.contains("endsWith(\"ReleaseRuntimeClasspath\")"))
        assertTrue(build.contains("exclude(group = \"org.jetbrains.kotlin\", module = \"kotlin-reflect\")"))
    }
}
