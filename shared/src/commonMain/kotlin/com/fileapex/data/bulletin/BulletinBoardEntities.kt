package com.fileapex.data.bulletin

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "messages")
@Serializable
data class MessageEntity(
    @PrimaryKey val id: String,
    val originDeviceId: String,
    val senderName: String,
    val content: String,
    val contentType: Int,
    val timestamp: Long,
    val isDeleted: Boolean = false,
    val isPinned: Boolean = false,
    /** Local time of the last change to this row (see [BulletinChange]); 0 for rows older than schema 3. */
    val updatedAt: Long = 0L
)

/**
 * A change-able fact about a message, kept beside the message so the message itself never has to be rewritten.
 * [stamp] is when the person made the change, on their device's clock; the newest stamp wins on every device
 * (see [BulletinFlags]). [updatedAt] is only this device's store time, the key peers use to pull what changed.
 */
@Entity(tableName = "message_flags")
@Serializable
data class MessageFlagEntity(
    @PrimaryKey val flagId: String,
    val messageId: String,
    val kind: String,
    val enabled: Boolean,
    val stamp: Long,
    val setBy: String,
    val updatedAt: Long = 0L
)

/**
 * One line of a checklist post. Every line changes on its own: [stamp] is when it was last changed on the changing
 * device's clock and the newest stamp wins on every device. A removed line stays as [deleted] so the removal travels.
 */
@Entity(tableName = "checklist_items")
@Serializable
data class ChecklistItemEntity(
    @PrimaryKey val itemId: String,
    val messageId: String,
    val text: String,
    val done: Boolean,
    val deleted: Boolean,
    /** Order inside the list: the time the line was added. */
    val position: Long,
    val stamp: Long,
    val setBy: String,
    val updatedAt: Long = 0L
)

@Entity(tableName = "tombstones")
@Serializable
data class TombstoneEntity(
    @PrimaryKey val id: String,
    val deletedAt: Long,
    val originDeviceId: String,
    val remotePurge: Boolean = false,
    /** Local time this tombstone was stored; a peer pulling a delta asks for rows newer than its cursor. */
    val updatedAt: Long = 0L
)

@Entity(tableName = "outbox")
@Serializable
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val outboxId: Long = 0,
    val targetDeviceId: String,
    val payloadType: Int,
    val payloadId: String,
    val createdAt: Long,
    val retryCount: Int = 0
)

@Entity(tableName = "processed_packets")
@Serializable
data class ProcessedPacketEntity(
    @PrimaryKey val packetId: String,
    val processedAt: Long
)
