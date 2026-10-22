package com.fileapex.data.bulletin

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * One-time copy of the old `bulletin_board.db` into the merged [FileApexDatabase].
 *
 * Room cannot reach a second file from a migration, so this runs after the merged database is open.
 * The old file is only read. It is renamed to [BACKUP_SUFFIX] after the counts check out and is
 * never deleted by the app.
 */
object BulletinBoardMerge {
    const val BACKUP_SUFFIX = ".merged-backup"
    const val PRE_MERGE_SUFFIX = ".pre-merge-backup"

    sealed interface Result {
        data object NothingToMerge : Result
        data class Merged(val messages: Int, val tombstones: Int, val outbox: Int, val packets: Int) : Result
        data class Mismatch(val table: String, val expected: Int, val found: Int) : Result
    }

    /** Copies [dbPath] beside itself as `<name>.pre-merge-backup` once, before Room opens it at version 17. */
    fun backupBeforeMerge(dbPath: String, legacyBulletinPath: String) {
        if (!SystemFileSystem.exists(Path(legacyBulletinPath))) return
        val source = Path(dbPath)
        val target = Path(dbPath + PRE_MERGE_SUFFIX)
        if (!SystemFileSystem.exists(source) || SystemFileSystem.exists(target)) return
        runCatching {
            SystemFileSystem.source(source).buffered().use { input ->
                SystemFileSystem.sink(target).buffered().use { output -> input.transferTo(output) }
            }
        }.onFailure { println("BulletinBoardMerge: pre-merge backup failed - ${it.message}") }
    }

    /** Leave cluster and wipe: removes the old file, its WAL files and the merge backup. */
    fun deleteLegacyFiles(legacyBulletinPath: String) {
        for (base in listOf(legacyBulletinPath, legacyBulletinPath + BACKUP_SUFFIX)) {
            for (suffix in listOf("", "-wal", "-shm")) {
                runCatching { SystemFileSystem.delete(Path(base + suffix), mustExist = false) }
            }
        }
    }

    suspend fun run(legacyBulletinPath: String, database: FileApexDatabase): Result {
        val legacyPath = Path(legacyBulletinPath)
        if (!SystemFileSystem.exists(legacyPath)) return Result.NothingToMerge
        val connection = BundledSQLiteDriver().open(legacyBulletinPath)
        val messages: List<MessageEntity>
        val tombstones: List<TombstoneEntity>
        val outbox: List<OutboxEntity>
        val packets: List<ProcessedPacketEntity>
        try {
            messages = readMessages(connection)
            tombstones = readTombstones(connection)
            outbox = readOutbox(connection)
            packets = readPackets(connection)
        } finally {
            connection.close()
        }
        val dao = database.transactionDao()
        dao.importFromLegacyFile(messages, tombstones, outbox, packets)
        val checks = listOf(
            Triple("messages", messages.size, database.messageDao().countAll()),
            Triple("tombstones", tombstones.size, database.tombstoneDao().countAll()),
            Triple("outbox", outbox.size, database.outboxDao().count()),
            Triple("processed_packets", packets.size, database.processedPacketDao().countAll())
        )
        checks.firstOrNull { (_, expected, found) -> found < expected }?.let { (table, expected, found) ->
            return Result.Mismatch(table, expected, found)
        }
        runCatching {
            val backup = Path(legacyBulletinPath + BACKUP_SUFFIX)
            SystemFileSystem.atomicMove(legacyPath, backup)
            // The WAL files belong to the old file; leaving them beside a missing database would confuse a later open.
            for (suffix in listOf("-wal", "-shm")) {
                val sidecar = Path(legacyBulletinPath + suffix)
                if (SystemFileSystem.exists(sidecar)) SystemFileSystem.atomicMove(sidecar, Path(backup.toString() + suffix))
            }
        }.onFailure { println("BulletinBoardMerge: could not rename the old file - ${it.message}") }
        return Result.Merged(messages.size, tombstones.size, outbox.size, packets.size)
    }

    private fun columns(connection: SQLiteConnection, table: String): Set<String> {
        val names = mutableSetOf<String>()
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            while (statement.step()) names += statement.getText(1)
        }
        return names
    }

    private fun readMessages(connection: SQLiteConnection): List<MessageEntity> {
        val cols = columns(connection, "messages")
        if (cols.isEmpty()) return emptyList()
        val rows = mutableListOf<MessageEntity>()
        // Schema 1 and 2 files have no updatedAt; the version 3 migration used the creation time.
        val stamp = if ("updatedAt" in cols) "updatedAt" else "timestamp"
        connection.prepare(
            "SELECT id, originDeviceId, senderName, content, contentType, timestamp, isDeleted, isPinned, $stamp FROM messages"
        ).use { s ->
            while (s.step()) {
                rows += MessageEntity(
                    id = s.getText(0), originDeviceId = s.getText(1), senderName = s.getText(2),
                    content = s.getText(3), contentType = s.getLong(4).toInt(), timestamp = s.getLong(5),
                    isDeleted = s.getLong(6) != 0L, isPinned = s.getLong(7) != 0L, updatedAt = s.getLong(8)
                )
            }
        }
        return rows
    }

    private fun readTombstones(connection: SQLiteConnection): List<TombstoneEntity> {
        val cols = columns(connection, "tombstones")
        if (cols.isEmpty()) return emptyList()
        val rows = mutableListOf<TombstoneEntity>()
        val purge = if ("remotePurge" in cols) "remotePurge" else "0"
        val stamp = if ("updatedAt" in cols) "updatedAt" else "deletedAt"
        connection.prepare("SELECT id, deletedAt, originDeviceId, $purge, $stamp FROM tombstones").use { s ->
            while (s.step()) {
                rows += TombstoneEntity(
                    id = s.getText(0), deletedAt = s.getLong(1), originDeviceId = s.getText(2),
                    remotePurge = s.getLong(3) != 0L, updatedAt = s.getLong(4)
                )
            }
        }
        return rows
    }

    private fun readOutbox(connection: SQLiteConnection): List<OutboxEntity> {
        if (columns(connection, "outbox").isEmpty()) return emptyList()
        val rows = mutableListOf<OutboxEntity>()
        connection.prepare(
            "SELECT outboxId, targetDeviceId, payloadType, payloadId, createdAt, retryCount FROM outbox"
        ).use { s ->
            while (s.step()) {
                rows += OutboxEntity(
                    outboxId = s.getLong(0), targetDeviceId = s.getText(1), payloadType = s.getLong(2).toInt(),
                    payloadId = s.getText(3), createdAt = s.getLong(4), retryCount = s.getLong(5).toInt()
                )
            }
        }
        return rows
    }

    private fun readPackets(connection: SQLiteConnection): List<ProcessedPacketEntity> {
        if (columns(connection, "processed_packets").isEmpty()) return emptyList()
        val rows = mutableListOf<ProcessedPacketEntity>()
        connection.prepare("SELECT packetId, processedAt FROM processed_packets").use { s ->
            while (s.step()) rows += ProcessedPacketEntity(s.getText(0), s.getLong(1))
        }
        return rows
    }
}
