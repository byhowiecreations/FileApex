package com.fileapex.data.bulletin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Bulletin Board moves by pull only: no push-style sender, no push routes, no size gate in front of a post. */
class BulletinPullOnlyTest {
    private val main = File("src/commonMain/kotlin/com/fileapex")

    private fun text(path: String) = File(main, path).readText()

    @Test
    fun thePushStyleSenderIsGone() {
        assertFalse(File(main, "data/bulletin/BulletinLegacyRelay.kt").exists())
        assertFalse(text("data/bulletin/BulletinBoardSyncEngine.kt").contains("BulletinLegacyRelay"))
    }

    @Test
    fun thePushRoutesAndTheirClientCallsAreGone() {
        val routes = text("network/routes/ServerBulletinRoutes.kt")
        val client = text("network/PeerBulletinClient.kt")
        listOf("/api/v1/notes/send", "/api/v1/notes/attachment", "/api/v1/notes/delete").forEach {
            assertFalse(it, routes.contains(it))
            assertFalse(it, client.contains(it))
        }
    }

    @Test
    fun noFileSizeLimitStandsInFrontOfABulletinPost() {
        assertFalse(text("cloud/drive/DriveRelayPolicy.kt").contains("NOTES_LAN_ATTACHMENT_MAX_BYTES"))
        assertFalse(text("cloud/drive/DriveRelayPolicy.kt").contains("evaluateNotesAttachment"))
        listOf("ui/notes/NotesLogic.kt", "ui/notes/NotesScope.kt").forEach {
            assertFalse(it, text(it).contains("evaluateNotesAttachment"))
        }
    }
}
