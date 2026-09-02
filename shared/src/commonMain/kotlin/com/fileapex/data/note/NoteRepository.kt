package com.fileapex.data.note

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.data.bulletin.BulletinBoardRepository
import com.fileapex.data.bulletin.BulletinBoardSyncEngine
import com.fileapex.data.bulletin.BulletinContentType
import com.fileapex.data.bulletin.BulletinMessageKind
import com.fileapex.data.bulletin.BulletinSenderPolicy
import com.fileapex.data.bulletin.BulletinFileMetadata
import com.fileapex.data.bulletin.toNoteRecord
import com.fileapex.data.db.NoteDao
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.platform.textContainsWebUrl
import com.fileapex.update.BulletinApkUpdateCoordinator
import com.fileapex.update.BulletinApkUpdatePolicy
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

class NoteRepository {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _notes = MutableStateFlow<List<NoteRecord>>(emptyList())
    val notes: StateFlow<List<NoteRecord>> = _notes.asStateFlow()

    @Volatile
    private var legacyDao: NoteDao? = null

    @Volatile
    private var bulletinRepository: BulletinBoardRepository? = null

    @Volatile
    private var bulletinSyncEngine: BulletinBoardSyncEngine? = null

    private val notifiedNoteIds = mutableSetOf<String>()

    private val _downloadingAttachmentIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingAttachmentIds: StateFlow<Set<String>> = _downloadingAttachmentIds.asStateFlow()

    @Volatile
    private var appScope: CoroutineScope? = null

    fun attachLegacyDao(noteDao: NoteDao, scope: CoroutineScope) {
        this.legacyDao = noteDao
        appScope = scope
    }

    fun attachBulletinBoard(
        repository: BulletinBoardRepository,
        syncEngine: BulletinBoardSyncEngine,
        scope: CoroutineScope
    ) {
        this.bulletinRepository = repository
        this.bulletinSyncEngine = syncEngine
        appScope = scope
        scope.launch(Dispatchers.IO) {
            combine(
                repository.observeAsNotes(),
                FileApexServices.deviceRepository.observeDevices()
            ) { records, _ -> records }
                .collect { records ->
                    val resolved = records.map { resolveNoteForDisplay(it) }
                    mutex.withLock {
                        _notes.value = resolved
                    }
                }
        }
    }

    suspend fun addNote(note: NoteRecord): Boolean {
        val named = note.copy(sourceDeviceName = resolveSenderName(note))
        val bulletin = bulletinRepository
        if (bulletin != null) {
            if (bulletin.isTombstoned(named.noteId)) return false
            val payload = com.fileapex.data.bulletin.BulletinMessagePayload(
                id = named.noteId,
                originDeviceId = named.sourceDeviceId,
                senderName = named.sourceDeviceName,
                content = encodeBulletinContent(named),
                contentType = bulletinContentType(named),
                timestamp = named.epochMs,
                isPinned = named.attachmentPinned
            )
            if (!bulletin.upsertFromSync(payload)) return false
            val shouldAutoUpdate = !named.isMine && BulletinApkUpdatePolicy.shouldAutoUpdateNote(
                named.attachmentFileName,
                named.noteId,
                named.epochMs,
                named.attachmentSizeBytes
            )
            if (shouldAutoUpdate) {
                BulletinApkUpdateCoordinator.handleIncomingApkUpdate(named)
            } else {
                maybeNotifyIncoming(named.copy(isMine = named.sourceDeviceId == loadLocalIdentity().deviceId))
            }
            return true
        }
        return addNoteLegacy(named)
    }

