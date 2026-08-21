package com.fileapex.data.bulletin

import com.fileapex.data.db.NoteDao
import com.fileapex.data.db.NoteEntity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.note.NoteRecord
import com.fileapex.util.sha256HexFile
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BulletinBoardRepository(
    private val database: BulletinBoardDatabase
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val messageDao = database.messageDao()
    private val tombstoneDao = database.tombstoneDao()
    private val outboxDao = database.outboxDao()
    private val transactionDao = database.transactionDao()

    val messages: Flow<List<MessageEntity>> = messageDao.observeActive()

    fun observeAsNotes(): Flow<List<NoteRecord>> = messages.map { rows ->
        rows.map { it.toNoteRecord() }
    }

    suspend fun getMessage(id: String): MessageEntity? = messageDao.getById(id)

    suspend fun isTombstoned(id: String): Boolean = tombstoneDao.countById(id) > 0

    suspend fun migrateFromLegacyNotes(noteDao: NoteDao) {
        if (messageDao.getActiveOnce().isNotEmpty()) return
        val legacy = noteDao.getAllNotesOnce()
        if (legacy.isEmpty()) return
        transactionDao.migrateLegacyMessages(legacy.map { it.toMessageEntity() })
    }

    suspend fun ingestLocalText(content: String, link: Boolean = false): MessageEntity {
        val identity = loadLocalIdentity()
        val selfName = LocalDeviceNameStore.current().ifBlank { identity.deviceName }
        val trimmed = content.trim()
        val message = MessageEntity(
            id = "msg-" + TimeUtils.now() + "-" + (1000..9999).random(),
            originDeviceId = identity.deviceId,
            senderName = selfName,
            content = trimmed,
            contentType = if (link) BulletinContentType.LINK else BulletinContentType.TEXT,
            timestamp = TimeUtils.now(),
            isDeleted = false,
            isPinned = false
        )
        messageDao.upsert(message)
        return message
    }

    suspend fun ingestLocalFile(
        absolutePath: String,
        fileName: String,
        sizeBytes: Long,
        caption: String = ""
    ): MessageEntity {
        val identity = loadLocalIdentity()
        val selfName = LocalDeviceNameStore.current().ifBlank { identity.deviceName }
        val sha256 = sha256HexFile(absolutePath)
        val preview = runCatching {
            BulletinMediaHelper.buildImagePreviewBase64(absolutePath)
        }.getOrNull()
        val contentType = if (preview != null) {
            BulletinContentType.IMAGE_PREVIEW
        } else {
            BulletinContentType.FILE_METADATA
        }
        val content = if (preview != null) {
            json.encodeToString(
                BulletinFileMetadata(
                    fileName = fileName,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    originNode = identity.deviceId,
                    localPath = absolutePath,
                    previewBase64 = preview
                )
            )
        } else {
            json.encodeToString(
                BulletinFileMetadata(
                    fileName = fileName,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    originNode = identity.deviceId,
                    localPath = absolutePath
                )
            )
        }
        val message = MessageEntity(
            id = "msg-" + TimeUtils.now() + "-" + (1000..9999).random(),
            originDeviceId = identity.deviceId,
            senderName = selfName,
            content = if (caption.isNotBlank()) "$caption\n$content" else content,
            contentType = contentType,
            timestamp = TimeUtils.now()
        )
        messageDao.upsert(message)
        return message
    }

    suspend fun upsertFromSync(payload: BulletinMessagePayload): Boolean {
        if (tombstoneDao.countById(payload.id) > 0) return false
        messageDao.upsert(
            MessageEntity(
                id = payload.id,
                originDeviceId = payload.originDeviceId,
                senderName = payload.senderName,
                content = payload.content,
                contentType = payload.contentType,
                timestamp = payload.timestamp,
                isDeleted = false,
                isPinned = payload.isPinned
            )
        )
        return true
    }

    suspend fun applyTombstone(payload: BulletinTombstonePayload) {
        transactionDao.applyTombstone(
            TombstoneEntity(
                id = payload.id,
                deletedAt = payload.deletedAt,
                originDeviceId = payload.originDeviceId
            )
        )
    }

    suspend fun deleteMessage(id: String) {
        val identity = loadLocalIdentity()
        applyTombstone(
            BulletinTombstonePayload(
                id = id,
                deletedAt = TimeUtils.now(),
                originDeviceId = identity.deviceId
            )
        )
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        messageDao.setPinned(id, pinned)
    }

    suspend fun enqueueOutbox(targetDeviceId: String, payloadType: Int, payloadId: String) {
        outboxDao.insert(
            OutboxEntity(
                targetDeviceId = targetDeviceId,
                payloadType = payloadType,
                payloadId = payloadId,
                createdAt = TimeUtils.now()
            )
        )
    }

    suspend fun enqueueOutboxForAllPeers(payloadType: Int, payloadId: String, peerIds: List<String>) {
        val selfId = loadLocalIdentity().deviceId
        val now = TimeUtils.now()
        val entries = peerIds.filter { it != selfId }.map { peerId ->
            OutboxEntity(
                targetDeviceId = peerId,
                payloadType = payloadType,
                payloadId = payloadId,
                createdAt = now
            )
        }
        if (entries.isNotEmpty()) {
            transactionDao.insertOutboxEntries(entries)
        }
    }

    suspend fun getOutboxForDevice(deviceId: String, limit: Int): List<OutboxEntity> =
        outboxDao.getForDevice(deviceId, limit)

    suspend fun removeOutboxEntry(outboxId: Long) = outboxDao.deleteById(outboxId)

    suspend fun removeOutboxByPayload(targetDeviceId: String, payloadId: String) =
        outboxDao.deleteByPayload(targetDeviceId, payloadId)

    suspend fun pruneStaleOutbox(nowMs: Long = TimeUtils.now()): Int {
        val cutoff = nowMs - BulletinBoardPolicy.OUTBOX_TTL_MS
        return outboxDao.deleteOlderThan(cutoff)
    }

    suspend fun pruneOldMessages(nowMs: Long = TimeUtils.now()): Int {
        val cutoff = nowMs - BulletinBoardPolicy.RETENTION_HORIZON_MS
        return messageDao.pruneUnpinnedOlderThan(cutoff)
    }

    suspend fun bindLocalPath(messageId: String, localPath: String) {
        val existing = messageDao.getById(messageId) ?: return
        if (existing.contentType != BulletinContentType.FILE_METADATA &&
            existing.contentType != BulletinContentType.IMAGE_PREVIEW
        ) {
            return
        }
        val meta = runCatching {
            json.decodeFromString<BulletinFileMetadata>(extractMetadataJson(existing.content))
        }.getOrNull() ?: return
        val updated = meta.copy(localPath = localPath)
        messageDao.upsert(existing.copy(content = rebuildContent(existing.content, updated)))
    }

    fun decodeFileMetadata(message: MessageEntity): BulletinFileMetadata? {
        return runCatching {
            json.decodeFromString<BulletinFileMetadata>(extractMetadataJson(message.content))
        }.getOrNull()
    }

    private fun extractMetadataJson(content: String): String {
        val marker = "\n{"
        val idx = content.indexOf(marker)
        return if (idx >= 0) content.substring(idx + 1) else content
    }

    private fun rebuildContent(captionAndJson: String, meta: BulletinFileMetadata): String {
        val marker = "\n{"
        val idx = captionAndJson.indexOf(marker)
        val caption = if (idx >= 0) captionAndJson.substring(0, idx).trim() else ""
        val encoded = json.encodeToString(meta)
        return if (caption.isBlank()) encoded else "$caption\n$encoded"
    }
}

private fun NoteEntity.toMessageEntity(): MessageEntity {
    val contentType = when {
        !attachmentFileName.isNullOrBlank() && !attachmentLocalPath.isNullOrBlank() ->
            BulletinContentType.FILE_METADATA
        else -> BulletinContentType.TEXT
    }
    val content = if (contentType == BulletinContentType.FILE_METADATA) {
        val meta = BulletinFileMetadata(
            fileName = attachmentFileName.orEmpty(),
            sizeBytes = attachmentSizeBytes,
            sha256 = checksum.orEmpty(),
            originNode = sourceDeviceId,
            localPath = attachmentLocalPath,
            driveFileId = driveFileId
        )
        val json = Json { encodeDefaults = true }
        val body = json.encodeToString(meta)
        if (content.isBlank()) body else "${content.trim()}\n$body"
    } else {
        content
    }
    return MessageEntity(
        id = noteId,
        originDeviceId = sourceDeviceId,
        senderName = sourceDeviceName,
        content = content,
        contentType = contentType,
        timestamp = epochMs,
        isDeleted = false,
        isPinned = attachmentPinned
    )
}
