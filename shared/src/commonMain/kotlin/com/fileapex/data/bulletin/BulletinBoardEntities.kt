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
    val isPinned: Boolean = false
)

@Entity(tableName = "tombstones")
@Serializable
data class TombstoneEntity(
    @PrimaryKey val id: String,
    val deletedAt: Long,
    val originDeviceId: String,
    val remotePurge: Boolean = false
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