    suspend fun sendNote(
        content: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentPath: String? = null,
        attachmentFileName: String? = null,
        attachmentSizeBytes: Long = 0L
    ): NoteRecord = withContext(Dispatchers.Default) {
        val bulletin = bulletinRepository
        val syncEngine = bulletinSyncEngine
        if (bulletin != null && syncEngine != null) {
            val message = if (!attachmentPath.isNullOrBlank()) {
                val display = attachmentFileName
                    ?: attachmentPath.substringAfterLast('/').substringAfterLast('\\')
                val fileLen = SystemFileSystem.metadataOrNull(Path(attachmentPath))?.size
                    ?: error("Attachment file not found")
                bulletin.ingestLocalFile(attachmentPath, display, fileLen, content.trim())
            } else {
                val link = textContainsWebUrl(content.trim())
                bulletin.ingestLocalText(content.trim(), link = link)
            }
            syncEngine.publishMessage(message)
            return@withContext mutex.withLock {
                _notes.value.firstOrNull { it.noteId == message.id }
            } ?: message.toNoteRecordFallback()
        }
        sendNoteLegacy(
            content,
            driveFileId,
            checksum,
            attachmentPath,
            attachmentFileName,
            attachmentSizeBytes
        )
    }

    suspend fun setAttachmentPinned(noteId: String, pinned: Boolean) {
        bulletinRepository?.setPinned(noteId, pinned)
            ?: setAttachmentPinnedLegacy(noteId, pinned)
    }

