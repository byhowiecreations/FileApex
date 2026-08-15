package com.fileapex.cloud

import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** FCM HTTP v1 — silent high-priority data messages via Firebase Admin service account. */
object FcmWakeBackend {
    fun isConfigured(): Boolean = fcmServiceAccountConfig()?.isUsable == true

    suspend fun sendPresenceWake(targetFcmToken: String, sourceDeviceId: String): Boolean {
        val config = fcmServiceAccountConfig()?.takeIf { it.isUsable } ?: return false
        if (targetFcmToken.isBlank()) return false
        return FcmHttpV1Client.sendDataWake(
            config = config,
            targetToken = targetFcmToken,
            data = buildJsonObject {
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
        val config = fcmServiceAccountConfig()?.takeIf { it.isUsable } ?: return false
        if (targetFcmToken.isBlank() || sessionId.isBlank()) return false
        return FcmHttpV1Client.sendDataWake(
            config = config,
            targetToken = targetFcmToken,
            data = buildJsonObject {
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
        val config = fcmServiceAccountConfig()?.takeIf { it.isUsable } ?: return false
        if (targetFcmToken.isBlank() || noteId.isBlank()) return false
        val MAX_INLINE_BYTES = 3000
        val contentBytes = content?.toByteArray(Charsets.UTF_8)
        val hasDriveFile = !driveFileId.isNullOrBlank()
        val isInline = !hasDriveFile &&
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
                if (!attachmentName.isNullOrBlank()) {
                    put(FcmWakeProtocol.Keys.ATTACHMENT_NAME, attachmentName)
                }
                if (attachmentSizeBytes > 0L) {
                    put(FcmWakeProtocol.Keys.ATTACHMENT_SIZE, attachmentSizeBytes.toString())
                }
            } else if (!isInline) {
                put(FcmWakeProtocol.Keys.DRIVE_FILE_ID, driveFileId.orEmpty())
                put(FcmWakeProtocol.Keys.CHECKSUM, checksum.orEmpty())
            }
        }
        return FcmHttpV1Client.sendDataWake(
            config = config,
            targetToken = targetFcmToken,
            data = dataObj
        )
    }

    suspend fun sendNoteDeleteWake(
        targetFcmToken: String,
        sourceDeviceId: String,
        noteId: String
    ): Boolean {
        val config = fcmServiceAccountConfig()?.takeIf { it.isUsable } ?: return false
        if (targetFcmToken.isBlank() || noteId.isBlank()) return false
        val dataObj = buildJsonObject {
            put(FcmWakeProtocol.Keys.TYPE, FcmWakeProtocol.TYPE_NOTE_DELETE)
            put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.Keys.NOTE_ID, noteId)
        }
        return FcmHttpV1Client.sendDataWake(
            config = config,
            targetToken = targetFcmToken,
            data = dataObj
        )
    }

    suspend fun sendDriveRelayPointer(
        targetFcmToken: String,
        sourceDeviceId: String,
        entryId: String
    ): Boolean {
        val config = fcmServiceAccountConfig()?.takeIf { it.isUsable } ?: return false
        if (targetFcmToken.isBlank()) return false
        val dataObj = buildJsonObject {
            put(FcmWakeProtocol.Keys.TYPE, FcmWakeProtocol.TYPE_DRIVE_RELAY)
            put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_DRIVE_RELAY)
            put(FcmWakeProtocol.Keys.SOURCE_DEVICE_ID, sourceDeviceId)
            put(FcmWakeProtocol.Keys.ENTRY_ID, entryId)
            put(FcmWakeProtocol.KEY_ENTRY_ID, entryId)
            put(FcmWakeProtocol.Keys.EPOCH_MS, TimeUtils.now().toString())
        }
        return FcmHttpV1Client.sendDataWake(
            config = config,
            targetToken = targetFcmToken,
            data = dataObj
        )
    }
}

internal object FcmHttpV1Client {
    suspend fun sendDataWake(
        config: FcmServiceAccountConfig,
        targetToken: String,
        data: kotlinx.serialization.json.JsonObject
    ): Boolean {
        val accessToken = FcmGoogleOAuth.accessToken(config) ?: return false
        val url = "https://fcm.googleapis.com/v1/projects/${config.projectId}/messages:send"
        val payload = buildJsonObject {
            put(
                "message",
                buildJsonObject {
                    put("token", targetToken)
                    put("data", data)
                    put(
                        "android",
                        buildJsonObject {
                            put("priority", "HIGH")
                        }
                    )
                }
            )
        }
        val response = FileApexServices.httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        if (!response.status.isSuccess()) {
            println(
                "FcmWakeBackend: v1 send failed (${response.status}) - " +
                    response.bodyAsText().take(200)
            )
            return false
        }
        return true
    }
}
