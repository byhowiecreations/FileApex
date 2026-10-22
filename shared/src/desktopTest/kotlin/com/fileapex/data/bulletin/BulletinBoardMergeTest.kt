package com.fileapex.data.bulletin

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.MIGRATION_16_17
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinBoardMergeTest {
    private val dir: File = Files.createTempDirectory("fileapex-merge").toFile().also { it.deleteOnExit() }

    private fun openMerged(): FileApexDatabase =
        Room.databaseBuilder<FileApexDatabase>(File(dir, "fileapex.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun exec(connection: SQLiteConnection, vararg sql: String) {
        for (statement in sql) connection.prepare(statement).use { it.step() }
    }

    private fun legacyV3(file: File) {
        val c = BundledSQLiteDriver().open(file.absolutePath)
        exec(
            c,
            "CREATE TABLE `messages` (`id` TEXT NOT NULL, `originDeviceId` TEXT NOT NULL, `senderName` TEXT NOT NULL, `content` TEXT NOT NULL, `contentType` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `tombstones` (`id` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, `originDeviceId` TEXT NOT NULL, `remotePurge` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `outbox` (`outboxId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetDeviceId` TEXT NOT NULL, `payloadType` INTEGER NOT NULL, `payloadId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL)",
            "CREATE TABLE `processed_packets` (`packetId` TEXT NOT NULL, `processedAt` INTEGER NOT NULL, PRIMARY KEY(`packetId`))",
            "INSERT INTO messages VALUES ('m1','devA','Desk','hello',0,1000,0,1,5000)",
            "INSERT INTO messages VALUES ('m2','devB','Phone','gone',0,2000,1,0,6000)",
            "INSERT INTO tombstones VALUES ('m9',3000,'devA',1,7000)",
            "INSERT INTO outbox VALUES (41,'devB',1,'m1',4000,2)",
            "INSERT INTO outbox VALUES (42,'devC',1,'m2',4100,0)",
            "INSERT INTO processed_packets VALUES ('pk1',8000)"
        )
        c.close()
    }

    @Test
    fun copiesEveryRowKeepingIdsAndStampsThenRenamesTheOldFile() = runBlocking {
        val legacy = File(dir, "bulletin_board.db")
        legacyV3(legacy)
        val db = openMerged()
        val result = BulletinBoardMerge.run(legacy.absolutePath, db)
        assertEquals(BulletinBoardMerge.Result.Merged(2, 1, 2, 1), result)
        val m1 = db.messageDao().getById("m1")!!
        assertEquals(5000L, m1.updatedAt)
        assertTrue(m1.isPinned)
        assertEquals(1000L, m1.timestamp)
        assertTrue(db.messageDao().getById("m2")!!.isDeleted)
        assertTrue(db.tombstoneDao().getById("m9")!!.remotePurge)
        assertEquals(7000L, db.tombstoneDao().getById("m9")!!.updatedAt)
        assertEquals(listOf(41L, 42L), db.outboxDao().getForDevice("devB", 10).map { it.outboxId } +
            db.outboxDao().getForDevice("devC", 10).map { it.outboxId })
        assertEquals(1, db.processedPacketDao().countById("pk1"))
        assertFalse(legacy.exists())
        assertTrue(File(dir, "bulletin_board.db" + BulletinBoardMerge.BACKUP_SUFFIX).exists())
        db.close()
    }

    @Test
    fun interruptedCopyRepeatsWithoutDuplicatesOrOverwritingNewerRows() = runBlocking {
        val legacy = File(dir, "bulletin_board.db")
        legacyV3(legacy)
        val db = openMerged()
        BulletinBoardMerge.run(legacy.absolutePath, db)
        db.messageDao().setPinned("m1", false, 9999L)
        // The rename never happened (app killed): the old file is still there on the next start.
        File(dir, "bulletin_board.db" + BulletinBoardMerge.BACKUP_SUFFIX).renameTo(legacy)
        val again = BulletinBoardMerge.run(legacy.absolutePath, db)
        assertEquals(BulletinBoardMerge.Result.Merged(2, 1, 2, 1), again)
        assertEquals(2, db.messageDao().countAll())
        assertFalse(db.messageDao().getById("m1")!!.isPinned)
        db.close()
    }

    @Test
    fun schemaOneFileGetsTheVersionThreeDefaults() = runBlocking {
        val legacy = File(dir, "bulletin_board.db")
        val c = BundledSQLiteDriver().open(legacy.absolutePath)
        exec(
            c,
            "CREATE TABLE `messages` (`id` TEXT NOT NULL, `originDeviceId` TEXT NOT NULL, `senderName` TEXT NOT NULL, `content` TEXT NOT NULL, `contentType` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `tombstones` (`id` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, `originDeviceId` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "INSERT INTO messages VALUES ('m1','devA','Desk','hello',0,1234,0,0)",
            "INSERT INTO tombstones VALUES ('m0',777,'devA')"
        )
        c.close()
        val db = openMerged()
        BulletinBoardMerge.run(legacy.absolutePath, db)
        assertEquals(1234L, db.messageDao().getById("m1")!!.updatedAt)
        val t = db.tombstoneDao().getById("m0")!!
        assertEquals(777L, t.updatedAt)
        assertFalse(t.remotePurge)
        db.close()
    }

    @Test
    fun noOldFileMeansNothingToMerge() = runBlocking {
        val db = openMerged()
        assertEquals(BulletinBoardMerge.Result.NothingToMerge, BulletinBoardMerge.run(File(dir, "none.db").absolutePath, db))
        assertNull(db.messageDao().getById("x"))
        db.close()
    }

    @Test
    fun migrationCreatesTheFourBulletinTables() = runBlocking {
        val file = File(dir, "migrate.db")
        val c = BundledSQLiteDriver().open(file.absolutePath)
        MIGRATION_16_17.migrate(c)
        for (table in listOf("messages", "tombstones", "outbox", "processed_packets")) {
            c.prepare("SELECT COUNT(*) FROM `$table`").use { assertTrue(it.step()) }
        }
        c.close()
    }

    @Test
    fun wipeRemovesTheOldFileAndItsBackup() {
        val legacy = File(dir, "bulletin_board.db").also { it.writeText("x") }
        val backup = File(dir, "bulletin_board.db" + BulletinBoardMerge.BACKUP_SUFFIX).also { it.writeText("x") }
        BulletinBoardMerge.deleteLegacyFiles(legacy.absolutePath)
        assertFalse(legacy.exists())
        assertFalse(backup.exists())
    }
}
