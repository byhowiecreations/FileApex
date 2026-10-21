package com.fileapex.cloud

import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Silent high-priority FCM data messages, sent through the FileApex relay that holds the Firebase key. */
object FcmWakeBackend {
    private val relay = FcmRelayClient(
        httpClient = { FileApexServices.httpClient },
        idToken = { CloudAuthBackend.firebaseIdToken() }
    )

    /** Wakes go through the FileApex relay, which needs a Firebase sign-in; builds without cloud accounts have none. */
    fun isConfigured(): Boolean = CloudAuthBackend.isConfigured()

    suspend fun sendPresenceWake(targetFcmToken: String, sourceDeviceId: String): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank()) return false
        return relay.sendDataWake(
            targetFcmToken,
            buildJsonObject {
                put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_PRESENCE_WAKE)
                put(FcmWakeProtocol.KEY_SOURCE_DEVICE_ID, sourceDeviceId)
                put(FcmWakeProtocol.KEY_EPOCH_MS, TimeUtils.now().toString())
            }
        )
    }

    suspend fun sendDiagnosticsWake(
        targetFcmToken: String,
        sourceDeviceId: String,
        sessionId: String
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank() || sessionId.isBlank()) return false
        return relay.sendDataWake(
            targetFcmToken,
            buildJsonObject {
                put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_DIAGNOSTICS_REQUEST)
                put(FcmWakeProtocol.KEY_SOURCE_DEVICE_ID, sourceDeviceId)
                put(FcmWakeProtocol.KEY_SESSION_ID, sessionId)
                put(FcmWakeProtocol.KEY_EPOCH_MS, TimeUtils.now().toString())
            }
        )
    }

    suspend fun sendNoteWake(
        targetFcmToken: String,
        sourceDeviceId: String,
        noteId: String,
        content: String?,
        driveFileId: String?,
        checksum: String?,
        attachmentName: String? = null,
        attachmentSizeBytes: Long = 0L
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank() || noteId.isBlank()) return false
        val MAX_INLINE_BYTES = 3000
        val contentBytes = content?.toByteArray(Charsets.UTF_8)
        val hasDriveFile = !driveFileId.isNullOrBlank()
        val hasAttachment = !attachmentName.isNullOrBlank() || attachmentSizeBytes > 0L
        val isInline = !hasDriveFile &&
            !hasAttachment &&
            contentBytes != null &&
            contentBytes.size <= MAX_INLINE_BYTES

        val dataObj = buildJsonObject {
            put(
                FcmWakeProtocol.Keys.TYPE,
                if (isInline) FcmWakeProtocol.TYPE_NOTE_INLINE else FcmWakeProtocol.TYPE_NOTE_SYNC
            )
            put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.Keys.NOTE_ID, noteId)
            put(FcmWakeProtocol.Keys.EPOCH_MS, TimeUtils.now().toString())
            if (!content.isNullOrBlank() && (isInline || contentBytes == null || contentBytes.size <= MAX_INLINE_BYTES)) {
                put(FcmWakeProtocol.Keys.CONTENT, content)
            }
            if (hasDriveFile) {
                put(FcmWakeProtocol.Keys.DRIVE_FILE_ID, driveFileId)
                put(FcmWakeProtocol.Keys.CHECKSUM, checksum.orEmpty())
            }
            if (hasAttachment) {
                if (!attachmentName.isNullOrBlank()) {
                    put(FcmWakeProtocol.Keys.ATTACHMENT_NAME, attachmentName)
                }
                if (attachmentSizeBytes > 0L) {
                    put(FcmWakeProtocol.Keys.ATTACHMENT_SIZE, attachmentSizeBytes.toString())
                }
            } else if (!isInline && !hasDriveFile) {
                put(FcmWakeProtocol.Keys.DRIVE_FILE_ID, driveFileId.orEmpty())
                put(FcmWakeProtocol.Keys.CHECKSUM, checksum.orEmpty())
            }
        }
        return relay.sendDataWake(targetFcmToken, dataObj)
    }

    /** A Bulletin Board push whose content is in [sealed]; only the type, note id and sender are readable. */
    suspend fun sendSealedNoteWake(
        targetFcmToken: String,
        sourceDeviceId: String,
        noteId: String,
        type: String,
        sealed: String
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank() || noteId.isBlank() || sealed.isBlank()) return false
        return relay.sendDataWake(
            targetFcmToken,
            buildJsonObject {
                put(FcmWakeProtocol.Keys.TYPE, type)
                put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
                put(FcmWakeProtocol.Keys.NOTE_ID, noteId)
                put(FcmWakeProtocol.Keys.EPOCH_MS, TimeUtils.now().toString())
                put(FcmWakeProtocol.KEY_SENDER_PUBLIC_KEY, com.fileapex.domain.clipboard.ClipboardE2ee.publicKeyBase64())
                put(FcmWakeProtocol.KEY_SEALED, sealed)
            }
        )
    }

    suspend fun sendNoteDeleteWake(
        targetFcmToken: String,
        sourceDeviceId: String,
        noteId: String,
        driveFileId: String? = null,
        checksum: String? = null,
        attachmentName: String? = null
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank() || noteId.isBlank()) return false
        val dataObj = buildJsonObject {
            put(FcmWakeProtocol.Keys.TYPE, FcmWakeProtocol.TYPE_NOTE_DELETE)
            put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.Keys.NOTE_ID, noteId)
            put(FcmWakeProtocol.Keys.ACTION, FcmWakeProtocol.ACTION_RETRACT_MESSAGE)
            if (!driveFileId.isNullOrBlank()) {
                put(FcmWakeProtocol.Keys.DRIVE_FILE_ID, driveFileId)
            }
            if (!checksum.isNullOrBlank()) {
                put(FcmWakeProtocol.Keys.CHECKSUM, checksum)
            }
            if (!attachmentName.isNullOrBlank()) {
                put(FcmWakeProtocol.Keys.ATTACHMENT_NAME, attachmentName)
            }
        }
        return relay.sendDataWake(targetFcmToken, dataObj)
    }

    suspend fun sendDriveRelayPointer(
        targetFcmToken: String,
        sourceDeviceId: String,
        entryId: String
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank()) return false
        val dataObj = buildJsonObject {
            put(FcmWakeProtocol.Keys.TYPE, FcmWakeProtocol.TYPE_DRIVE_RELAY)
            put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_DRIVE_RELAY)
            put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.Keys.ENTRY_ID, entryId)
            put(FcmWakeProtocol.KEY_ENTRY_ID, entryId)
            put(FcmWakeProtocol.Keys.EPOCH_MS, TimeUtils.now().toString())
        }
        return relay.sendDataWake(targetFcmToken, dataObj)
    }

    suspend fun sendClipboardShare(
        targetFcmToken: String,
        sourceDeviceId: String,
        senderDeviceName: String,
        senderPublicKey: String,
        ciphertext: String,
        capturedAtEpochMs: Long
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank() || ciphertext.isBlank() || senderPublicKey.isBlank()) return false
        val dataObj = buildJsonObject {
            put(FcmWakeProtocol.Keys.TYPE, FcmWakeProtocol.TYPE_CLIPBOARD_SHARE)
            put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_CLIPBOARD_SHARE)
            put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.Keys.SENDER_DEVICE_NAME, senderDeviceName)
            put(FcmWakeProtocol.Keys.SENDER_PUBLIC_KEY, senderPublicKey)
            put(FcmWakeProtocol.Keys.CIPHERTEXT, ciphertext)
            put(FcmWakeProtocol.Keys.CAPTURED_AT, capturedAtEpochMs.toString())
            put(FcmWakeProtocol.Keys.EPOCH_MS, TimeUtils.now().toString())
        }
        return relay.sendDataWake(targetFcmToken, dataObj)
    }

    suspend fun sendClipboardAck(
        targetFcmToken: String,
        sourceDeviceId: String,
        capturedAtEpochMs: Long
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank() || sourceDeviceId.isBlank()) return false
        return relay.sendDataWake(
            targetFcmToken,
            buildJsonObject {
                put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_CLIPBOARD_ACK)
                put(FcmWakeProtocol.KEY_SOURCE_DEVICE_ID, sourceDeviceId)
                put(FcmWakeProtocol.KEY_CAPTURED_AT, capturedAtEpochMs.toString())
                put(FcmWakeProtocol.KEY_EPOCH_MS, TimeUtils.now().toString())
            }
        )
    }

    suspend fun sendClipboardOptIn(
        targetFcmToken: String,
        sourceDeviceId: String,
        senderDeviceName: String,
        pendingPayload: com.fileapex.domain.clipboard.ClipboardSendRequest? = null
    ): Boolean {
        if (!isConfigured()) return false
        if (targetFcmToken.isBlank()) return false
        val dataObj = buildJsonObject {
            put(FcmWakeProtocol.Keys.TYPE, FcmWakeProtocol.TYPE_CLIPBOARD_OPT_IN_REQUEST)
            put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_CLIPBOARD_OPT_IN_REQUEST)
            put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.KEY_SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.Keys.SENDER_DEVICE_NAME, senderDeviceName)
            put(FcmWakeProtocol.KEY_SENDER_DEVICE_NAME, senderDeviceName)
            put(FcmWakeProtocol.Keys.EPOCH_MS, TimeUtils.now().toString())
            if (pendingPayload != null && pendingPayload.ciphertext.length <= 2000) {
                put(FcmWakeProtocol.Keys.CIPHERTEXT, pendingPayload.ciphertext)
                put(FcmWakeProtocol.Keys.SENDER_PUBLIC_KEY, pendingPayload.senderPublicKey)
                put(FcmWakeProtocol.Keys.CAPTURED_AT, pendingPayload.capturedAtEpochMs.toString())
            }
        }
        return relay.sendDataWake(targetFcmToken, dataObj)
    }
}
