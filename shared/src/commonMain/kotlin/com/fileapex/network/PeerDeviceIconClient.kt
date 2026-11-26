package com.fileapex.network

import com.fileapex.domain.devices.DeviceIconSyncRequest
import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS

class PeerDeviceIconClient internal constructor(private val http: FileApexClient) {
    /** False when the peer is an older build without the route; throws when it could not be reached or refused. */
    suspend fun postDeviceIcon(host: String, port: Int, request: DeviceIconSyncRequest): Boolean {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/device-icon", host = host, port = port),
            body = http.json.encodeToString(DeviceIconSyncRequest.serializer(), request),
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        if (response.statusCode == 404) return false
        http.requireSuccess(response, "Device icon sync failed (${response.statusCode})")
        return true
    }
}
