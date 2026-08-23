package com.fileapex.cloud

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.cloud.drive.DriveRelayCoordinator
import com.fileapex.data.note.NoteRecord
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.PresenceBackgroundWake
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.BatteryBulletinCoordinator
import com.fileapex.platform.FileApexAndroidBootstrap
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

    override fun onCreate() {
        super.onCreate()
        if (FileApexAndroidBootstrap.ensureInitialized(applicationContext)) {
            BatteryBulletinCoordinator.onProcessStart(applicationContext)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data[FcmWakeProtocol.Keys.TYPE] ?: data[FcmWakeProtocol.KEY_TYPE]
        Log.i(TAG, "FCM received type=$type keys=${data.keys.joinToString()}")
        serviceScope.launch {
            if (!FileApexAndroidBootstrap.ensureInitialized(applicationContext)) {
                Log.e(TAG, "FCM dropped type=$type - process not initialized")
                return@launch
            }
            dispatch(data, type)
        }
    }

    private suspend fun dispatch(data: Map<String, String>, type: String?) {
        val noteId = data[FcmWakeProtocol.Keys.NOTE_ID] ?: data[FcmWakeProtocol.KEY_NOTE_ID]
        val sourceDeviceId = data[FcmWakeProtocol.Keys.SOURCE_DEVICE_ID]
            ?: data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID]
        when {
            FcmWakeCoordinator.isPresenceWake(type) -> handlePresenceWake(data)
            FcmWakeCoordinator.isDiagnosticsWake(type) -> handleDiagnosticsWake(data)
            FcmWakeCoordinator.isNoteInline(type) -> {
                val content = data[FcmWakeProtocol.Keys.CONTENT]
                    ?: data[FcmWakeProtocol.KEY_CONTENT].orEmpty()
                processInlineNote(noteId, sourceDeviceId, content)
            }
            FcmWakeCoordinator.isNoteSync(type) -> {
                val driveFileId = data[FcmWakeProtocol.Keys.DRIVE_FILE_ID]
                    ?: data[FcmWakeProtocol.KEY_DRIVE_FILE_ID].orEmpty()
                val remoteChecksum = data[FcmWakeProtocol.Keys.CHECKSUM]
                    ?: data[FcmWakeProtocol.KEY_CHECKSUM].orEmpty()
                val attachmentName = data[FcmWakeProtocol.Keys.ATTACHMENT_NAME]
                    ?: data[FcmWakeProtocol.KEY_ATTACHMENT_NAME]
                val attachmentSize = (data[FcmWakeProtocol.Keys.ATTACHMENT_SIZE]
                    ?: data[FcmWakeProtocol.KEY_ATTACHMENT_SIZE])
                    ?.toLongOrNull() ?: 0L
                val caption = data[FcmWakeProtocol.Keys.CONTENT]
                    ?: data[FcmWakeProtocol.KEY_CONTENT].orEmpty()
                processDriveSyncNote(
                    noteId = noteId,
                    sourceDeviceId = sourceDeviceId,
                    driveFileId = driveFileId,
                    remoteChecksum = remoteChecksum,
                    caption = caption,
                    attachmentName = attachmentName,
                    attachmentSizeBytes = attachmentSize
                )
            }
            FcmWakeCoordinator.isNoteDelete(
                type,
                data[FcmWakeProtocol.Keys.ACTION] ?: data[FcmWakeProtocol.KEY_ACTION]
            ) -> {
                val driveFileId = data[FcmWakeProtocol.Keys.DRIVE_FILE_ID]
                    ?: data[FcmWakeProtocol.KEY_DRIVE_FILE_ID]
                val checksum = data[FcmWakeProtocol.Keys.CHECKSUM]
                    ?: data[FcmWakeProtocol.KEY_CHECKSUM]
                val attachmentName = data[FcmWakeProtocol.Keys.ATTACHMENT_NAME]
                    ?: data[FcmWakeProtocol.KEY_ATTACHMENT_NAME]
                if (!noteId.isNullOrBlank() || !driveFileId.isNullOrBlank() || !checksum.isNullOrBlank()) {
                    serviceScope.launch {
                        FileApexServices.noteRepository.applyRemoteRetract(
                            noteId = noteId.orEmpty(),
                            driveFileId = driveFileId,
                            checksum = checksum,
                            attachmentName = attachmentName
                        )
                    }
                }
            }
            FcmWakeCoordinator.isDriveRelay(type) -> {
                Log.i(TAG, "Drive relay pointer from $sourceDeviceId")
                DriveRelayCoordinator.onFcmRelayPointer()
            }
            FcmWakeCoordinator.isClipboardShare(type) -> {
                Log.i(TAG, "Clipboard share from $sourceDeviceId")
                com.fileapex.domain.clipboard.ClipboardShareCoordinator.applyFcmInbound(
                    senderDeviceId = sourceDeviceId.orEmpty(),
                    senderDeviceName = data[FcmWakeProtocol.Keys.SENDER_DEVICE_NAME]
                        ?: data[FcmWakeProtocol.KEY_SENDER_DEVICE_NAME].orEmpty(),
                    senderPublicKey = data[FcmWakeProtocol.Keys.SENDER_PUBLIC_KEY]
                        ?: data[FcmWakeProtocol.KEY_SENDER_PUBLIC_KEY].orEmpty(),
                    ciphertext = data[FcmWakeProtocol.Keys.CIPHERTEXT]
                        ?: data[FcmWakeProtocol.KEY_CIPHERTEXT].orEmpty(),
                    capturedAtEpochMs = data[FcmWakeProtocol.Keys.CAPTURED_AT]
                        ?: data[FcmWakeProtocol.KEY_CAPTURED_AT]
                )
            }
            else -> Log.w(TAG, "FCM ignored unknown type=$type")
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
            if (FileApexServices.noteRepository.isRetracted(id, null, null)) return@launch
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
        remoteChecksum: String,
        caption: String,
        attachmentName: String?,
        attachmentSizeBytes: Long
    ) {
        val id = noteId.takeIf { !it.isNullOrBlank() } ?: return
        val sourceId = sourceDeviceId.takeIf { !it.isNullOrBlank() } ?: "unknown"
        Log.i(TAG, "Drive sync note received noteId=$id from $sourceId driveFileId=${driveFileId.ifBlank { "none" }}")
        serviceScope.launch {
            if (FileApexServices.noteRepository.isRetracted(id, driveFileId, remoteChecksum)) return@launch
            // TYPE_NOTE_SYNC with no driveFileId has nothing to fetch — skip the ledger sweep.
            if (driveFileId.isNotBlank()) {
                DriveRelayCoordinator.onFcmRelayPointer()
            } else {
                Log.d(TAG, "Drive sync note has no driveFileId - skipping relay sweep noteId=$id")
            }
            val already = FileApexServices.noteRepository.containsNoteOrChecksum(id, remoteChecksum)
            if (!already) {
                FileApexServices.noteRepository.addNote(
                    NoteRecord(
                        noteId = id,
                        sourceDeviceId = sourceId,
                        sourceDeviceName = "Paired Device",
                        content = caption,
                        driveFileId = driveFileId.ifBlank { null },
                        checksum = remoteChecksum.ifBlank { null },
                        epochMs = TimeUtils.now(),
                        isMine = false,
                        attachmentFileName = attachmentName,
                        attachmentSizeBytes = attachmentSizeBytes
                    )
                )
            }
        }
    }

    override fun onNewToken(token: String) {
        FcmTokenRegistrar.onTokenRefreshed(token)
    }

    companion object {
        private const val TAG = "FileApexFcmMessaging"
    }
}
