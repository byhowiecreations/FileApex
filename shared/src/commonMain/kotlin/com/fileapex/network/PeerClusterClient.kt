package com.fileapex.network

import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS

class PeerClusterClient internal constructor(private val http: FileApexClient) {
    suspend fun postRemoteRename(
        host: String,
        port: Int,
        newName: String,
        renamedByDeviceId: String = "",
        renamedByDeviceName: String = ""
    ): RenameOutcome {
        val payload = http.json.encodeToString(
            RenameDeviceRequest.serializer(),
            RenameDeviceRequest(
                deviceName = newName.trim(),
                renamedByDeviceId = renamedByDeviceId.trim(),
                renamedByDeviceName = renamedByDeviceName.trim()
            )
        )
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/identity/rename",
            body = payload,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Remote rename failed (${response.statusCode})")
        return RenameConfirmation.fromBody(response.body)
    }
}
