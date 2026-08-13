package com.fileapex.cloud

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.domain.presence.PresenceBackgroundWake
import com.fileapex.network.ServerLifecycleManager

import com.fileapex.data.note.NoteRecord
import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Silent FCM data handler — wakes Doze'd instances for targeted peer health checks (Path A).
 * No notification channel; high-priority data-only payloads per [FcmWakeProtocol].
 */
class FileApexFcmMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data[FcmWakeProtocol.Keys.TYPE] ?: data[FcmWakeProtocol.KEY_TYPE]
        val noteId = data[FcmWakeProtocol.Keys.NOTE_ID] ?: data[FcmWakeProtocol.KEY_NOTE_ID]
        val sourceDeviceId = data[FcmWakeProtocol.Keys.SOURCE_DEVICE_ID] ?: data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID]

        when {
            FcmWakeCoordinator.isPresenceWake(type) -> handlePresenceWake(data)
            FcmWakeCoordinator.isDiagnosticsWake(type) -> handleDiagnosticsWake(data)
            FcmWakeCoordinator.isNoteInline(type) -> {
                val content = data[FcmWakeProtocol.Keys.CONTENT] ?: data[FcmWakeProtocol.KEY_CONTENT].orEmpty()
                processInlineNote(noteId, sourceDeviceId, content)
            }
            FcmWakeCoordinator.isNoteSync(type) -> {
                val driveFileId = data[FcmWakeProtocol.Keys.DRIVE_FILE_ID] ?: data[FcmWakeProtocol.KEY_DRIVE_FILE_ID].orEmpty()
                val remoteChecksum = data[FcmWakeProtocol.Keys.CHECKSUM] ?: data[FcmWakeProtocol.KEY_CHECKSUM].orEmpty()
                processDriveSyncNote(noteId, sourceDeviceId, driveFileId, remoteChecksum)
            }
            FcmWakeCoordinator.isNoteDelete(type) -> {
                if (!noteId.isNullOrBlank()) {
                    serviceScope.launch {
                        FileApexServices.noteRepository.deleteNote(noteId)
                    }
                }
            }
        }
    }

    private fun handlePresenceWake(data: Map<String, String>) {
        Log.i(TAG, "Presence wake received from ${data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID]}")
        ServerLifecycleManager.ensureRunning { logMessage, error ->
            if (error != null) {
                Log.e(TAG, logMessage, error)
            } else {
                Log.i(TAG, logMessage)
            }
        }
        PresenceBackgroundWake.onRemoteWakeSignal(data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID])
    }

    private fun handleDiagnosticsWake(data: Map<String, String>) {
        val sessionId = data[FcmWakeProtocol.KEY_SESSION_ID].orEmpty()
        Log.i(
            TAG,
            "Diagnostics wake session=$sessionId from ${data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID]}"
        )
        ServerLifecycleManager.ensureRunning { logMessage, error ->
            if (error != null) {
                Log.e(TAG, logMessage, error)
            } else {
                Log.i(TAG, logMessage)
            }
        }
        DiagnosticsCloudRelay.onDiagnosticsWake(sessionId)
    }

    private fun processInlineNote(noteId: String?, sourceDeviceId: String?, content: String) {
        val id = noteId.takeIf { !it.isNullOrBlank() } ?: return
        val sourceId = sourceDeviceId.takeIf { !it.isNullOrBlank() } ?: "unknown"
        Log.i(TAG, "Inline note received noteId=$id from $sourceId")
        serviceScope.launch {
            FileApexServices.noteRepository.addNote(
                NoteRecord(
                    noteId = id,
                    sourceDeviceId = sourceId,
                    sourceDeviceName = "Paired Device",
                    content = content,
                    epochMs = TimeUtils.now(),
                    isMine = false
                )
            )
        }
    }

    private fun processDriveSyncNote(
        noteId: String?,
        sourceDeviceId: String?,
        driveFileId: String,
        remoteChecksum: String
    ) {
        val id = noteId.takeIf { !it.isNullOrBlank() } ?: return
        val sourceId = sourceDeviceId.takeIf { !it.isNullOrBlank() } ?: "unknown"
        Log.i(TAG, "Drive sync note received noteId=$id driveFileId=$driveFileId from $sourceId")
        serviceScope.launch {
            if (FileApexServices.noteRepository.containsNoteOrChecksum(id, remoteChecksum)) {
                Log.i(TAG, "Skipping duplicate drive sync noteId=$id")
                return@launch
            }
            FileApexServices.noteRepository.addNote(
                NoteRecord(
                    noteId = id,
                    sourceDeviceId = sourceId,
                    sourceDeviceName = "Paired Device",
                    content = "[Synced note from Drive: $driveFileId]",
                    driveFileId = driveFileId,
                    checksum = remoteChecksum,
                    epochMs = TimeUtils.now(),
                    isMine = false
                )
            )
        }
    }

    override fun onNewToken(token: String) {
        FcmTokenRegistrar.onTokenRefreshed(token)
    }

    companion object {
        private const val TAG = "FileApexFcmMessaging"
    }
}
