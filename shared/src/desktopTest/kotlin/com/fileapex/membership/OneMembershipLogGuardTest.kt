package com.fileapex.membership

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The signed chain is the only membership log. The older signed-event layer (log, service, replicator, route) is gone and stays gone. */
class OneMembershipLogGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private val main: List<File> = File(root, "shared/src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/desktopTest/" !in it.path && "/androidUnitTest/" !in it.path }.toList()

    @Test
    fun theOlderLayerHasNoFilesLeft() {
        val names = setOf("MembershipLog.kt", "MembershipService.kt", "MembershipReplication.kt", "LegacyMembers.kt", "ClockHealth.kt", "HttpMembershipTransport.kt", "ServerMembershipRoutes.kt")
        assertEquals(emptyList<String>(), main.map { it.name }.filter { it in names }.sorted())
    }

    @Test
    fun nothingServesOrCallsTheOlderRoute() {
        val offenders = main.filter { "/api/v1/membership/events" in it.readText() }.map { it.name }.sorted()
        assertEquals(emptyList<String>(), offenders)
    }
}
