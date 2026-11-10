package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Only in-person pairing and a verified record of the signed chain may change trust on this device. Everything else is
 * a hint. Plan: docs/planning/one-trust-path-plan.md; audit: docs/audits/2026-11-10-opus-audit.md.
 */
class OneTrustPathGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun mainSources(): Sequence<File> = File(root, "shared/src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "Test/" !in it.path && "/build/" !in it.path }

    private fun filesCalling(call: String): List<String> =
        mainSources().filter { call in it.readText() }.map { it.name }.sorted().distinct().toList()

    @Test
    fun onlyTheChainEffectsReachTheSelfWipe() =
        assertEquals("only a verified REMOVE of this device may wipe it", listOf("MembershipWiring.kt"), filesCalling("KickedByPeer.onUserRemoved("))

    @Test
    fun aRevokedAnswerIsNeverTiedToAPeerByItsAddress() {
        val services = File(root, "shared/src/commonMain/kotlin/com/fileapex/di/FileApexServices.kt").readText()
        val handler = services.substringAfter("client.onRevocationDetected").substringBefore("client.membershipVersionProvider")
        assertFalse("the 401 handler matches peers by IP", handler.contains("lastKnownIp"))
    }

    @Test
    fun theUnusedUnsignedRemovalNoticeIsGone() =
        assertEquals(emptyList<String>(), filesCalling("RemovalNotice.judge("))

    @Test
    fun theAppNeverSendsTheRelayItsOwnAdmitOrRevokeLists() {
        val client = File(root, "shared/src/commonMain/kotlin/com/fileapex/cloud/device/DeviceRelayClient.kt").readText()
        assertFalse("the relay client still posts to sync", client.contains("post(\n            \"sync\"") || client.contains("\"sync\""))
        assertEquals(emptyList<String>(), filesCalling("removedDeviceVersions("))
    }
}

