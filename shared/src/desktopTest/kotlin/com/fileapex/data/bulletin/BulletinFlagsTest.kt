package com.fileapex.data.bulletin

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinFlagsTest {
    private val dir: File = Files.createTempDirectory("fileapex-flags").toFile().also { it.deleteOnExit() }

    private fun open(): FileApexDatabase =
        Room.databaseBuilder<FileApexDatabase>(File(dir, "fileapex-" + System.nanoTime() + ".db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun message(id: String, pinned: Boolean = false) = MessageEntity(
        id = id, originDeviceId = "devA", senderName = "Desk", content = "hi", contentType = 0, timestamp = 1000L, isPinned = pinned
    )

    private fun lock(id: String, on: Boolean, stamp: Long, by: String = "devA") = MessageFlagEntity(
        flagId = BulletinFlags.id(id, BulletinFlags.LOCKED), messageId = id, kind = BulletinFlags.LOCKED,
        enabled = on, stamp = stamp, setBy = by
    )

    private fun batch() = ProcessedPacketEntity("pk-" + System.nanoTime(), 1L)

    @Test
    fun newerStampWinsAndEqualStampsPickTheSameSideEverywhere() {
        val locked = lock("m", true, 100)
        assertTrue(BulletinFlags.replaces(null, locked))
        assertTrue(BulletinFlags.replaces(locked, lock("m", false, 101)))
        assertFalse(BulletinFlags.replaces(lock("m", false, 101), locked))
        assertTrue(BulletinFlags.replaces(lock("m", true, 100, "devA"), lock("m", false, 100, "devB")))
        assertFalse(BulletinFlags.replaces(lock("m", false, 100, "devB"), lock("m", true, 100, "devA")))
        assertFalse(BulletinFlags.replaces(locked, locked))
    }

    @Test
    fun aChangeMadeHereBeatsTheValueItSawEvenWhenTheClockRunsBehind() {
        val seen = lock("m", true, 5_000)
        val mine = BulletinFlags.localChange(seen, "m", BulletinFlags.LOCKED, false, "devB", nowStamp = 1_000)
        assertEquals(5_001L, mine.stamp)
        assertTrue(BulletinFlags.replaces(seen, mine))
    }

    @Test
    fun lockingAndUnlockingChangesTheMessageAndAnOlderChangeCannotUndoIt() = runBlocking {
        val db = open()
        db.messageDao().upsert(message("m"))
        val dao = db.transactionDao()
        assertTrue(dao.applyFlag(lock("m", true, 100), 1L))
        assertTrue(db.messageDao().getById("m")!!.isPinned)
        assertTrue(dao.applyFlag(lock("m", false, 200), 2L))
        assertFalse(db.messageDao().getById("m")!!.isPinned)
        assertFalse(dao.applyFlag(lock("m", true, 150), 3L))
        assertFalse(db.messageDao().getById("m")!!.isPinned)
        assertEquals(2L, db.messageFlagDao().get(BulletinFlags.id("m", BulletinFlags.LOCKED))!!.updatedAt)
        db.close()
    }

    @Test
    fun aMessageFromAnOlderBuildCannotRelockWhatTheClusterUnlocked() = runBlocking {
        val db = open()
        db.messageDao().upsert(message("m"))
        db.transactionDao().applyFlag(lock("m", true, 100), 1L)
        db.transactionDao().applyFlag(lock("m", false, 200), 2L)
        val fromOldBuild = Json { ignoreUnknownKeys = true }.decodeFromString(
            BulletinMessagePayload.serializer(),
            """{"id":"m","originDeviceId":"devA","senderName":"Desk","content":"hi","contentType":0,"timestamp":1000,"isPinned":true}"""
        )
        assertTrue(fromOldBuild.isPinned)
        db.transactionDao().ingestSyncBatch(
            messages = listOf(message("m", pinned = fromOldBuild.isPinned)),
            tombstones = emptyList(),
            packet = batch()
        )
        assertFalse(db.messageDao().getById("m")!!.isPinned)
        db.close()
    }

    @Test
    fun aFlagThatArrivesBeforeItsMessageStillLocksIt() = runBlocking {
        val db = open()
        db.transactionDao().applyFlag(lock("m", true, 100), 1L)
        db.transactionDao().ingestSyncBatch(listOf(message("m")), emptyList(), batch())
        assertTrue(db.messageDao().getById("m")!!.isPinned)
        db.close()
    }

    @Test
    fun anUnlockAndTheDeleteThatFollowsItInOneBatchBothApply() = runBlocking {
        val db = open()
        db.messageDao().upsert(message("m", pinned = true))
        db.transactionDao().applyFlag(lock("m", true, 100), 1L)
        db.transactionDao().ingestSyncBatch(
            messages = emptyList(),
            tombstones = listOf(TombstoneEntity("m", deletedAt = 300L, originDeviceId = "devA")),
            packet = batch(),
            flags = listOf(lock("m", false, 200))
        )
        assertTrue(db.messageDao().getById("m")!!.isDeleted)
        assertNotNull(db.tombstoneDao().getById("m"))
        db.close()
    }

    @Test
    fun aLockedMessageIgnoresADeleteUntilItIsUnlocked() = runBlocking {
        val db = open()
        db.messageDao().upsert(message("m"))
        db.transactionDao().applyFlag(lock("m", true, 100), 1L)
        db.transactionDao().applyTombstone(TombstoneEntity("m", 300L, "devA"))
        assertFalse(db.messageDao().getById("m")!!.isDeleted)
        assertNull(db.tombstoneDao().getById("m"))
        db.close()
    }

    @Test
    fun aMessageNeverLockedByAFlagKeepsALockCopiedFromTheOldBoard() = runBlocking {
        val db = open()
        db.messageDao().upsert(message("m", pinned = true))
        db.transactionDao().ingestSyncBatch(listOf(message("m")), emptyList(), batch())
        assertTrue(db.messageDao().getById("m")!!.isPinned)
        db.close()
    }

    @Test
    fun flagsTravelAsTheirOwnItemAndOlderBuildsGetNone() {
        val flag = lock("m", true, 100)
        val item = BulletinSyncItems(BulletinBoardRepository(open())).flag(flag)
        assertEquals(BulletinPayloadType.FLAG, item.payloadType)
        assertEquals(flag.flagId, item.payloadId)
        val back = BulletinFlags.fromPayload(Json.decodeFromString(BulletinFlagPayload.serializer(), item.body))
        assertEquals(flag, back)
        assertFalse(BulletinFlags.supportedBy(218))
        assertTrue(BulletinFlags.supportedBy(219))
        assertTrue(BulletinFlags.supportedBy(1_025_219))
    }

    @Test
    fun anImageWithAPreviewIsNotLockedUntilSomeoneLocksIt() {
        val meta = """{"fileName":"a.png","sizeBytes":3,"sha256":"x","originNode":"devA","previewBase64":"AAAA"}"""
        val note = message("img").copy(contentType = BulletinContentType.IMAGE_PREVIEW, content = meta).toNoteRecord()
        assertFalse(note.attachmentPinned)
        assertTrue(message("img", pinned = true).copy(contentType = BulletinContentType.IMAGE_PREVIEW, content = meta).toNoteRecord().attachmentPinned)
    }
}
