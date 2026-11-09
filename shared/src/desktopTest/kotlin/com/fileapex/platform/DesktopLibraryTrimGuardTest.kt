package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kotlin-reflect is only reached through three Ktor classes that load modules by name from a config file or reload code in
 * development; the app starts its server with an explicit module. The bundled SQLite jar carries natives for four systems
 * and an app uses one.
 */
class DesktopLibraryTrimGuardTest {
    private fun repoFile(path: String): File = File(path).takeIf { it.isFile } ?: File("..").resolve(path)

    @Test
    fun kotlinReflectIsLeftOutOfTheDesktopRuntime() {
        val slim = repoFile("composeApp/desktop-slim-jars.gradle.kts").readText()
        assertTrue(slim.contains("exclude(group = \"org.jetbrains.kotlin\", module = \"kotlin-reflect\")"))
    }

    @Test
    fun theSqliteJarKeepsOnlyTheNativeOfTheBuildHost() {
        val slim = repoFile("composeApp/desktop-slim-jars.gradle.kts").readText()
        assertTrue(slim.contains("slimSqliteJar"))
        assertTrue(slim.contains("exclude(group = \"androidx.sqlite\", module = \"sqlite-bundled-jvm\")"))
        val build = repoFile("composeApp/build.gradle.kts").readText()
        assertTrue(build.contains("tasks.named(\"slimSqliteJar\")"))
    }

    @Test
    fun theIntelMacGetsTheSqliteNativeThatVersion270DroppedAndABuildFailsWithoutAHostNative() {
        val slim = repoFile("composeApp/desktop-slim-jars.gradle.kts").readText()
        assertTrue("androidx.sqlite 2.7.0 and 2.7.1 ship no natives/osx_x64", slim.contains("sqlite-bundled-jvm:2.6.2"))
        assertTrue(slim.contains("natives/osx_x64/"))
        assertTrue("the build must fail when the jar has no native for the machine it is built for", slim.contains("has no SQLite native for"))
    }
}
