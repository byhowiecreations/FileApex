package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The Mac signing modes of scripts/build-fileapex.sh, run against temp folders and a temp keychain, never the real ones. */
class BuildScriptSigningTest {
    private val script = File("../scripts/build-fileapex.sh").absolutePath

    private class Result(val code: Int, val out: String)

    private fun run(call: String, env: Map<String, String>, home: File? = null): Result {
        val pb = ProcessBuilder("bash", "-c", "FILEAPEX_SOURCE_ONLY=1 source '$script'; $call")
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        if (home != null) pb.environment()["HOME"] = home.absolutePath
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("timed out: $call", process.waitFor(120, TimeUnit.SECONDS))
        return Result(process.exitValue(), out)
    }

    private fun temp() = Files.createTempDirectory("fileapex-sign").toFile().also { it.deleteOnExit() }

    private fun envFor(dir: File, pin: File?, envFile: File) = buildMap {
        put("FILEAPEX_SIGNING_DIR", dir.absolutePath)
        put("FILEAPEX_PIN_FILE", (pin ?: File(dir, "none.sha1")).absolutePath)
        put("FILEAPEX_ENV_FILE", envFile.absolutePath)
        put("FILEAPEX_DIR", dir.absolutePath)
    }

    @Test fun setupRefusesWhenAnIdentityIsAlreadyPinnedAndNoBackupIsGiven() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val dir = temp(); val pin = File(dir, "pin.sha1").also { it.writeText("AAAA\n") }
        val r = run("setup_signing", envFor(dir, pin, File(dir, "env")))
        assertTrue(r.out, r.code != 0)
        assertTrue(r.out, r.out.contains("-restore-signing"))
        assertFalse(File(dir, "fileapex-signing.keychain-db").exists())
    }

    @Test fun setupCreatesOneIdentityPinsItAndRefusesASecondTime() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val dir = temp(); val pin = File(dir, "pin.sha1"); val envFile = File(dir, "env")
        val first = run("setup_signing", envFor(dir, pin, envFile))
        assertEquals(first.out, 0, first.code)
        assertTrue(File(dir, "fileapex-signing.keychain-db").isFile)
        assertTrue(File(dir, "fileapex-signing.p12").isFile)
        assertTrue(pin.readText().trim().matches(Regex("[0-9A-F]{40}")))
        val text = envFile.readText()
        assertEquals(1, Regex("FILEAPEX_MAC_SIGN_IDENTITY").findAll(text).count())
        assertEquals(1, Regex("FILEAPEX_MAC_SIGN_KEYCHAIN").findAll(text).count())
        val again = run("setup_signing", envFor(dir, pin, envFile))
        assertTrue(again.out, again.code != 0)
        assertEquals(pin.readText().trim(), pin.readText().trim())
        assertEquals(1, Regex("FILEAPEX_MAC_SIGN_IDENTITY").findAll(envFile.readText()).count())
    }

    @Test fun restoreAcceptsTheBackupOfThePinnedIdentityAndRefusesAnyOther() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val src = temp(); val pin = File(src, "pin.sha1")
        assertEquals(0, run("setup_signing", envFor(src, pin, File(src, "env"))).code)
        val fresh = temp(); val envFile = File(fresh, "env")
        File(src, "fileapex-signing.p12").copyTo(File(fresh, "backup.p12"))
        File(src, "p12-password.txt").copyTo(File(fresh, "p12-password.txt"))
        val good = run("restore_signing '${File(fresh, "backup.p12").absolutePath}'", envFor(fresh, pin, envFile))
        assertEquals(good.out, 0, good.code)
        assertTrue(File(fresh, "fileapex-signing.keychain-db").isFile)
        val wrongPin = File(fresh, "other.sha1").also { it.writeText("0".repeat(40) + "\n") }
        val other = temp()
        File(src, "fileapex-signing.p12").copyTo(File(other, "backup.p12"))
        File(src, "p12-password.txt").copyTo(File(other, "p12-password.txt"))
        val bad = run("restore_signing '${File(other, "backup.p12").absolutePath}'", envFor(other, wrongPin, File(other, "env")))
        assertTrue(bad.out, bad.code != 0)
        assertFalse(File(other, "fileapex-signing.keychain-db").exists())
    }

    @Test fun doctorNamesEveryMissingToolWithAFixAndNeverPrintsSecrets() {
        val home = temp(); val dir = temp()
        val env = envFor(dir, null, File(dir, "env")) + mapOf("KEYSTORE_PASSWORD" to "sekret-value-123", "KEY_PASSWORD" to "sekret-value-456")
        val r = run("doctor", env, home)
        assertTrue(r.out, r.code != 0)
        listOf("JDK 21 (Silicon)", "JDK 21 (Intel)", "Android SDK", "Go", "Rust", "Rosetta", "Signing identity").forEach {
            assertTrue("$it missing from report:\n${r.out}", r.out.contains(it))
        }
        assertTrue(r.out, r.out.contains("MISSING"))
        assertTrue(r.out, r.out.contains("Fix:"))
        assertFalse(r.out.contains("sekret-value"))
    }

    @Test fun macBuildsNeedAnIdentityOrTheUnsignedFlag() {
        val text = File(script).readText()
        assertTrue(text.contains("-unsigned"))
        assertTrue(text.contains("FILEAPEX_MAC_UNSIGNED"))
    }
}
