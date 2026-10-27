package com.fileapex.network

import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS

class PeerNotificationClient internal constructor(private val http: FileApexClient) {
    suspend fun postNotificationEvent(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.NotificationEventRequest
    ) {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/notifications/event", host = host, port = port),
            body = http.json.encodeToString(com.fileapex.domain.notifications.NotificationEventRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Notification delivery failed (${response.statusCode})")
    }

    suspend fun postNotificationCommand(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.NotificationCommandRequest
    ) {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/notifications/command", host = host, port = port),
            body = http.json.encodeToString(com.fileapex.domain.notifications.NotificationCommandRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Notification command failed (${response.statusCode})")
    }

    suspend fun postPhoneSettingsPrompt(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.PhoneSettingsPromptRequest
    ) {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/notifications/prompt", host = host, port = port),
            body = http.json.encodeToString(com.fileapex.domain.notifications.PhoneSettingsPromptRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Settings prompt failed (${response.statusCode})")
    }

    /** Null when the peer answered but has no notification routes (an older build); throws when it could not be reached. */
    suspend fun getNotificationSyncStatus(
        host: String,
        port: Int
    ): com.fileapex.domain.notifications.NotificationSyncStatus? {
        val response = http.boundGet(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/notifications/status", host = host, port = port),
            timeoutMs = 3_000
        )
        if (response.statusCode == 404) return null
        http.requireSuccess(response, "Notification status check failed (${response.statusCode})")
        return http.json.decodeFromString(
            com.fileapex.domain.notifications.NotificationSyncStatus.serializer(),
            response.body
        )
    }

    suspend fun postNotificationSnapshot(
        host: String,
        port: Int,
        request: com.fileapex.domain.notifications.NotificationSnapshotRequest
    ): com.fileapex.domain.notifications.NotificationSnapshotResponse {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/notifications/snapshot", host = host, port = port),
            body = http.json.encodeToString(com.fileapex.domain.notifications.NotificationSnapshotRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Notification snapshot failed (${response.statusCode})")
        return http.json.decodeFromString(
            com.fileapex.domain.notifications.NotificationSnapshotResponse.serializer(),
            response.body
        )
    }
}
