package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A desktop package carries the native libraries of its own platform and architecture and nothing else: the SQLite, Iroh,
 * JNA, JLine and Skia natives come from slim jars built for the build host (`composeApp/desktop-slim-jars.gradle.kts`).
 * No native binary may sit in the shared module's resources, where it would travel into every package.
 */
class DesktopNativesGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val slimScript = File(root, "composeApp/desktop-slim-jars.gradle.kts").readText()
    private val composeBuild = File(root, "composeApp/build.gradle.kts").readText()

    @Test
    fun noNativeBinaryLivesInTheSharedModuleResources() {
        val natives = root.resolve("shared/src").walkTopDown()
            .filter { it.isFile && it.extension in setOf("dylib", "so", "dll", "jnilib") }
            .map { it.relativeTo(root).path }.toList()
        assertTrue("native binaries in shared resources would ship in every package: $natives", natives.isEmpty())
        assertFalse(File(root, "shared/src/desktopMain/resources/natives").exists())
    }

    @Test
    fun everySlimNativeJarIsDefinedPutOnTheRuntimeClasspathAndLeftOutOfTheFullOne() {
        val slimTasks = listOf("slimSqliteJar", "slimIrohJar", "slimJnaJar", "slimJlineJar", "slimJlineNativeJar", "slimSkikoRuntimeJar")
        for (task in slimTasks) {
            assertTrue("$task is not registered", slimScript.contains("""register<Jar>("$task")"""))
            assertTrue("$task is not on the desktop runtime classpath", composeBuild.contains("""tasks.named("$task")"""))
        }
        val excluded = listOf(
            """group = "androidx.sqlite", module = "sqlite-bundled-jvm"""",
            """group = "computer.iroh", module = "iroh"""",
            """group = "net.java.dev.jna", module = "jna"""",
            """group = "org.jline", module = "jline"""",
            """group = "org.jline", module = "jline-native"""",
            """group = "org.jetbrains.skiko"""",
        )
        for (rule in excluded) assertTrue("the full jar is not excluded: $rule", slimScript.contains(rule))
    }

    @Test
    fun everySlimNativeJarChecksItKeepsTheNativeOfTheBuildHost() {
        assertTrue(slimScript.contains("The JNA jar has no native for this machine"))
        assertTrue(slimScript.contains("The JLine jars have no native for this machine"))
        assertTrue(slimScript.contains("The Skia runtime jar has no library for this machine"))
    }
}
