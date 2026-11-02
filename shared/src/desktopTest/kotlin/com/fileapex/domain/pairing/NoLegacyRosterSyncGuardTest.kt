package com.fileapex.domain.pairing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The old roster sync is gone. Membership and profiles travel on the signed chain only. */
class NoLegacyRosterSyncGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private val main: List<File> = File(root, "shared/src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/desktopTest/" !in it.path && "/androidUnitTest/" !in it.path }.toList()

    @Test
    fun theMergeIntroductionsAndRemoveRoutesAreGone() {
        val words = listOf(
            "/api/v1/devices/merge",
            "/api/v1/cluster/introductions",
            "/api/v1/cluster/remove",
            "ClusterSyncRequest",
            "PeerAdmission",
            "PeerCandidateAdmission",
            "ClusterPuller",
            "SealedIntroduction"
        )
        val offenders = main.filter { file -> file.readText().let { text -> words.any { it in text } } }.map { it.name }.sorted()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun theOldMembershipClockIsGone() {
        val words = listOf("ClusterClock", "SettingsMembershipStore", "applyRemoteRemoval")
        val offenders = main.filter { file -> file.readText().let { text -> words.any { it in text } } }.map { it.name }.sorted()
        assertEquals(emptyList<String>(), offenders)
    }
}
