package com.fileapex.domain.presence

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Features reach a peer through one of two named entries on PeerPresenceMonitor: endpointIfOnline (background and
 * automatic work, never touches an offline peer) or probeNow (an action the user started). The raw route lookup
 * stays private to the monitor. These scans keep it that way.
 */
class PeerRouteEntryGuardTest {
    private val roots = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin", "src/jvmCommon/kotlin", "src/iosMain/kotlin")
        .map { File(it) }.filter { it.exists() }

    private fun hits(token: String): List<String> =
        roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
                file.readLines().any { line -> line.contains(token) && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
            }.map { it.name }.toList()
        }.sorted()

    @Test
    fun rawRouteLookupIsOnlyUsedInsideThePresenceMonitor() {
        assertEquals(emptyList<String>(), hits("resolveOutboundEndpoint(").filter { it != "PeerPresenceMonitor.kt" })
    }

    @Test
    fun probeNowIsOnlyUsedByUserStartedActions() {
        val allowed = listOf(
            "BulletinBoardSyncEngine.kt", "DevicesConnectActions.kt", "DevicesDetailsActions.kt", "DevicesDropActions.kt", "DevicesViewModel.kt", "ExplorerMutationActions.kt", "ExplorerViewModel.kt", "PeerPresenceMonitor.kt",
            "PhoneLocator.kt", "TransferManager.kt", "TransferQueueCoordinator.kt"
        )
        assertEquals(emptyList<String>(), hits("probeNow(").filter { it !in allowed })
    }
}
