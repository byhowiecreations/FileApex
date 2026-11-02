package com.fileapex.domain.pairing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Removals travel as signed chain records only. Nothing builds, advertises or pulls the old removal lists any more. */
class NoRemovalListGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private val main: List<File> = File(root, "shared/src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/desktopTest/" !in it.path && "/androidUnitTest/" !in it.path }.toList()

    @Test
    fun theFingerprintTheHeaderThePullersAndTheRouteAreGone() {
        val words = listOf("RemovalFingerprint", "removalFingerprint", "removalRecords", "RemovalPuller", "windowedRemovals", "/api/v1/devices/removed")
        val offenders = main.filter { file -> file.readText().let { text -> words.any { it in text } } }.map { it.name }.sorted()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun noPairingIntroductionCarriesRemovalRecords() {
        val text = File(root, "shared/src/commonMain/kotlin/com/fileapex/domain/pairing/PairingCoordinator.kt").readText()
        assertEquals(false, "getTombstoneRecords()" in text)
    }
}