    suspend fun deleteNote(noteId: String) {
        val bulletin = bulletinRepository
        if (bulletin != null) {
            retractedKeys += noteId
            bulletin.deleteMessageLocalOnly(noteId)
            val snapshot = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } }
            retractNotifications(snapshot, noteId)
            return
        }
        deleteNoteFromAllDevicesLegacy(noteId)
    }

    suspend fun deleteNoteFromAllDevices(noteId: String, remotePurge: Boolean = false) {
        val bulletin = bulletinRepository
        val syncEngine = bulletinSyncEngine
        if (bulletin != null && syncEngine != null) {
            retractedKeys += noteId
            bulletin.deleteMessage(noteId, remotePurge = remotePurge)
            syncEngine.publishTombstone(noteId)
            val snapshot = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } }
            retractNotifications(snapshot, noteId)
            return
        }
        deleteNoteFromAllDevicesLegacy(noteId)
    }

    suspend fun sendBatteryAlert(levelPercent: Int?): NoteRecord {
        val bulletin = bulletinRepository
        val syncEngine = bulletinSyncEngine
        if (bulletin != null && syncEngine != null) {
            val message = bulletin.ingestLocalBatteryAlert(levelPercent)
            syncEngine.publishMessage(message)
            return mutex.withLock {
                _notes.value.firstOrNull { it.noteId == message.id }
            } ?: message.toNoteRecord()
        }
        return sendNoteLegacy(
            content = BulletinMessageKind.batteryAlertContent(levelPercent),
            driveFileId = null,
            checksum = null,
            attachmentPath = null,
            attachmentFileName = null,
            attachmentSizeBytes = 0L
        )
    }

    suspend fun retractBulletinsByKind(originDeviceId: String, contentType: Int) {
        val id = originDeviceId.trim()
        if (id.isEmpty()) return
        val bulletin = bulletinRepository
        val syncEngine = bulletinSyncEngine
        if (bulletin != null && syncEngine != null) {
            val deletedAt = TimeUtils.now()
            val retractedIds = bulletin.applyRetractByKind(id, contentType, deletedAt)
            syncEngine.publishRetractByKind(id, contentType, deletedAt)
            for (noteId in retractedIds) {
                syncEngine.publishTombstone(noteId)
            }
            onBulletinMessagesRetracted(retractedIds)
            return
        }
    }

    suspend fun applyRemoteRetract(
        noteId: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentName: String? = null
    ) {
        val bulletin = bulletinRepository
        if (bulletin != null && noteId.isNotBlank()) {
            retractedKeys += noteId
            bulletin.applyTombstone(
                com.fileapex.data.bulletin.BulletinTombstonePayload(
                    id = noteId,
                    deletedAt = TimeUtils.now(),
                    originDeviceId = ""
                )
            )
            val snapshot = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } }
            retractNotifications(snapshot, noteId, attachmentName)
            return
        }
        applyRemoteRetractLegacy(noteId, driveFileId, checksum, attachmentName)
    }

    fun isRetracted(noteId: String?, driveFileId: String?, checksum: String?): Boolean {
        if (!noteId.isNullOrBlank() && noteId in retractedKeys) return true
        if (!driveFileId.isNullOrBlank() && driveFileId in retractedKeys) return true
        if (!checksum.isNullOrBlank() && checksum in retractedKeys) return true
        return false
    }

    suspend fun containsNoteOrChecksum(noteId: String, checksum: String?): Boolean {
        if (isRetracted(noteId, null, checksum)) return true
        return mutex.withLock {
            _notes.value.any { item ->
                (!noteId.isBlank() && item.noteId == noteId) ||
                    (!checksum.isNullOrBlank() && item.checksum == checksum)
            }
        }
    }

    suspend fun existingLocalAttachment(
        noteId: String?,
        driveFileId: String?,
        checksum: String?,
        fileName: String?,
        sizeBytes: Long
    ): String? {
        return mutex.withLock {
            val note = _notes.value.firstOrNull { item ->
                matchesAttachmentLocked(item, noteId, driveFileId, checksum, fileName, sizeBytes)
            } ?: return@withLock null
            val path = note.attachmentLocalPath?.takeIf { it.isNotBlank() } ?: return@withLock null
            if (SystemFileSystem.exists(Path(path))) path else null
        }
    }

    suspend fun setAttachmentLocalPath(noteId: String, localPath: String) {
        val previous = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } }
        val alreadyHadLocalFile = previous?.let { resolveLocalAttachmentPath(it) } != null
        bulletinRepository?.bindLocalPath(noteId, localPath)
        mutex.withLock {
            _notes.value = _notes.value.map { note ->
                if (note.noteId != noteId) note else note.copy(attachmentLocalPath = localPath)
            }
        }
        val latest = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } } ?: return
        val fileName = latest.attachmentFileName.orEmpty()
        if (!latest.isMine && com.fileapex.update.BulletinApkUpdatePolicy.shouldAutoUpdateNote(
                fileName,
                noteId,
                latest.epochMs,
                latest.attachmentSizeBytes
            )
        ) {
            val version = com.fileapex.update.BulletinApkUpdatePolicy.extractVersionFromApkName(fileName) ?: "v0.0.0"
            com.fileapex.update.BulletinApkUpdateCoordinator.triggerDirectApkInstall(localPath, version, fileName)
        } else if (NoteNotifyPolicy.shouldNotifyAttachmentReady(latest.isMine, alreadyHadLocalFile, fileName)) {
            runCatching { com.fileapex.platform.notifyFilesReceived(listOf(fileName)) }
        }
    }

    suspend fun bindDownloadedAttachment(
        noteId: String = "",
        driveFileId: String,
        checksum: String,
        localPath: String,
        fileName: String,
        sizeBytes: Long,
        pinned: Boolean
    ): Boolean {
        val id = noteId.ifBlank {
            mutex.withLock {
                _notes.value.firstOrNull { note ->
                    matchesAttachmentLocked(note, null, driveFileId, checksum, fileName, sizeBytes)
                }?.noteId
            }.orEmpty()
        }
        if (id.isNotBlank()) {
            setAttachmentLocalPath(id, localPath)
            if (pinned) setAttachmentPinned(id, true)
            return true
        }
        return false
    }

    suspend fun requestFullFile(noteId: String): String? {
        return bulletinSyncEngine?.requestFullFile(noteId)
    }

    suspend fun fetchAttachmentIfNeeded(noteId: String): String? {
        val note = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } } ?: return null
        resolveLocalAttachmentPath(note)?.let { return it }
        if (noteId in _downloadingAttachmentIds.value) return null

        _downloadingAttachmentIds.update { it + noteId }
        return try {
            if (!note.driveFileId.isNullOrBlank()) {
                hydrateIncomingAttachment(note)
            } else if (!note.attachmentFileName.isNullOrBlank()) {
                requestFullFile(noteId)?.also { path ->
                    setAttachmentLocalPath(noteId, path)
                }
            } else {
                null
            }
            mutex.withLock {
                _notes.value.firstOrNull { it.noteId == noteId }
            }?.let { resolveLocalAttachmentPath(it) }
        } finally {
            _downloadingAttachmentIds.update { it - noteId }
        }
    }

    fun attachmentNeedsDownload(note: NoteRecord): Boolean {
        if (note.attachmentFileName.isNullOrBlank()) return false
        return resolveLocalAttachmentPath(note) == null
    }

    private suspend fun retractNotifications(
        snapshot: NoteRecord?,
        noteId: String,
        attachmentName: String? = null
    ) {
        val previews = buildList {
            snapshot?.content?.takeIf { it.isNotBlank() }?.let { add(it) }
            snapshot?.attachmentFileName?.takeIf { it.isNotBlank() }?.let { add(it) }
            attachmentName?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()
        runCatching { com.fileapex.platform.retractNoteNotifications(listOf(noteId), previews) }
        notifiedNoteIds.remove(noteId)
        com.fileapex.update.PendingUpdateStore.removeProcessedNote(noteId)
        val pending = com.fileapex.update.PendingUpdateStore.load()
        if (pending != null && (pending.assetName == snapshot?.attachmentFileName || pending.assetName == attachmentName)) {
            com.fileapex.update.PendingUpdateStore.save(null)
            com.fileapex.platform.dismissAppUpdateNotification()
        }
        val localPath = snapshot?.attachmentLocalPath?.takeIf { it.isNotBlank() }
        if (localPath != null && (snapshot.attachmentFileName?.let { com.fileapex.update.BulletinApkUpdatePolicy.matchesAutoUpdateApk(it) } == true)) {
            runCatching { java.io.File(localPath).delete() }
        }
    }

    private fun bulletinContentType(note: NoteRecord): Int {
        return if (!note.attachmentFileName.isNullOrBlank()) {
            BulletinContentType.FILE_METADATA
        } else if (textContainsWebUrl(note.content)) {
            BulletinContentType.LINK
        } else {
            BulletinContentType.TEXT
        }
    }

    private fun encodeBulletinContent(note: NoteRecord): String {
        if (note.attachmentFileName.isNullOrBlank()) return note.content
        val meta = BulletinFileMetadata(
            fileName = note.attachmentFileName,
            sizeBytes = note.attachmentSizeBytes,
            sha256 = note.checksum.orEmpty(),
            originNode = note.sourceDeviceId,
            localPath = note.attachmentLocalPath,
            driveFileId = note.driveFileId
        )
        val body = json.encodeToString(BulletinFileMetadata.serializer(), meta)
        return if (note.content.isBlank()) body else "${note.content.trim()}\n$body"
    }

    private fun com.fileapex.data.bulletin.MessageEntity.toNoteRecordFallback(): NoteRecord {
        val selfId = loadLocalIdentity().deviceId
        return NoteRecord(
            noteId = id,
            sourceDeviceId = originDeviceId,
            sourceDeviceName = senderName,
            content = content,
            epochMs = timestamp,
            isMine = originDeviceId == selfId
        )
    }

    private fun matchesAttachmentLocked(
        note: NoteRecord,
        noteId: String?,
        driveFileId: String?,
        checksum: String?,
        fileName: String? = null,
        sizeBytes: Long = 0L
    ): Boolean {
        if (!noteId.isNullOrBlank() && note.noteId == noteId) return true
        if (!driveFileId.isNullOrBlank() && note.driveFileId == driveFileId) return true
        if (!checksum.isNullOrBlank() && note.checksum == checksum) return true
        if (!fileName.isNullOrBlank() &&
            note.attachmentFileName == fileName &&
            sizeBytes > 0L &&
            note.attachmentSizeBytes == sizeBytes
        ) {
            return true
        }
        return false
    }

    suspend fun onPeerBulletinBatchIngested(
        newMessages: List<com.fileapex.data.bulletin.MessageEntity>,
        tombstones: List<com.fileapex.data.bulletin.TombstoneEntity>,
    ) {
        for (message in newMessages) {
            val note = message.toNoteRecord()
            val shouldAutoUpdate = !note.isMine && BulletinApkUpdatePolicy.shouldAutoUpdateNote(
                note.attachmentFileName,
                note.noteId,
                note.epochMs,
                note.attachmentSizeBytes
            )
            if (shouldAutoUpdate) {
                BulletinApkUpdateCoordinator.handleIncomingApkUpdate(note)
            } else {
                maybeNotifyIncoming(note)
            }
        }
        for (tombstone in tombstones) {
            retractedKeys += tombstone.id
            val snapshot = mutex.withLock { _notes.value.firstOrNull { it.noteId == tombstone.id } }
            retractNotifications(snapshot, tombstone.id)
        }
    }

    suspend fun onPeerBulletinKindRetracted(retractedMessageIds: List<String>) {
        onBulletinMessagesRetracted(retractedMessageIds)
    }

    private suspend fun onBulletinMessagesRetracted(retractedMessageIds: List<String>) {
        for (noteId in retractedMessageIds.distinct()) {
            if (noteId.isBlank()) continue
            retractedKeys += noteId
            val snapshot = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } }
            retractNotifications(snapshot, noteId)
        }
    }

    private suspend fun maybeNotifyIncoming(note: NoteRecord) {
        val preview = NoteNotifyPolicy.incomingPreview(note.content, note.attachmentFileName)
        if (!NoteNotifyPolicy.shouldNotifyIncomingNote(
                isMine = note.isMine,
                alreadyNotified = note.noteId in notifiedNoteIds,
                preview = preview
            )
        ) {
            return
        }
        notifiedNoteIds += note.noteId
        val sender = resolveSenderName(note)
        val critical = NoteNotifyPolicy.isCriticalBulletin(preview, note.contentType)
        runCatching {
            com.fileapex.platform.notifyNoteReceived(
                sourceDeviceName = sender,
                content = preview,
                noteId = note.noteId,
                critical = critical,
            )
        }
    }

    private suspend fun resolveSenderName(note: NoteRecord): String {
        if (note.isMine) return note.sourceDeviceName
        return BulletinSenderPolicy.displayName(note.sourceDeviceId, note.sourceDeviceName)
    }

    private suspend fun resolveNoteForDisplay(note: NoteRecord): NoteRecord {
        if (note.isMine) return note
        val displayName = resolveSenderName(note)
        val content = if (note.contentType == BulletinContentType.BATTERY_LOW) {
            note.content
        } else {
            NoteNotifyPolicy.rewriteBatteryDeviceName(
                content = note.content,
                storedName = note.sourceDeviceName,
                displayName = displayName
            )
        }
        return if (displayName == note.sourceDeviceName && content == note.content) {
            note
        } else {
            note.copy(sourceDeviceName = displayName, content = content)
        }
    }

    private fun resolveLocalAttachmentPath(note: NoteRecord): String? {
        val path = note.attachmentLocalPath?.takeIf { it.isNotBlank() } ?: return null
        return path.takeIf { SystemFileSystem.exists(Path(it)) }
    }

    private fun shouldAutoFetchAttachment(note: NoteRecord): Boolean {
        val name = note.attachmentFileName.orEmpty().trim()
        if (!note.isMine && BulletinApkUpdatePolicy.shouldAutoUpdateNote(
                name,
                note.noteId,
                note.epochMs,
                note.attachmentSizeBytes
            )
        ) {
            return attachmentNeedsDownload(note)
        }
        return false
    }

    private suspend fun hydrateIncomingAttachment(note: NoteRecord) {
        val driveId = note.driveFileId?.takeIf { it.isNotBlank() } ?: return
        var attempt = 0
        while (true) {
            val latest = mutex.withLock { _notes.value.firstOrNull { it.noteId == note.noteId } } ?: note
            val local = latest.attachmentLocalPath
            if (!local.isNullOrBlank() && SystemFileSystem.exists(Path(local))) return
            val path = runCatching {
                com.fileapex.cloud.drive.DriveRelayCoordinator.materializeNoteAttachment(latest)
            }.getOrElse { error ->
                println("NoteRepository: attachment download attempt ${attempt + 1} failed - ${error.message}")
                null
            }
            if (!path.isNullOrBlank() && SystemFileSystem.exists(Path(path))) return
            if (attempt >= DriveRelayPolicy.RECEIVE_RETRIES) return
            delay(DriveRelayPolicy.receiveRetryDelayMs())
            attempt += 1
        }
    }

    // Legacy fallbacks when bulletin board is not initialized (tests / early boot).
    private val retractedKeys = mutableSetOf<String>()

    private suspend fun addNoteLegacy(note: NoteRecord): Boolean {
        if (note.noteId in retractedKeys) return false
        val currentDao = legacyDao
        if (currentDao != null) {
            runCatching { currentDao.insertNote(note.toLegacyEntity()) }
        }
        val added = mutex.withLock {
            val current = _notes.value
            if (current.any { it.noteId == note.noteId }) return@withLock false
            _notes.value = (current + listOf(note)).sortedBy { it.epochMs }
            true
        }
        if (added) maybeNotifyIncoming(note)
        return added
    }

    private suspend fun sendNoteLegacy(
        content: String,
        driveFileId: String?,
        checksum: String?,
        attachmentPath: String?,
        attachmentFileName: String?,
        attachmentSizeBytes: Long
    ): NoteRecord {
        val selfIdentity = loadLocalIdentity()
        val selfName = LocalDeviceNameStore.current().ifBlank { selfIdentity.deviceName }
        val noteId = "note-" + TimeUtils.now() + "-" + (1000..9999).random()
        var localPath = attachmentPath
        var resolvedName = attachmentFileName
        var resolvedSize = attachmentSizeBytes
        if (!attachmentPath.isNullOrBlank()) {
            val display = attachmentFileName
                ?: attachmentPath.substringAfterLast('/').substringAfterLast('\\')
            localPath = copyIntoDownloads(attachmentPath, display)
            resolvedName = display
            resolvedSize = SystemFileSystem.metadataOrNull(Path(localPath))?.size ?: resolvedSize
        }
        val record = NoteRecord(
            noteId = noteId,
            sourceDeviceId = selfIdentity.deviceId,
            sourceDeviceName = selfName,
            content = content.trim(),
            driveFileId = driveFileId,
            checksum = checksum,
            epochMs = TimeUtils.now(),
            isMine = true,
            attachmentFileName = resolvedName,
            attachmentSizeBytes = resolvedSize,
            attachmentLocalPath = localPath
        )
        addNoteLegacy(record)
        return record
    }

    private suspend fun setAttachmentPinnedLegacy(noteId: String, pinned: Boolean) {
        val current = _notes.value.find { it.noteId == noteId } ?: return
        val updated = current.copy(attachmentPinned = pinned)
        legacyDao?.insertNote(updated.toLegacyEntity())
        mutex.withLock {
            _notes.value = _notes.value.map { if (it.noteId == noteId) updated else it }
        }
    }

    private suspend fun deleteNoteFromAllDevicesLegacy(noteId: String) {
        applyRemoteRetractLegacy(noteId)
        FcmWakeCoordinator.dispatchNoteDeleteToLinkedPeers(noteId = noteId)
    }

    private suspend fun applyRemoteRetractLegacy(
        noteId: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentName: String? = null
    ) {
        if (noteId.isNotBlank()) retractedKeys += noteId
        legacyDao?.deleteNote(noteId)
        mutex.withLock {
            _notes.value = _notes.value.filterNot { it.noteId == noteId }
        }
        retractNotifications(null, noteId, attachmentName)
    }

    private fun copyIntoDownloads(sourceAbsolutePath: String, fileName: String): String {
        val dest = UniqueFileNames.resolveInDirectory(defaultDownloadsDir(), fileName)
        val from = Path(sourceAbsolutePath)
        val to = Path(dest)
        if (from.toString() == to.toString()) return dest
        to.parent?.let { parent ->
            if (!SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
        }
        SystemFileSystem.source(from).buffered().use { input ->
            SystemFileSystem.sink(to).buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (!input.exhausted()) {
                    val n = input.readAtMostTo(buffer)
                    if (n > 0) output.write(buffer, 0, n)
                }
            }
        }
        return dest
    }

    private fun NoteRecord.toLegacyEntity() = com.fileapex.data.db.NoteEntity(
        noteId = noteId,
        sourceDeviceId = sourceDeviceId,
        sourceDeviceName = sourceDeviceName,
        content = content,
        driveFileId = driveFileId,
        checksum = checksum,
        epochMs = epochMs,
        isMine = isMine,
        attachmentFileName = attachmentFileName,
        attachmentSizeBytes = attachmentSizeBytes,
        attachmentPinned = attachmentPinned,
        attachmentLocalPath = attachmentLocalPath
    )
}
