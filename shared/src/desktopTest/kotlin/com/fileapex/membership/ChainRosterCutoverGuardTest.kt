package com.fileapex.membership

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2.3: rename, hardware-twin replace and post-pairing catch-up go through the chain, not the old roster sync. */
class ChainRosterCutoverGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun source(path: String) = File(root, path).readText()

    private fun functionBody(text: String, name: String): String {
        val start = text.indexOf("fun $name")
        require(start >= 0) { "$name is missing" }
        val next = Regex("\\n    (?:suspend )?fun ").find(text, start + 1)?.range?.first ?: text.length
        return text.substring(start, next)
    }

    @Test
    fun aRenameBroadcastNeverPostsClusterSync() {
        val body = functionBody(source("shared/src/commonMain/kotlin/com/fileapex/domain/pairing/PairingCoordinator.kt"), "broadcastSelfIdentity")
        assertEquals(false, "postClusterSync" in body)
    }

    @Test
    fun aHardwareTwinIsRemovedThroughTheChain() {
        val body = functionBody(source("shared/src/commonMain/kotlin/com/fileapex/domain/presence/PeerPresenceMonitor.kt"), "supersedeHardwareTwins")
        assertTrue("HardwareTwins" in body)
        assertEquals(false, "peerAdmission" in body)
        assertTrue("removeMember" in source("shared/src/commonMain/kotlin/com/fileapex/domain/pairing/HardwareTwins.kt"))
    }

    @Test
    fun pairingCompletionDoesNotImportOrAnnounceTheOldRoster() {
        val joiner = source("shared/src/commonMain/kotlin/com/fileapex/presentation/devices/DevicesPairingActions.kt")
        assertEquals(false, "importDirectPeerRoster" in joiner)
        assertEquals(false, "announceSelfToCluster" in joiner)
        val host = source("shared/src/commonMain/kotlin/com/fileapex/network/ServerLifecycleManager.kt")
        assertEquals(false, "propagatePairingComplete" in host)
    }
}
