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
        return FcmHttpV1Client.sendPresenceWake(
            config = config,
            targetToken = targetFcmToken,
            sourceDeviceId = sourceDeviceId
        )
    }
}

internal object FcmHttpV1Client {
    suspend fun sendPresenceWake(
        config: FcmServiceAccountConfig,
        targetToken: String,
        sourceDeviceId: String
    ): Boolean {
        val accessToken = FcmGoogleOAuth.accessToken(config) ?: return false
        val url = "https://fcm.googleapis.com/v1/projects/${config.projectId}/messages:send"
        val payload = buildJsonObject {
            put(
                "message",
                buildJsonObject {
                    put("token", targetToken)
                    put(
                        "data",
                        buildJsonObject {
                            put(FcmWakeProtocol.KEY_TYPE, FcmWakeProtocol.TYPE_PRESENCE_WAKE)
                            put(FcmWakeProtocol.KEY_SOURCE_DEVICE_ID, sourceDeviceId)
                            put(FcmWakeProtocol.KEY_EPOCH_MS, TimeUtils.now().toString())
                        }
                    )
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
                "FcmWakeBackend: v1 send failed (${response.status}) — " +
                    response.bodyAsText().take(200)
            )
            return false
        }
        return true
    }
}
