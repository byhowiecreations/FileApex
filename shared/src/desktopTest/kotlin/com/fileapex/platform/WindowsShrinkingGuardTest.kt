package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsShrinkingGuardTest {
    private fun repoFile(path: String): File =
        File(path).takeIf { it.isFile } ?: File("..").resolve(path)

    @Test
    fun codeShrinkingIsOnForWindows() {
        val build = repoFile("composeApp/build.gradle.kts").readText()
        val block = build.substringAfter("buildTypes.release.proguard {").substringBefore("nativeDistributions")
        assertFalse("Windows must not be excluded from ProGuard", block.contains("isWindowsHost()"))
        assertTrue(block.contains("isLinuxHost()"))
    }

    @Test
    fun theKeepRulesCoverJnaBindings() {
        val rules = repoFile("composeApp/proguard-desktop.pro").readText()
        assertTrue(rules.contains("-keep class com.sun.jna.** { *; }"))
        assertTrue(rules.contains("extends com.sun.jna.Library"))
        assertTrue(rules.contains("extends com.sun.jna.Structure"))
        assertTrue(rules.contains("extends com.sun.jna.Callback"))
    }

    @Test
    fun enumsKeepTheirValuesMethodSoJmdnsAndEnumMapStillWork() {
        val rules = repoFile("composeApp/proguard-desktop.pro").readText()
        assertTrue(rules.contains("-keepclassmembers enum * {"))
        assertTrue(rules.contains("values()"))
    }

    @Test
    fun signaturesAreStrippedFromJarsProGuardRewrites() {
        val build = repoFile("composeApp/build.gradle.kts").readText()
        assertTrue(build.contains("proguardReleaseJars"))
        assertTrue(build.contains("stripJarSignatures"))
    }
}
