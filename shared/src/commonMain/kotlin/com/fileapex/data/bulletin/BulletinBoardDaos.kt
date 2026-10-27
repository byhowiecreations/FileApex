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
interface MessageFlagDao {
    @Query("SELECT * FROM message_flags WHERE flagId = :flagId LIMIT 1")
    suspend fun get(flagId: String): MessageFlagEntity?

    @Query("SELECT * FROM message_flags WHERE updatedAt > :since ORDER BY updatedAt ASC")
    suspend fun getChangedSince(since: Long): List<MessageFlagEntity>

    @Query("SELECT * FROM message_flags")
    suspend fun getAll(): List<MessageFlagEntity>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM message_flags")
    suspend fun latestChange(): Long

    @Query("DELETE FROM message_flags")
    suspend fun deleteAllFlags()
}

@Dao
interface ChecklistItemDao {
    @Query("SELECT * FROM checklist_items WHERE itemId = :itemId LIMIT 1")
    suspend fun get(itemId: String): ChecklistItemEntity?

    @Query("SELECT * FROM checklist_items WHERE deleted = 0 ORDER BY position ASC, itemId ASC")
    fun observeActive(): kotlinx.coroutines.flow.Flow<List<ChecklistItemEntity>>

    @Query("SELECT COUNT(*) FROM checklist_items WHERE messageId = :messageId AND deleted = 0")
    suspend fun countActive(messageId: String): Int

    @Query("SELECT * FROM checklist_items WHERE updatedAt > :since ORDER BY updatedAt ASC")
    suspend fun getChangedSince(since: Long): List<ChecklistItemEntity>

    @Query("SELECT * FROM checklist_items")
    suspend fun getAll(): List<ChecklistItemEntity>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM checklist_items")
    suspend fun latestChange(): Long

    @Query("DELETE FROM checklist_items")
    suspend fun deleteAllItems()
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

    @Query("SELECT * FROM message_flags WHERE flagId = :flagId LIMIT 1")
    protected abstract suspend fun getFlag(flagId: String): MessageFlagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertFlag(flag: MessageFlagEntity)

    /** The lock shown on a message is a copy of its Locked flag, so lists and delete checks keep reading one column. */
    @Query("UPDATE messages SET isPinned = :locked WHERE id = :id")
    protected abstract suspend fun mirrorLocked(id: String, locked: Boolean)

    @Query("SELECT * FROM checklist_items WHERE itemId = :itemId LIMIT 1")
    protected abstract suspend fun getChecklistItem(itemId: String): ChecklistItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertChecklistItem(item: ChecklistItemEntity)

    @Query("SELECT COUNT(*) FROM checklist_items WHERE messageId = :messageId AND deleted = 0")
    protected abstract suspend fun countLiveItems(messageId: String): Int

    /** True when [incoming] replaced the stored line (it is newer). A peer cannot grow one list past the limit. */
    @Transaction
    open suspend fun applyChecklistItem(incoming: ChecklistItemEntity, nowMs: Long): Boolean {
        val existing = getChecklistItem(incoming.itemId)
        if (existing == null && !incoming.deleted && countLiveItems(incoming.messageId) >= BulletinChecklist.MAX_ITEMS) return false
        if (!BulletinChecklist.replaces(existing, incoming)) return false
        upsertChecklistItem(incoming.copy(updatedAt = nowMs))
        return true
    }

    /** True when [incoming] replaced what was stored (it is newer), so the caller knows the board changed. */
    @Transaction
    open suspend fun applyFlag(incoming: MessageFlagEntity, nowMs: Long): Boolean {
        if (!BulletinFlags.replaces(getFlag(incoming.flagId), incoming)) return false
        upsertFlag(incoming.copy(updatedAt = nowMs))
        if (incoming.kind == BulletinFlags.LOCKED) mirrorLocked(incoming.messageId, incoming.enabled)
        return true
    }

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
        packet: ProcessedPacketEntity,
        flags: List<MessageFlagEntity> = emptyList(),
        checklistItems: List<ChecklistItemEntity> = emptyList()
    ) {
        for (message in messages) {
            val existing = getMessageById(message.id)
            // The lock comes from the flag, never from the message payload an older build sends along.
            val locked = getFlag(BulletinFlags.id(message.id, BulletinFlags.LOCKED))?.enabled ?: existing?.isPinned ?: false
            BulletinChange.messageToStore(existing, message.copy(isPinned = locked), BulletinStampClock.next())?.let { upsertMessage(it) }
        }
        // Flags before deletes: an unlock that arrives with a delete must be known when the delete is checked.
        for (flag in flags) applyFlag(flag, BulletinStampClock.next())
        for (item in checklistItems) applyChecklistItem(item, BulletinStampClock.next())
        if (checklistItems.isNotEmpty()) println("BulletinBoard: a batch carried ${checklistItems.size} checklist lines")
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
}
