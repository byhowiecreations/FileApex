package com.fileapex.platform

import com.fileapex.cloud.FirebaseGatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FirebaseGateTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun kotlinFiles(dir: String) = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun launchStartsFirebaseUnlessThisInstallIsKnownToBeUnlinked() {
        assertTrue("first launch after the update has no mirror yet", FirebaseGatePolicy.shouldInitAtLaunch(null))
        assertTrue(FirebaseGatePolicy.shouldInitAtLaunch("true"))
        assertFalse(FirebaseGatePolicy.shouldInitAtLaunch("false"))
    }

    @Test
    fun theStaleSessionCheckSkipsStartingFirebaseOnlyOnAnInstallKnownToBeUnlinked() {
        assertTrue(FirebaseGatePolicy.canSkipSessionCheck(mirror = "false", firebaseRunning = false))
        assertFalse("first launch after the update still cleans a stale session", FirebaseGatePolicy.canSkipSessionCheck(mirror = null, firebaseRunning = false))
        assertFalse(FirebaseGatePolicy.canSkipSessionCheck(mirror = "true", firebaseRunning = false))
        assertFalse("when it already runs, the real check is free", FirebaseGatePolicy.canSkipSessionCheck(mirror = "false", firebaseRunning = true))
    }

    @Test
    fun onlyARealLinkToUnlinkChangeDeletesThePushToken() {
        assertTrue(FirebaseGatePolicy.shouldDeleteTokenOnChange(previous = "true", linked = false))
        assertFalse(FirebaseGatePolicy.shouldDeleteTokenOnChange(previous = null, linked = false))
        assertFalse(FirebaseGatePolicy.shouldDeleteTokenOnChange(previous = "false", linked = false))
        assertFalse(FirebaseGatePolicy.shouldDeleteTokenOnChange(previous = "true", linked = true))
    }

    @Test
    fun firebaseIsOnlyTouchedInsideTheGoogleFlavorAndAlwaysThroughTheGate() {
        val outside = listOf("shared/src/commonMain", "shared/src/androidMain", "shared/src/androidFdroid", "shared/src/jvmCommon", "composeApp/src/androidMain", "composeApp/src/commonMain")
            .flatMap(::kotlinFiles).filter { "com.google.firebase" in it.readText() }
        assertEquals(emptyList<File>(), outside)
        val offenders = kotlinFiles("shared/src/androidGoogle").filter { it.name != "FirebaseGate.kt" }.flatMap { file ->
            file.readLines().filter { Regex("""Firebase\w*\.getInstance\(\)""").containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" }
        }
        assertEquals("call sites must use FirebaseGate", emptyList<String>(), offenders)
    }

    @Test
    fun theManifestsStopFirebaseStartingAtLaunchAndTheServiceStartsItItself() {
        for (flavor in listOf("github", "play")) {
            val manifest = File(root, "composeApp/src/$flavor/AndroidManifest.xml").readText()
            assertTrue(flavor, manifest.contains("com.google.firebase.provider.FirebaseInitProvider") && manifest.contains("tools:node=\"remove\""))
        }
        val service = File(root, "shared/src/androidGoogle/kotlin/com/fileapex/cloud/FileApexFcmMessagingService.kt").readText()
        assertTrue(service.indexOf("FirebaseGate.ensure") in 0 until service.indexOf("super.onCreate()"))
    }

    @Test
    fun readingTheConfiguredKeysDoesNotStartFirebase() {
        val gate = File(root, "shared/src/androidGoogle/kotlin/com/fileapex/cloud/FirebaseGate.kt").readText()
        val options = gate.substringAfter("fun options()").substringBefore("/** First thing")
        assertTrue(options.contains("FirebaseOptions.fromResource"))
        assertFalse("options() must not call app() or ensure()", options.contains("app()") || options.contains("ensure("))
    }

    @Test
    fun theFdroidFlavorHasNoFirebaseAtAll() {
        val gate = File(root, "shared/src/androidFdroid/kotlin/com/fileapex/cloud/FirebaseGate.kt").readText()
        assertFalse(gate.contains("firebase", ignoreCase = true).and(gate.contains("import com.google")))
    }
}
