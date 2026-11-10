package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rust and Node work (the drop relay, the browser client, the website and its tests) runs in Docker containers, never as
 * programs compiled on the Mac: every freshly built unsigned program raises an Apple "could not verify" prompt, and one Rust
 * build makes hundreds. The build script must say so, check for Docker instead of Rust, and offer the three container builds.
 */
class BuildScriptDockerToolsTest {
    private val projectRoot = File("..").canonicalFile
    private val scriptFile = File(projectRoot, "scripts/build-fileapex.sh")
    private val script = scriptFile.readText()

    private fun run(vararg args: String): Pair<Int, String> {
        val home = Files.createTempDirectory("fileapex-docker-home").toFile().also { it.deleteOnExit() }
        val config = File(home, "config.env")
        val pb = ProcessBuilder(listOf("bash", scriptFile.absolutePath) + args).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(
            mapOf(
                "PATH" to "/usr/bin:/bin",
                "HOME" to home.absolutePath,
                "FILEAPEX_DIR" to projectRoot.absolutePath,
                "FILEAPEX_CONFIG" to config.absolutePath,
                "CLI_MODE" to "1",
            )
        )
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("timed out: ${args.toList()}", process.waitFor(120, TimeUnit.SECONDS))
        return process.exitValue() to out
    }

    @Test fun theScriptNeverRunsCargoNodeNpmOrWasmBindgenItself() {
        val offenders = script.lines().map { it.trim() }.filter { !it.startsWith("#") }
            .filter { Regex("""^(cargo|rustc|node|npm|npx|wasm-bindgen)\b""").containsMatchIn(it) }
        assertTrue("these lines run native Rust or Node: $offenders", offenders.isEmpty())
    }

    @Test fun theMacDoctorAsksForDockerAndNotForRust() {
        assertTrue(script.contains("""doctor_check "Docker""""))
        assertFalse("a required Rust check sends people to install what caused the prompts", script.contains("""doctor_check "Rust" """))
    }

    @Test fun theOldNativeBuildScriptIsGone() {
        assertFalse(File(projectRoot, "native/dropweb/build.sh").exists())
        assertTrue(File(projectRoot, "native/dropweb/build-docker.sh").exists())
        assertTrue(File(projectRoot, "native/droprelay/build.sh").exists())
    }

    @Test fun theThreeContainerBuildsAreOfferedAndCallTheRightHelpers() {
        val usage = script.substringAfter("usage() {").substringAfter("cat <<EOF").substringBefore("\nEOF")
        for (flag in listOf("-relay", "-wasm", "-site")) assertTrue("usage lists $flag", usage.contains(flag))
        assertTrue(script.contains("native/droprelay/build.sh"))
        assertTrue(script.contains("native/dropweb/build-docker.sh"))
        assertTrue(script.contains("build-worker.mjs"))
        assertTrue("every container build first makes sure Docker is up", script.contains("require_docker"))
    }

    @Test fun theSiteZipLeavesOutFinderJunkAndLandsInCurrent() {
        assertTrue(script.contains("zip -r -X"))
        assertTrue(script.contains("*.DS_Store"))
        assertTrue(script.contains("__MACOSX"))
        assertTrue(script.contains("current/fileapex-site.zip"))
    }

    @Test fun withoutDockerEachContainerBuildStopsAtOnceAndSaysWhy() {
        for (flag in listOf("-relay", "-wasm", "-site")) {
            val (code, out) = run(flag)
            assertTrue("$flag must fail without Docker, output: $out", code != 0)
            assertTrue("$flag must name Docker, output: $out", out.contains("Docker"))
            assertFalse("$flag must not start any build", out.contains("Compiling") || out.contains("Building:"))
        }
    }

    @Test fun theOtherPlatformDoctorsNoLongerPointAtTheRemovedNativeScript() {
        assertFalse(script.contains("used by native/dropweb"))
    }
}
