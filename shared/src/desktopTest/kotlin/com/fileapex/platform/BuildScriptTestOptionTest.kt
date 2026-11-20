package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds never run the unit tests, so `-test` is a separate option: the desktop and Android suites plus the compile of every
 * target, with no packaging, no signing and nothing moved into current/.
 */
class BuildScriptTestOptionTest {
    private val projectRoot = File("..").canonicalFile
    private val scriptFile = File(projectRoot, "scripts/build-fileapex.sh")
    private val script = scriptFile.readText()

    private fun functionBody(name: String): String {
        val start = script.indexOf("\n$name() {")
        assertTrue("$name() is missing", start >= 0)
        return script.substring(start).substringAfter("{\n").substringBefore("\n}\n")
    }

    private fun help(): String {
        val home = Files.createTempDirectory("fileapex-test-option-home").toFile().also { it.deleteOnExit() }
        val pb = ProcessBuilder("bash", scriptFile.absolutePath, "-h").redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(
            mapOf(
                "PATH" to "/usr/bin:/bin",
                "HOME" to home.absolutePath,
                "FILEAPEX_DIR" to projectRoot.absolutePath,
                "FILEAPEX_CONFIG" to File(home, "config.env").absolutePath,
            )
        )
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("timed out", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        return out
    }

    @Test fun theHelpListsTheTestOption() {
        assertTrue(help().lines().any { it.trim().startsWith("-test") })
    }

    @Test fun theCommandLineDispatchesTestToRunTests() {
        val branch = script.substringAfter("-test|test)").substringBefore(";;")
        assertTrue(branch.contains("run_tests"))
    }

    @Test fun theMenuOffersTheTests() {
        assertTrue(script.substringAfter("main_menu() {").contains("run_tests"))
    }

    @Test fun theTestRunCoversBothSuitesAndEveryTarget() {
        val body = functionBody("run_tests")
        for (task in listOf(":shared:desktopTest", ":shared:testDebugUnitTest", ":composeApp:compileKotlinDesktop", ":composeApp:compileGithubDebugKotlinAndroid", ":composeApp:compilePlayDebugKotlinAndroid")) {
            assertTrue("run_tests lacks $task", body.contains(task))
        }
    }

    @Test fun theFdroidCompileRunsAloneBecauseItCannotShareAnInvocation() {
        val body = functionBody("run_tests")
        val fdroidLine = body.lines().single { it.contains("compileFdroidDebugKotlinAndroid") }
        assertFalse(fdroidLine.contains(":shared:desktopTest"))
        assertFalse(fdroidLine.contains("compilePlayDebugKotlinAndroid"))
    }

    @Test fun theTestRunNeverPackagesSignsOrTouchesCurrent() {
        val body = functionBody("run_tests")
        for (word in listOf("execute_build", "cleanup_build_dir", "show_current", "salvage", "NEEDS_SIGNING", "signing.local.env", "package", "ship", "bundle", "CURRENT_DIR")) {
            assertFalse("run_tests must not use $word", body.contains(word))
        }
    }

    @Test fun theTestRunStopsAtTheFirstFailingStep() {
        val body = functionBody("run_tests")
        assertTrue(body.contains("|| return") || body.contains("|| exit"))
    }
}
