package com.fileapex.platform

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android keeps no BouncyCastle provider classes in the release DEX. That is only safe while no code that Android runs
 * asks a JCA provider for a BouncyCastle algorithm: the Ed25519, X25519 and certificate code uses BouncyCastle's own API,
 * and the one provider lookup (the PKCS#12 export for the macOS tray) lives in desktopMain.
 */
class AndroidBouncyCastleGuardTest {
    private fun repoFile(path: String): File = File(path).takeIf { it.exists() } ?: File("..").resolve(path)

    private val androidSourceRoots = listOf(
        "shared/src/commonMain",
        "shared/src/jvmCommon",
        "shared/src/androidMain",
        "composeApp/src/androidMain",
        "composeApp/src/commonMain"
    )

    @Test
    fun noAndroidSourceAsksForTheBouncyCastleProvider() {
        val offenders = androidSourceRoots.map(::repoFile).filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file ->
                val text = file.readText()
                text.contains("BouncyCastleProvider") || text.contains("org.bouncycastle.jce.provider") ||
                    text.contains("org.bouncycastle.jcajce.provider") || Regex("""getInstance\([^)]*"BC"\)""").containsMatchIn(text)
            }
        assertTrue("Android code reaches a BouncyCastle provider: $offenders", offenders.isEmpty())
    }

    @Test
    fun theAndroidKeepRulesDoNotKeepTheBouncyCastleProviders() {
        val rules = repoFile("composeApp/proguard-rules.pro").readText()
        val keeps = rules.lines().filter { it.trim().startsWith("-keep") && it.contains("org.bouncycastle") }
        assertEquals("no BouncyCastle keep rule belongs in the Android rules: $keeps", emptyList<String>(), keeps)
    }

    @Test
    fun theCryptoThatAndroidRunsNeverLoadsAProviderClass() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-Xlog:class+load:stdout", "-cp", System.getProperty("java.class.path"),
            "com.fileapex.platform.AndroidCryptoFlowsKt"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("flows timed out", process.waitFor(120, TimeUnit.SECONDS))
        assertEquals(output.takeLast(2000), 0, process.exitValue())
        assertTrue("the flows did not run", output.contains("FLOWS-OK"))
        assertTrue("sanity: the lightweight API was used", output.contains("org.bouncycastle.crypto.signers.Ed25519Signer"))
        val providers = output.lines().filter { "org.bouncycastle.jce.provider" in it || "org.bouncycastle.jcajce.provider" in it }
        assertFalse("provider classes were loaded: ${providers.take(5)}", providers.isNotEmpty())
    }
}
