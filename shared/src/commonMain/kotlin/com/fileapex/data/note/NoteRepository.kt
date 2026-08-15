package com.fileapex.data.note

import com.fileapex.cloud.FcmWakeCoordinator
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
            if (!note.isMine) {
                val preview = note.content.ifBlank { note.attachmentFileName.orEmpty() }
                runCatching { com.fileapex.platform.notifyNoteReceived(note.sourceDeviceName, preview) }
            }
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
            val entry = com.fileapex.cloud.drive.DriveRelayCoordinator.uploadNoteAttachment(
                localPath = localPath,
                displayName = display
            )
            resolvedDriveId = entry.driveFileId
            resolvedChecksum = entry.contentHash
            resolvedName = entry.fileName
            resolvedSize = entry.sizeBytes
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

        FcmWakeCoordinator.dispatchNoteWakeToLinkedPeers(
            noteId = noteId,
            content = caption,
            driveFileId = resolvedDriveId,
            checksum = resolvedChecksum,
            attachmentName = resolvedName,
            attachmentSizeBytes = resolvedSize
        )

        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val devices = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
                for (device in devices) {
                    val host = device.lastKnownIp
                    val port = device.port
                    if (host.isNotBlank() && port > 0) {
                        runCatching {
                            FileApexServices.client.postNote(host, port, record)
                        }
                    }
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
        val currentDao = dao
        if (currentDao != null) {
            runCatching { currentDao.deleteNote(noteId) }
        }
        mutex.withLock {
            val updated = _notes.value.filterNot { it.noteId == noteId }
            _notes.value = updated
        }
    }

    suspend fun deleteNoteFromAllDevices(noteId: String) {
        deleteNote(noteId)

        FcmWakeCoordinator.dispatchNoteDeleteToLinkedPeers(noteId)

        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val devices = FileApexServices.deviceRepositoryOrNull()?.listDevices().orEmpty()
                for (device in devices) {
                    val host = device.lastKnownIp
                    val port = device.port
                    if (host.isNotBlank() && port > 0) {
                        runCatching {
                            FileApexServices.client.postNoteDelete(host, port, noteId)
                        }
                    }
                }
            }
        }
    }

    suspend fun containsNoteOrChecksum(noteId: String, checksum: String?): Boolean {
        val currentDao = dao
        if (currentDao != null && runCatching { currentDao.countNoteOrChecksum(noteId, checksum) }.getOrDefault(0) > 0) {
            return true
        }
        return mutex.withLock {
            _notes.value.any { item ->
                item.noteId == noteId || (!checksum.isNullOrBlank() && item.checksum == checksum)
            }
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
    }

    suspend fun bindDownloadedAttachment(
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
                val sameFile = (!driveFileId.isBlank() && note.driveFileId == driveFileId) ||
                    (!checksum.isBlank() && note.checksum == checksum)
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
        return matched
    }

    private suspend fun hydrateIncomingAttachment(note: NoteRecord) {
        val driveId = note.driveFileId?.takeIf { it.isNotBlank() } ?: return
        val local = note.attachmentLocalPath
        if (!local.isNullOrBlank() && SystemFileSystem.exists(Path(local))) return
        runCatching {
            com.fileapex.cloud.drive.DriveRelayCoordinator.materializeNoteAttachment(note)
        }.onFailure { error ->
            println("NoteRepository: attachment download failed - ${error.message}")
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
