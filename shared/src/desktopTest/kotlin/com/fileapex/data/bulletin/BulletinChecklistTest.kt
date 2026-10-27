package com.fileapex.data.bulletin

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinChecklistTest {
    private val dir: File = Files.createTempDirectory("fileapex-checklist").toFile().also { it.deleteOnExit() }

    private fun open(): FileApexDatabase =
        Room.databaseBuilder<FileApexDatabase>(File(dir, "fileapex-" + System.nanoTime() + ".db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun list(id: String = "L") =
        MessageEntity(id, "devA", "Desk", "Groceries", BulletinContentType.CHECKLIST, 1000L)

    private fun item(
        id: String, text: String = id, done: Boolean = false, deleted: Boolean = false,
        position: Long = 1, stamp: Long = 10, by: String = "devA", message: String = "L"
    ) = ChecklistItemEntity(id, message, text, done, deleted, position, stamp, by)

    @Test
    fun theNewestChangeToAnItemWinsAndTiesGoToTheLargerDeviceId() {
        val base = item("a", stamp = 10)
        assertTrue(BulletinChecklist.replaces(null, base))
        assertTrue(BulletinChecklist.replaces(base, base.copy(done = true, stamp = 11)))
        assertFalse(BulletinChecklist.replaces(base.copy(stamp = 11), base.copy(done = true, stamp = 10)))
        assertTrue(BulletinChecklist.replaces(base.copy(setBy = "devA"), base.copy(done = true, setBy = "devB")))
        assertFalse(BulletinChecklist.replaces(base, base))
    }

    @Test
    fun aChangeMadeHereBeatsWhatItSawEvenOnASlowClock() {
        val seen = item("a", stamp = 5_000)
        val mine = BulletinChecklist.localChange(seen, seen.copy(done = true), "devB", nowStamp = 1_000)
        assertEquals(5_001L, mine.stamp)
        assertTrue(BulletinChecklist.replaces(seen, mine))
    }

    @Test
    fun textIsTrimmedAndCapped() {
        assertEquals("milk", BulletinChecklist.cleanText("  milk \n"))
        assertEquals(BulletinChecklist.MAX_TEXT, BulletinChecklist.cleanText("x".repeat(500)).length)
        assertEquals("", BulletinChecklist.cleanText("   "))
    }

    @Test
    fun aChecklistNoteListsItsLiveItemsInOrderAndIgnoresRemovedOnes() = runBlocking {
        val db = open()
        val repository = BulletinBoardRepository(db)
        db.messageDao().upsert(list())
        val dao = db.transactionDao()
        dao.applyChecklistItem(item("b", "eggs", position = 2), 1)
        dao.applyChecklistItem(item("a", "milk", position = 1, done = true), 2)
        dao.applyChecklistItem(item("c", "gone", position = 3), 3)
        dao.applyChecklistItem(item("c", "gone", position = 3, deleted = true, stamp = 20), 4)
        val note = repository.observeAsNotes().first().single()
        assertEquals(listOf("milk" to true, "eggs" to false), note.checklist.map { it.text to it.done })
        db.close()
    }

    @Test
    fun anOlderTickCannotUndoANewerUntickAndAFinalRemovalSticks() = runBlocking {
        val db = open()
        db.messageDao().upsert(list())
        val dao = db.transactionDao()
        assertTrue(dao.applyChecklistItem(item("a", stamp = 10), 1))
        assertTrue(dao.applyChecklistItem(item("a", done = false, stamp = 30), 2))
        assertFalse(dao.applyChecklistItem(item("a", done = true, stamp = 20), 3))
        assertTrue(dao.applyChecklistItem(item("a", deleted = true, stamp = 40), 4))
        assertFalse(dao.applyChecklistItem(item("a", done = true, stamp = 35), 5))
        assertTrue(db.checklistItemDao().observeActive().first().isEmpty())
        db.close()
    }

    @Test
    fun itemsArriveInASyncBatchAndTravelAsTheirOwnItem() = runBlocking {
        val db = open()
        val dao = db.transactionDao()
        dao.ingestSyncBatch(
            messages = listOf(list()), tombstones = emptyList(), packet = ProcessedPacketEntity("pk", 1L),
            checklistItems = listOf(item("a", "milk"))
        )
        assertEquals(1, db.checklistItemDao().observeActive().first().size)
        val wire = BulletinSyncItems(BulletinBoardRepository(db)).checklistItem(item("a", "milk", done = true))
        assertEquals(BulletinPayloadType.CHECKLIST_ITEM, wire.payloadType)
        val back = BulletinChecklist.fromPayload(Json.decodeFromString(BulletinChecklistItemPayload.serializer(), wire.body))
        assertEquals(item("a", "milk", done = true), back)
        db.close()
    }

    private fun peer(code: Int) = com.fileapex.data.db.PairedDeviceEntity(
        deviceId = "p", deviceName = "P", lastKnownIp = "192.168.1.9", port = 8080, publicKeyHash = "h",
        publicKey = "k", rootPath = "/", clusterVersion = 1L, clientVersionCode = code
    )

    @Test
    fun aNewlyPairedNewBuildGetsFlagsAndChecklistLinesAnOlderOneDoesNot() {
        val newer = BulletinPairingResend.entries(peer(219), listOf("m"), listOf("t"), listOf("f"), listOf("i"), now = 1)
        assertEquals(
            listOf(BulletinPayloadType.MESSAGE, BulletinPayloadType.TOMBSTONE, BulletinPayloadType.FLAG, BulletinPayloadType.CHECKLIST_ITEM),
            newer.map { it.payloadType }
        )
        val older = BulletinPairingResend.entries(peer(218), listOf("m"), listOf("t"), listOf("f"), listOf("i"), now = 1)
        assertEquals(listOf(BulletinPayloadType.MESSAGE, BulletinPayloadType.TOMBSTONE), older.map { it.payloadType })
    }

    @Test
    fun aPeerCannotGrowOneListPastTheLimitButRemovalsStillApply() = runBlocking {
        val db = open()
        val dao = db.transactionDao()
        repeat(BulletinChecklist.MAX_ITEMS) { assertTrue(dao.applyChecklistItem(item("i$it", position = it.toLong()), 1)) }
        assertFalse(dao.applyChecklistItem(item("extra"), 2))
        assertTrue(dao.applyChecklistItem(item("i0", deleted = true, stamp = 50), 3))
        assertTrue(dao.applyChecklistItem(item("extra"), 4))
        db.close()
    }

    @Test
    fun peerFlagIdsAndKindsAreCutToALengthWeAccept() {
        val flag = BulletinFlags.fromPayload(BulletinFlagPayload("m".repeat(500), "k".repeat(500), true, 1, "s".repeat(500)))
        assertEquals(64, flag.messageId.length)
        assertEquals(32, flag.kind.length)
        assertEquals(64, flag.setBy.length)
    }
}
