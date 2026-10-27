package com.fileapex.network

import com.fileapex.i18n.AppI18n
import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS

class PeerClipboardClient internal constructor(private val http: FileApexClient) {
    suspend fun sendClipboard(
        host: String,
        port: Int,
        senderDeviceId: String,
        senderDeviceName: String,
        senderPublicKey: String,
        ciphertext: String,
        capturedAtEpochMs: Long
    ): com.fileapex.domain.clipboard.ClipboardSendResponse {
        val request = com.fileapex.domain.clipboard.ClipboardSendRequest(
            senderDeviceId = senderDeviceId,
            senderDeviceName = senderDeviceName,
            senderPublicKey = senderPublicKey,
            ciphertext = ciphertext,
            capturedAtEpochMs = capturedAtEpochMs
        )
        val bodyStr = http.json.encodeToString(com.fileapex.domain.clipboard.ClipboardSendRequest.serializer(), request)
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(
                basePath = "/api/v1/clipboard/send",
                host = host,
                port = port
            ),
            body = bodyStr,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            val message = if (response.body.contains("clipboard_disabled")) {
                AppI18n.t("clipboard_disabled_on_peer")
            } else if (response.body.contains("pin_required")) {
                AppI18n.t("pin_required_open_device")
            } else {
                response.body.ifBlank { AppI18n.t("clipboard_disabled_on_peer") }
            }
            error(message)
        }
        http.requireSuccess(response, "Clipboard transfer failed (${response.statusCode})")
        return http.json.decodeFromString(com.fileapex.domain.clipboard.ClipboardSendResponse.serializer(), response.body)
    }

    suspend fun getClipboardStatus(
        host: String,
        port: Int
    ): com.fileapex.domain.clipboard.ClipboardStatusResponse {
        val response = http.boundGet(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(
                basePath = "/api/v1/clipboard/status",
                host = host,
                port = port
            ),
            timeoutMs = 3_000
        )
        http.requireSuccess(response, "Clipboard status check failed (${response.statusCode})")
        return http.json.decodeFromString(com.fileapex.domain.clipboard.ClipboardStatusResponse.serializer(), response.body)
    }

    suspend fun requestClipboardOptIn(
        host: String,
        port: Int,
        senderDeviceId: String,
        senderDeviceName: String,
        pendingPayload: com.fileapex.domain.clipboard.ClipboardSendRequest? = null
    ) {
        val request = com.fileapex.domain.clipboard.ClipboardOptInRequest(
            senderDeviceId = senderDeviceId,
            senderDeviceName = senderDeviceName,
            pendingPayload = pendingPayload
        )
        val bodyStr = http.json.encodeToString(com.fileapex.domain.clipboard.ClipboardOptInRequest.serializer(), request)
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(
                basePath = "/api/v1/clipboard/opt-in-request",
                host = host,
                port = port
            ),
            body = bodyStr,
            contentType = "application/json",
            timeoutMs = 3_000
        )
        http.requireSuccess(response, "Clipboard opt-in request failed (${response.statusCode})")
    }

    suspend fun pullRemoteClipboard(host: String, port: Int): String? {
        val response = http.boundGet(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(
                basePath = "/api/v1/clipboard/current",
                host = host,
                port = port
            ),
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 403) {
            if (response.body.contains("clipboard_disabled")) {
                error(AppI18n.t("clipboard_disabled_on_peer"))
            } else {
                error(AppI18n.t("pin_required_open_device"))
            }
        }
        http.requireSuccess(response, "Clipboard pull failed (${response.statusCode})")
        val root = runCatching { http.json.parseToJsonElement(response.body) }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return null
        val sealed = runCatching {
            http.json.decodeFromString(com.fileapex.domain.clipboard.ClipboardSendRequest.serializer(), response.body)
        }.getOrNull()
        if (sealed != null && sealed.ciphertext.isNotBlank()) {
            val key = com.fileapex.domain.clipboard.ClipboardShareCoordinator
                .verifiedSenderKey(sealed.senderDeviceId, sealed.senderPublicKey)
            return com.fileapex.domain.clipboard.ClipboardE2ee.decrypt(
                ciphertextBase64 = sealed.ciphertext,
                localDeviceId = http.localDeviceId().trim(),
                peerDeviceId = sealed.senderDeviceId.trim(),
                peerPublicKeyBase64 = key
            ).decodeToString()
        }
        // Peers older than this change answer with plain text; shown only, never applied to a clipboard.
        return (root["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
}
