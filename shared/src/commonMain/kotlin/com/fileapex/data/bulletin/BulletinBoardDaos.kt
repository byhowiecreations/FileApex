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

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("UPDATE messages SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query(
        "DELETE FROM messages WHERE isPinned = 0 AND timestamp < :cutoff AND isDeleted = 0"
    )
    suspend fun pruneUnpinnedOlderThan(cutoff: Long): Int
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
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<OutboxEntity>

    @Query(
        "SELECT * FROM outbox WHERE targetDeviceId = :deviceId ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun getForDevice(deviceId: String, limit: Int): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE outboxId = :outboxId")
    suspend fun deleteById(outboxId: Long)

    @Query("DELETE FROM outbox WHERE payloadId = :payloadId AND targetDeviceId = :targetDeviceId")
    suspend fun deleteByPayload(targetDeviceId: String, payloadId: String)

    @Query("DELETE FROM outbox WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("UPDATE outbox SET retryCount = retryCount + 1 WHERE outboxId = :outboxId")
    suspend fun incrementRetry(outboxId: Long)
}

@Dao
interface ProcessedPacketDao {
    @Query("SELECT COUNT(*) FROM processed_packets WHERE packetId = :packetId")
    suspend fun countById(packetId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(packet: ProcessedPacketEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(packets: List<ProcessedPacketEntity>)
}

@Dao
abstract class BulletinBoardTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTombstone(tombstone: TombstoneEntity)

    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :id")
    protected abstract suspend fun markMessageDeleted(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProcessedPacket(packet: ProcessedPacketEntity)

    @Query("DELETE FROM outbox WHERE payloadId = :payloadId AND targetDeviceId = :targetDeviceId")
    protected abstract suspend fun deleteOutboxPayload(targetDeviceId: String, payloadId: String)

    @Transaction
    open suspend fun applyTombstone(tombstone: TombstoneEntity) {
        insertTombstone(tombstone)
        markMessageDeleted(tombstone.id)
    }

    @Transaction
    open suspend fun ingestSyncBatch(
        messages: List<MessageEntity>,
        tombstones: List<TombstoneEntity>,
        packet: ProcessedPacketEntity
    ) {
        for (message in messages) upsertMessage(message)
        for (tombstone in tombstones) {
            insertTombstone(tombstone)
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

    @Transaction
    open suspend fun migrateLegacyMessages(messages: List<MessageEntity>) {
        for (message in messages) upsertMessage(message)
    }
}
