package com.fileapex.data.bulletin

import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.note.NoteChecklistItem
import com.fileapex.data.note.NoteRecord
import com.fileapex.util.sha256HexFile
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class BulletinBoardRepository(
    private val database: FileApexDatabase
) {
    private val json = BulletinFileContent.json

    private val pendingRetractByKind = mutableMapOf<String, BulletinRetractByKindPayload>()

    private val messageDao = database.messageDao()
    private val tombstoneDao = database.tombstoneDao()
    private val flagDao = database.messageFlagDao()
    private val checklistDao = database.checklistItemDao()
    private val outboxDao = database.outboxDao()
    private val transactionDao = database.transactionDao()

    val messages: Flow<List<MessageEntity>> = messageDao.observeActive()

    fun observeAsNotes(): Flow<List<NoteRecord>> = combine(messages, checklistDao.observeActive()) { rows, items ->
        val byList = items.groupBy { it.messageId }
        rows.map { row ->
            val note = row.toNoteRecord()
            if (row.contentType != BulletinContentType.CHECKLIST) note
            else note.copy(checklist = byList[row.id].orEmpty().map { NoteChecklistItem(it.itemId, it.text, it.done) })
        }
    }

    suspend fun getMessage(id: String): MessageEntity? = messageDao.getById(id)

    suspend fun ingestLocalBatteryAlert(levelPercent: Int?): MessageEntity {
        val identity = loadLocalIdentity()
        val selfName = LocalDeviceNameStore.current().ifBlank { identity.deviceName }
        val message = MessageEntity(
            id = "msg-" + TimeUtils.now() + "-" + (1000..9999).random(),
            originDeviceId = identity.deviceId,
            senderName = selfName,
            content = BulletinMessageKind.batteryAlertContent(levelPercent),
            contentType = BulletinContentType.BATTERY_LOW,
            timestamp = TimeUtils.now(),
            isDeleted = false,
            isPinned = false
        )
        messageDao.upsert(message.copy(updatedAt = BulletinStampClock.next()))
        return message
    }

    suspend fun applyRetractByKind(
        originDeviceId: String,
        contentType: Int,
        deletedAt: Long
    ): List<String> {
        val id = originDeviceId.trim()
        if (id.isEmpty()) return emptyList()
        val targets = messageDao.getActiveOnce().filter { message ->
            message.originDeviceId == id &&
                BulletinMessageKind.matchesRetract(message.contentType, message.content, contentType)
        }
        if (targets.isEmpty()) return emptyList()
        for (message in targets) {
            transactionDao.applyTombstone(
                TombstoneEntity(
                    id = message.id,
                    deletedAt = deletedAt,
                    originDeviceId = id
                )
            )
        }
        return targets.map { it.id }
    }

    fun rememberRetractByKind(payload: BulletinRetractByKindPayload) {
        val payloadId = BulletinMessageKind.retractPayloadId(payload.originDeviceId, payload.contentType)
        pendingRetractByKind[payloadId] = payload
    }

    fun pendingRetractByKind(payloadId: String): BulletinRetractByKindPayload? =
        pendingRetractByKind[payloadId]

    suspend fun isTombstoned(id: String): Boolean = tombstoneDao.countById(id) > 0

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
        messageDao.upsert(message.copy(updatedAt = BulletinStampClock.next()))
        return message
    }

    /** A new checklist post with its first lines; returns what must be sent to the other devices. */
    suspend fun ingestLocalChecklist(title: String, lines: List<String>): Pair<MessageEntity, List<ChecklistItemEntity>> = withContext(Dispatchers.IO) {
        val identity = loadLocalIdentity()
        val now = TimeUtils.now()
        val message = MessageEntity(
            id = "msg-" + now + "-" + (1000..9999).random(),
            originDeviceId = identity.deviceId,
            senderName = LocalDeviceNameStore.current().ifBlank { identity.deviceName },
            content = title.trim(),
            contentType = BulletinContentType.CHECKLIST,
            timestamp = now
        )
        messageDao.upsert(message.copy(updatedAt = BulletinStampClock.next()))
        val items = lines.map(BulletinChecklist::cleanText).filter { it.isNotEmpty() }.take(BulletinChecklist.MAX_ITEMS)
            .mapIndexed { index, text -> newChecklistItem(message.id, text, now + index, identity.deviceId) }
        items.forEach { transactionDao.applyChecklistItem(it, BulletinStampClock.next()) }
        message to items
    }

    suspend fun addChecklistItem(messageId: String, text: String): ChecklistItemEntity? = withContext(Dispatchers.IO) {
        val clean = BulletinChecklist.cleanText(text)
        if (clean.isEmpty() || checklistDao.countActive(messageId) >= BulletinChecklist.MAX_ITEMS) return@withContext null
        val item = newChecklistItem(messageId, clean, TimeUtils.now(), loadLocalIdentity().deviceId)
        transactionDao.applyChecklistItem(item, BulletinStampClock.next())
        item
    }

    suspend fun setChecklistItemDone(itemId: String, done: Boolean): ChecklistItemEntity? =
        changeChecklistItem(itemId) { it.copy(done = done) }

    suspend fun removeChecklistItem(itemId: String): ChecklistItemEntity? =
        changeChecklistItem(itemId) { it.copy(deleted = true) }

    private suspend fun changeChecklistItem(itemId: String, change: (ChecklistItemEntity) -> ChecklistItemEntity): ChecklistItemEntity? =
        withContext(Dispatchers.IO) {
            val existing = checklistDao.get(itemId) ?: return@withContext null
            val changed = BulletinChecklist.localChange(existing, change(existing), loadLocalIdentity().deviceId, TimeUtils.now())
            transactionDao.applyChecklistItem(changed, BulletinStampClock.next())
            changed
        }

    private fun newChecklistItem(messageId: String, text: String, position: Long, setBy: String) = ChecklistItemEntity(
        itemId = "item-" + position + "-" + (1000..9999).random(),
        messageId = messageId, text = text, done = false, deleted = false,
        position = position, stamp = BulletinStampClock.next(), setBy = setBy
    )

    suspend fun ingestLocalFile(
        absolutePath: String,
        fileName: String,
        sizeBytes: Long,
        caption: String = "",
        messageId: String? = null
    ): MessageEntity = withContext(Dispatchers.IO) {
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
            id = messageId ?: ("msg-" + TimeUtils.now() + "-" + (1000..9999).random()),
            originDeviceId = identity.deviceId,
            senderName = selfName,
            content = if (caption.isNotBlank()) "$caption\n$content" else content,
            contentType = contentType,
            timestamp = TimeUtils.now()
        )
        messageDao.upsert(message.copy(updatedAt = BulletinStampClock.next()))
        message
    }

    suspend fun upsertFromSync(payload: BulletinMessagePayload): Boolean {
        if (tombstoneDao.countById(payload.id) > 0) return false
        val existing = messageDao.getById(payload.id)
        val locked = flagDao.get(BulletinFlags.id(payload.id, BulletinFlags.LOCKED))?.enabled ?: existing?.isPinned ?: false
        val incoming = MessageEntity(
            id = payload.id,
            originDeviceId = payload.originDeviceId,
            senderName = payload.senderName,
            content = stripMissingLocalPath(payload.content, payload.contentType),
            contentType = payload.contentType,
            timestamp = payload.timestamp,
            isDeleted = false,
            isPinned = locked
        )
        BulletinChange.messageToStore(existing, incoming, BulletinStampClock.next())?.let { messageDao.upsert(it) }
        return true
    }

    suspend fun applyTombstone(payload: BulletinTombstonePayload) {
        transactionDao.applyTombstone(
            TombstoneEntity(
                id = payload.id,
                deletedAt = payload.deletedAt,
                originDeviceId = payload.originDeviceId,
                remotePurge = payload.remotePurge
            )
        )
    }

    suspend fun deleteMessageLocalOnly(id: String) {
        val identity = loadLocalIdentity()
        applyTombstone(
            BulletinTombstonePayload(
                id = id,
                deletedAt = TimeUtils.now(),
                originDeviceId = identity.deviceId,
                remotePurge = false
            )
        )
    }

    suspend fun deleteMessage(id: String, remotePurge: Boolean = false) {
        val identity = loadLocalIdentity()
        applyTombstone(
            BulletinTombstonePayload(
                id = id,
                deletedAt = TimeUtils.now(),
                originDeviceId = identity.deviceId,
                remotePurge = remotePurge
            )
        )
    }

    /** Locks or unlocks a message for the whole cluster; returns the flag to send to the other devices. */
    suspend fun setPinned(id: String, pinned: Boolean): MessageFlagEntity = setFlag(id, BulletinFlags.LOCKED, pinned)

    /** Changes one of the cluster-wide marks of a post (locked, task, done); returns the flag to send to the other devices. */
    suspend fun setFlag(id: String, kind: String, enabled: Boolean): MessageFlagEntity = withContext(Dispatchers.IO) {
        val existing = flagDao.get(BulletinFlags.id(id, kind))
        val flag = BulletinFlags.localChange(existing, id, kind, enabled, loadLocalIdentity().deviceId, TimeUtils.now())
        transactionDao.applyFlag(flag, BulletinStampClock.next())
        flag
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

    /** A peer's file path means nothing on this device and must never decide which local file is served. */
    fun stripRemoteLocalPath(content: String, contentType: Int): String {
        if (contentType != BulletinContentType.FILE_METADATA &&
            contentType != BulletinContentType.IMAGE_PREVIEW
        ) {
            return content
        }
        val meta = runCatching {
            json.decodeFromString<BulletinFileMetadata>(extractMetadataJson(content))
        }.getOrNull() ?: return content
        if (meta.localPath.isNullOrBlank()) return content
        return rebuildContent(content, meta.copy(localPath = null))
    }

    fun stripMissingLocalPath(content: String, contentType: Int): String {
        if (contentType != BulletinContentType.FILE_METADATA &&
            contentType != BulletinContentType.IMAGE_PREVIEW
        ) {
            return content
        }
        val meta = runCatching {
            json.decodeFromString<BulletinFileMetadata>(extractMetadataJson(content))
        }.getOrNull() ?: return content
        val stored = meta.localPath?.trim().orEmpty()
        if (stored.isEmpty()) return content
        val exists = runCatching {
            kotlinx.io.files.SystemFileSystem.exists(kotlinx.io.files.Path(stored))
        }.getOrDefault(false)
        if (exists) return content
        return rebuildContent(content, meta.copy(localPath = null))
    }

    private fun extractMetadataJson(content: String): String = BulletinFileContent.metadataJson(content)

    private fun rebuildContent(captionAndJson: String, meta: BulletinFileMetadata): String =
        BulletinFileContent.rebuild(captionAndJson, meta)
}
