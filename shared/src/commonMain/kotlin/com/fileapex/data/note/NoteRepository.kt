package com.fileapex.data.note

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.data.db.NoteDao
import com.fileapex.data.db.toEntity
import com.fileapex.data.db.toRecord
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var dao: NoteDao? = null
    private val retractedKeys = mutableSetOf<String>()
    private val notifiedNoteIds = mutableSetOf<String>()

    fun attachDao(noteDao: NoteDao, scope: CoroutineScope) {
        this.dao = noteDao
        scope.launch(Dispatchers.IO) {
            noteDao.observeAllNotes().collect { entities ->
                mutex.withLock {
                    _notes.value = entities.map { it.toRecord() }
                }
            }
        }
    }

    suspend fun addNote(note: NoteRecord): Boolean {
        if (isRetracted(note.noteId, note.driveFileId, note.checksum)) {
            return false
        }
        val currentDao = dao
        if (currentDao != null) {
            runCatching { currentDao.insertNote(note.toEntity()) }
        }
        val added = mutex.withLock {
            val current = _notes.value
            if (current.any { it.noteId == note.noteId }) {
                return@withLock false
            }
            val updated = (current + listOf(note)).sortedBy { it.epochMs }
            _notes.value = updated
            true
        }
        if (added) {
            hydrateIncomingAttachment(note)
            val latest = mutex.withLock { _notes.value.firstOrNull { it.noteId == note.noteId } } ?: note
            maybeNotifyIncoming(latest)
        }
        return added
    }

    suspend fun sendNote(
        content: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentPath: String? = null,
        attachmentFileName: String? = null,
        attachmentSizeBytes: Long = 0L
    ): NoteRecord {
        val selfIdentity = loadLocalIdentity()
        val selfName = LocalDeviceNameStore.current().ifBlank { selfIdentity.deviceName }
        val noteId = "note-" + TimeUtils.now() + "-" + (1000..9999).random()

        var resolvedDriveId = driveFileId
        var resolvedChecksum = checksum
        var resolvedName = attachmentFileName
        var resolvedSize = attachmentSizeBytes
        var pinned = false
        var localPath = attachmentPath
        if (!attachmentPath.isNullOrBlank()) {
            val display = attachmentFileName
                ?: attachmentPath.substringAfterLast('/').substringAfterLast('\\')
            localPath = copyIntoDownloads(attachmentPath, display)
            val fileLen = SystemFileSystem.metadataOrNull(Path(localPath))?.size
                ?: error("Attachment file not found")
            resolvedName = display
            resolvedSize = fileLen
            if (fileLen > DriveRelayPolicy.NOTES_LAN_ATTACHMENT_MAX_BYTES) {
                require(DriveRelayPolicy.canSend()) {
                    "Offline Notes attachments must be under ${DriveRelayPolicy.lanAttachmentLimitLabel()}"
                }
                require(!DriveRelayPolicy.payloadExceedsRelayLimit(fileLen)) {
                    DriveRelayPolicy.relayLimitExceededMessage(fileLen)
                }
            }
        }

        val caption = content.trim()
        val record = NoteRecord(
            noteId = noteId,
            sourceDeviceId = selfIdentity.deviceId,
            sourceDeviceName = selfName,
            content = caption,
            driveFileId = resolvedDriveId,
            checksum = resolvedChecksum,
            epochMs = TimeUtils.now(),
            isMine = true,
            attachmentFileName = resolvedName,
            attachmentSizeBytes = resolvedSize,
            attachmentPinned = pinned,
            attachmentLocalPath = localPath
        )

        addNote(record)

        val lanAttach = !localPath.isNullOrBlank() &&
            !resolvedName.isNullOrBlank() &&
            resolvedSize <= DriveRelayPolicy.NOTES_LAN_ATTACHMENT_MAX_BYTES

        FcmWakeCoordinator.dispatchNoteWakeToLinkedPeers(
            noteId = noteId,
            content = caption,
            driveFileId = resolvedDriveId,
            checksum = resolvedChecksum,
            attachmentName = resolvedName,
            attachmentSizeBytes = resolvedSize
        )

        CoroutineScope(Dispatchers.IO).launch {
            dispatchNoteToLanPeers(record, lanAttach, localPath, resolvedName)
        }

        if (!localPath.isNullOrBlank() && DriveRelayPolicy.canSend()) {
            val path = localPath
            val display = resolvedName ?: "attachment"
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val entry = com.fileapex.cloud.drive.DriveRelayCoordinator.uploadNoteAttachment(
                        localPath = path,
                        displayName = display,
                        noteId = noteId
                    )
                    applyDriveMetadata(noteId, entry)
                    FcmWakeCoordinator.dispatchNoteWakeToLinkedPeers(
                        noteId = noteId,
                        content = caption,
                        driveFileId = entry.driveFileId,
                        checksum = entry.contentHash,
                        attachmentName = entry.fileName,
                        attachmentSizeBytes = entry.sizeBytes
                    )
                }.onFailure { error ->
                    println("NoteRepository: Drive failsafe upload failed - ${error.message}")
                }
            }
        }

        return record
    }

    suspend fun setAttachmentPinned(noteId: String, pinned: Boolean) {
        val current = notes.value.find { it.noteId == noteId } ?: return
        val updated = current.copy(attachmentPinned = pinned)
        val currentDao = dao
        if (currentDao != null) {
            runCatching { currentDao.insertNote(updated.toEntity()) }
        }
        mutex.withLock {
            _notes.value = _notes.value.map { if (it.noteId == noteId) updated else it }
        }
        val driveId = current.driveFileId
        if (!driveId.isNullOrBlank()) {
            runCatching {
                com.fileapex.cloud.drive.DriveRelayCoordinator.setNoteAttachmentPinned(driveId, pinned)
            }
        }
    }

    suspend fun deleteNote(noteId: String) {
        applyRemoteRetract(noteId)
    }

    suspend fun deleteNoteFromAllDevices(noteId: String) {
        val snapshot = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } }
        val driveFileId = snapshot?.driveFileId?.takeIf { it.isNotBlank() }
        val checksum = snapshot?.checksum?.takeIf { it.isNotBlank() }
        val attachmentName = snapshot?.attachmentFileName?.takeIf { it.isNotBlank() }
        applyRemoteRetract(noteId, driveFileId, checksum, attachmentName)
        if (!driveFileId.isNullOrBlank()) {
            runCatching {
                com.fileapex.cloud.drive.DriveRelayCoordinator.deleteNoteAttachment(driveFileId)
            }
        }

        FcmWakeCoordinator.dispatchNoteDeleteToLinkedPeers(
            noteId = noteId,
            driveFileId = driveFileId,
            checksum = checksum,
            attachmentName = attachmentName
        )

        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val devices = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
                for (device in devices) {
                    val host = device.lastKnownIp
                    val port = device.port
                    if (host.isNotBlank() && port > 0) {
                        runCatching {
                            FileApexServices.client.postNoteDelete(
                                host = host,
                                port = port,
                                noteId = noteId,
                                driveFileId = driveFileId,
                                checksum = checksum,
                                attachmentName = attachmentName
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun applyRemoteRetract(
        noteId: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentName: String? = null
    ) {
        if (noteId.isBlank() && driveFileId.isNullOrBlank() && checksum.isNullOrBlank() &&
            attachmentName.isNullOrBlank()
        ) return
        val matches = mutex.withLock {
            if (noteId.isNotBlank()) retractedKeys += noteId
            driveFileId?.takeIf { it.isNotBlank() }?.let { retractedKeys += it }
            checksum?.takeIf { it.isNotBlank() }?.let { retractedKeys += it }
            val found = matchingNotesLocked(noteId, driveFileId, checksum, attachmentName)
            found.forEach { rememberRetractedLocked(it) }
            found
        }
        val ids = (matches.map { it.noteId } + listOfNotNull(noteId.takeIf { it.isNotBlank() })).distinct()
        val previews = (
            matches.map { note ->
                note.content.ifBlank { note.attachmentFileName.orEmpty() }
            } + listOfNotNull(attachmentName?.takeIf { it.isNotBlank() })
            ).filter { it.isNotBlank() }.distinct()
        runCatching { com.fileapex.platform.retractNoteNotifications(ids, previews) }
        matches.mapNotNull { it.attachmentFileName?.ifBlank { null } }.distinct().forEach { name ->
            runCatching { com.fileapex.platform.DriveRelayNotifier.retractRetrieved(name) }
        }
        val currentDao = dao
        for (id in ids) {
            if (currentDao != null) {
                runCatching { currentDao.deleteNote(id) }
            }
            notifiedNoteIds.remove(id)
        }
        mutex.withLock {
            val drop = ids.toSet()
            _notes.value = _notes.value.filterNot { it.noteId in drop }
        }
    }

    fun isRetracted(noteId: String?, driveFileId: String?, checksum: String?): Boolean {
        if (!noteId.isNullOrBlank() && noteId in retractedKeys) return true
        if (!driveFileId.isNullOrBlank() && driveFileId in retractedKeys) return true
        if (!checksum.isNullOrBlank() && checksum in retractedKeys) return true
        return false
    }

    suspend fun containsNoteOrChecksum(noteId: String, checksum: String?): Boolean {
        if (isRetracted(noteId, null, checksum)) return true
        val currentDao = dao
        if (currentDao != null && runCatching { currentDao.countNoteOrChecksum(noteId, checksum) }.getOrDefault(0) > 0) {
            return true
        }
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
        val currentDao = dao
        mutex.withLock {
            _notes.value = _notes.value.map { note ->
                if (note.noteId != noteId) {
                    note
                } else {
                    val updated = note.copy(attachmentLocalPath = localPath)
                    if (currentDao != null) {
                        runCatching { currentDao.insertNote(updated.toEntity()) }
                    }
                    updated
                }
            }
        }
        val latest = mutex.withLock { _notes.value.firstOrNull { it.noteId == noteId } }
        if (latest != null) maybeNotifyIncoming(latest)
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
        var matched = false
        val currentDao = dao
        mutex.withLock {
            _notes.value = _notes.value.map { note ->
                val sameFile = matchesAttachmentLocked(
                    note,
                    noteId,
                    driveFileId,
                    checksum,
                    fileName,
                    sizeBytes
                )
                if (!sameFile) {
                    note
                } else {
                    matched = true
                    val updated = note.copy(
                        driveFileId = driveFileId.ifBlank { note.driveFileId },
                        checksum = checksum.ifBlank { note.checksum },
                        attachmentFileName = note.attachmentFileName?.ifBlank { null } ?: fileName,
                        attachmentSizeBytes = if (note.attachmentSizeBytes > 0L) note.attachmentSizeBytes else sizeBytes,
                        attachmentPinned = note.attachmentPinned || pinned,
                        attachmentLocalPath = localPath,
                        content = if (note.content == fileName ||
                            note.content.startsWith("[Synced note from Drive:")
                        ) {
                            ""
                        } else {
                            note.content
                        }
                    )
                    if (currentDao != null) {
                        runCatching { currentDao.insertNote(updated.toEntity()) }
                    }
                    updated
                }
            }
        }
        if (matched) {
            val latest = mutex.withLock {
                _notes.value.firstOrNull { note ->
                    matchesAttachmentLocked(note, noteId, driveFileId, checksum, fileName, sizeBytes)
                }
            }
            if (latest != null) maybeNotifyIncoming(latest)
        }
        return matched
    }

    private fun matchingNotesLocked(
        noteId: String,
        driveFileId: String?,
        checksum: String?,
        attachmentName: String? = null
    ): List<NoteRecord> {
        return _notes.value.filter { note ->
            note.noteId == noteId ||
                (!driveFileId.isNullOrBlank() && note.driveFileId == driveFileId) ||
                (!checksum.isNullOrBlank() && note.checksum == checksum) ||
                (!attachmentName.isNullOrBlank() && note.attachmentFileName == attachmentName)
        }
    }

    private fun rememberRetractedLocked(note: NoteRecord) {
        retractedKeys += note.noteId
        note.driveFileId?.takeIf { it.isNotBlank() }?.let { retractedKeys += it }
        note.checksum?.takeIf { it.isNotBlank() }?.let { retractedKeys += it }
    }

    private fun attachmentStillDownloading(note: NoteRecord): Boolean {
        val named = !note.attachmentFileName.isNullOrBlank()
        val driven = !note.driveFileId.isNullOrBlank()
        if (!named && !driven) return false
        val local = note.attachmentLocalPath
        if (local.isNullOrBlank()) return true
        return !SystemFileSystem.exists(Path(local))
    }

    private fun alreadyNotified(note: NoteRecord): Boolean {
        if (note.noteId in notifiedNoteIds) return true
        if (!note.driveFileId.isNullOrBlank() && note.driveFileId in notifiedNoteIds) return true
        if (!note.checksum.isNullOrBlank() && note.checksum in notifiedNoteIds) return true
        return false
    }

    private fun rememberNotified(note: NoteRecord) {
        notifiedNoteIds += note.noteId
        note.driveFileId?.takeIf { it.isNotBlank() }?.let { notifiedNoteIds += it }
        note.checksum?.takeIf { it.isNotBlank() }?.let { notifiedNoteIds += it }
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

    private fun maybeNotifyIncoming(note: NoteRecord) {
        if (note.isMine) return
        if (alreadyNotified(note)) return
        if (isRetracted(note.noteId, note.driveFileId, note.checksum)) return
        if (attachmentStillDownloading(note)) return
        val preview = note.content.ifBlank { note.attachmentFileName.orEmpty() }
        if (preview.isBlank()) return
        rememberNotified(note)
        runCatching {
            com.fileapex.platform.notifyNoteReceived(
                sourceDeviceName = note.sourceDeviceName,
                content = preview,
                noteId = note.noteId
            )
        }
        if (isRetracted(note.noteId, note.driveFileId, note.checksum)) {
            runCatching {
                com.fileapex.platform.retractNoteNotifications(
                    listOf(note.noteId),
                    listOfNotNull(preview.takeIf { it.isNotBlank() }, note.attachmentFileName)
                )
            }
        }
    }

    private suspend fun dispatchNoteToLanPeers(
        record: NoteRecord,
        lanAttach: Boolean,
        localPath: String?,
        fileName: String?
    ) {
        val devices = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
        for (device in devices) {
            val host = device.lastKnownIp
            val port = device.port
            if (host.isBlank() || port <= 0) continue
            runCatching {
                FileApexServices.client.postNote(host, port, record)
                if (lanAttach && !localPath.isNullOrBlank() && !fileName.isNullOrBlank()) {
                    FileApexServices.client.uploadNoteAttachment(
                        host = host,
                        port = port,
                        noteId = record.noteId,
                        fileName = fileName,
                        localSourcePath = localPath
                    )
                }
            }
        }
    }

    private suspend fun applyDriveMetadata(
        noteId: String,
        entry: com.fileapex.cloud.drive.DriveLedgerEntry
    ) {
        val currentDao = dao
        mutex.withLock {
            _notes.value = _notes.value.map { note ->
                if (note.noteId != noteId) {
                    note
                } else {
                    val updated = note.copy(
                        driveFileId = entry.driveFileId,
                        checksum = entry.contentHash,
                        attachmentSizeBytes = if (note.attachmentSizeBytes > 0L) {
                            note.attachmentSizeBytes
                        } else {
                            entry.sizeBytes
                        }
                    )
                    if (currentDao != null) {
                        runCatching { currentDao.insertNote(updated.toEntity()) }
                    }
                    updated
                }
            }
        }
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
}
