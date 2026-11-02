package com.fileapex.security.tls

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A stored TLS pin may change only through a path that is authenticated or confirmed by the user. A new writer must be
 * added here on purpose, with its authority named, so no silent overwrite of a trusted pin can slip in again.
 */
class TlsPinWritersGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private val main: List<File> = listOf("shared/src", "composeApp/src").flatMap { dir ->
        File(root, dir).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "Test/" !in it.path && "/androidUnitTest/" !in it.path }.toList()
    }

    private val writers = mapOf(
        "MembershipWiring.kt" to "the signed chain; a legacy admit never overrides a pin already held (ChainApplier)",
        "TlsPinAnnouncement.kt" to "pair-key sealed announcements and vouches from members that may command membership, and the user's own confirmation",
        "TlsPinAnnouncer.kt" to "a rotation proven with the old key, or a vouch",
        "PairingCoordinator.kt" to "the pairing QR the user scanned",
        "DevicesPairingActions.kt" to "the pin proven during pairing",
        "PeerContactRecords.kt" to "the single gate every caller passes",
        "DeviceRepository.kt" to "delegates to the gate",
        "TlsPinTables.kt" to "the store behind the gate"
    )

    @Test
    fun onlyTheNamedAuthenticatedPathsWriteAPin() {
        val calls = Regex("""recordTlsPin\(|pins\.record\(|\.setTls\(|reactivateLegacy\(|addLegacy\(""")
        val found = main.filter { calls.containsMatchIn(it.readText()) }.map { it.name }.toSortedSet()
        assertEquals(writers.keys.toSortedSet(), found)
    }
}
