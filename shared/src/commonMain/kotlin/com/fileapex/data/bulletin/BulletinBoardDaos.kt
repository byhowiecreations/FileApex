package com.fileapex.data.bulletin

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE isDeleted = 0 ORDER BY timestamp ASC")
    fun observeActive(): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isDeleted = 0 ORDER BY timestamp ASC")
    suspend fun getActiveOnce(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET isPinned = :pinned, updatedAt = :nowMs WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, nowMs: Long)

    @Query("SELECT * FROM messages WHERE isDeleted = 0 AND updatedAt > :since ORDER BY updatedAt ASC")
    suspend fun getChangedSince(since: Long): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM messages")
    suspend fun latestChange(): Long

    @Query(
        "DELETE FROM messages WHERE isPinned = 0 AND timestamp < :cutoff AND isDeleted = 0"
    )
    suspend fun pruneUnpinnedOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}

@Dao
interface TombstoneDao {
    @Query("SELECT COUNT(*) FROM tombstones WHERE id = :id")
    suspend fun countById(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: TombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tombstones: List<TombstoneEntity>)

    @Query("SELECT * FROM tombstones WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TombstoneEntity?

    @Query("SELECT * FROM tombstones WHERE deletedAt > :cutoff ORDER BY deletedAt ASC")
    suspend fun getRecentOnce(cutoff: Long): List<TombstoneEntity>

    @Query("SELECT * FROM tombstones WHERE updatedAt > :since ORDER BY updatedAt ASC")
    suspend fun getChangedSince(since: Long): List<TombstoneEntity>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM tombstones")
    suspend fun latestChange(): Long

    @Query("SELECT COUNT(*) FROM tombstones")
    suspend fun countAll(): Int

    @Query("DELETE FROM tombstones")
    suspend fun deleteAllTombstones()
}

@Dao
interface OutboxDao {

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query(
        "SELECT * FROM outbox WHERE targetDeviceId = :deviceId ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun getForDevice(deviceId: String, limit: Int): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE outboxId = :outboxId")
    suspend fun deleteById(outboxId: Long)

    @Query("DELETE FROM outbox WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("UPDATE outbox SET retryCount = retryCount + 1 WHERE outboxId = :outboxId")
    suspend fun incrementRetry(outboxId: Long)

    @Query("DELETE FROM outbox")
    suspend fun deleteAllOutbox()
}

@Dao
interface ProcessedPacketDao {
    @Query("SELECT COUNT(*) FROM processed_packets WHERE packetId = :packetId")
    suspend fun countById(packetId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(packet: ProcessedPacketEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(packets: List<ProcessedPacketEntity>)

    @Query("SELECT COUNT(*) FROM processed_packets")
    suspend fun countAll(): Int

    @Query("DELETE FROM processed_packets")
    suspend fun deleteAllPackets()
}

@Dao
abstract class BulletinBoardTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTombstone(tombstone: TombstoneEntity)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    protected abstract suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT * FROM tombstones WHERE id = :id LIMIT 1")
    protected abstract suspend fun getTombstoneById(id: String): TombstoneEntity?

    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :id AND isPinned = 0")
    protected abstract suspend fun markMessageDeleted(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProcessedPacket(packet: ProcessedPacketEntity)

    @Query("DELETE FROM outbox WHERE payloadId = :payloadId AND targetDeviceId = :targetDeviceId")
    protected abstract suspend fun deleteOutboxPayload(targetDeviceId: String, payloadId: String)

    @Transaction
    open suspend fun applyTombstone(tombstone: TombstoneEntity) {
        val existing = getMessageById(tombstone.id)
        if (existing != null && existing.isPinned) return
        BulletinChange.tombstoneToStore(getTombstoneById(tombstone.id), tombstone, BulletinStampClock.next())?.let { insertTombstone(it) }
        markMessageDeleted(tombstone.id)
    }

    @Transaction
    open suspend fun ingestSyncBatch(
        messages: List<MessageEntity>,
        tombstones: List<TombstoneEntity>,
        packet: ProcessedPacketEntity
    ) {
        for (message in messages) {
            BulletinChange.messageToStore(getMessageById(message.id), message, BulletinStampClock.next())?.let { upsertMessage(it) }
        }
        for (tombstone in tombstones) {
            val existing = getMessageById(tombstone.id)
            if (existing != null && existing.isPinned) continue
            BulletinChange.tombstoneToStore(getTombstoneById(tombstone.id), tombstone, BulletinStampClock.next())?.let { insertTombstone(it) }
            markMessageDeleted(tombstone.id)
        }
        insertProcessedPacket(packet)
    }

    @Transaction
    open suspend fun applyAck(targetDeviceId: String, payloadIds: List<String>) {
        for (payloadId in payloadIds) {
            deleteOutboxPayload(targetDeviceId, payloadId)
        }
    }

    @Transaction
    open suspend fun insertOutboxEntries(entries: List<OutboxEntity>) {
        for (entry in entries) insertOutboxEntry(entry)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertOutboxEntry(entry: OutboxEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun importMessages(rows: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun importTombstones(rows: List<TombstoneEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun importOutbox(rows: List<OutboxEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun importPackets(rows: List<ProcessedPacketEntity>)

    /** One transaction; ids and stamps are kept and rows already present win, so a repeat after an interrupted copy is harmless. */
    @Transaction
    open suspend fun importFromLegacyFile(
        messages: List<MessageEntity>,
        tombstones: List<TombstoneEntity>,
        outbox: List<OutboxEntity>,
        packets: List<ProcessedPacketEntity>
    ) {
        importMessages(messages)
        importTombstones(tombstones)
        importOutbox(outbox)
        importPackets(packets)
    }

    @Transaction
    open suspend fun migrateLegacyMessages(messages: List<MessageEntity>) {
        for (message in messages) upsertMessage(message.copy(updatedAt = BulletinStampClock.next()))
    }
}
