package com.fileapex.data.note

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.data.db.NoteDao
import com.fileapex.data.db.toEntity
import com.fileapex.data.db.toRecord
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        return mutex.withLock {
            val current = _notes.value
            if (current.any { it.noteId == note.noteId }) {
                return@withLock false
            }
            val updated = (current + listOf(note)).sortedBy { it.epochMs }
            _notes.value = updated
            true
        }
    }

    suspend fun sendNote(
        content: String,
        driveFileId: String? = null,
        checksum: String? = null
    ): NoteRecord {
        val selfIdentity = loadLocalIdentity()
        val selfName = LocalDeviceNameStore.current().ifBlank { selfIdentity.deviceName }
        val noteId = "note-" + TimeUtils.now() + "-" + (1000..9999).random()

        val record = NoteRecord(
            noteId = noteId,
            sourceDeviceId = selfIdentity.deviceId,
            sourceDeviceName = selfName,
            content = content,
            driveFileId = driveFileId,
            checksum = checksum,
            epochMs = TimeUtils.now(),
            isMine = true
        )

        addNote(record)

        // 1. Dispatch silent FCM wake to linked peers (for sleeping Android devices)
        FcmWakeCoordinator.dispatchNoteWakeToLinkedPeers(
            noteId = noteId,
            content = content,
            driveFileId = driveFileId,
            checksum = checksum
        )

        // 2. Direct P2P push to all online/paired devices (Mac, Desktop & active Android devices)
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

        // 1. FCM deletion push for Android sleeping devices
        FcmWakeCoordinator.dispatchNoteDeleteToLinkedPeers(noteId)

        // 2. Direct P2P delete for online paired devices
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
}
