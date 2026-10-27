package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.util.cancellableCatching
import kotlinx.serialization.builtins.ListSerializer
import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS

class PeerClusterClient internal constructor(private val http: FileApexClient) {
    /** The removal records a peer holds inside the fingerprint window. */
    suspend fun fetchRemovedDevices(
        host: String,
        port: Int
    ): List<com.fileapex.domain.pairing.RemovedDeviceRecord> {
        val response = http.boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/devices/removed",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Removed devices fetch failed (${response.statusCode})")
        return http.json.decodeFromString(
            ListSerializer(com.fileapex.domain.pairing.RemovedDeviceRecord.serializer()),
            response.body
        )
    }

    /** Introductions sealed for [selfDeviceId] by the peer at [host]. Only that peer's pair key opens them. */
    suspend fun fetchIntroductions(
        host: String,
        port: Int,
        selfDeviceId: String
    ): List<com.fileapex.security.SealedIntroduction> {
        val response = http.boundGet(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/cluster/introductions?for=${java.net.URLEncoder.encode(selfDeviceId, "UTF-8")}",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        http.requireSuccess(response, "Introductions fetch failed (${response.statusCode})")
        return http.json.decodeFromString(
            ListSerializer(com.fileapex.security.SealedIntroduction.serializer()),
            response.body
        )
    }

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

    suspend fun postClusterSync(
        host: String,
        port: Int,
        request: ClusterSyncRequest
    ): List<PairedDeviceEntity> {
        val payload = http.json.encodeToString(ClusterSyncRequest.serializer(), request)
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = "/api/v1/devices/merge",
            body = payload,
            contentType = "application/json",
            timeoutMs = FileApexClient.CLUSTER_SYNC_TIMEOUT_MS
        )
        http.requireSuccess(response, "Cluster sync failed (${response.statusCode})")
        return runCatching {
            http.json.decodeFromString(ListSerializer(PairedDeviceEntity.serializer()), response.body)
        }.getOrDefault(emptyList())
    }

    suspend fun postClusterRemove(
        host: String,
        port: Int,
        record: com.fileapex.domain.pairing.RemovedDeviceRecord
    ): Boolean {
        val payload = http.json.encodeToString(com.fileapex.domain.pairing.RemovedDeviceRecord.serializer(), record)
        val response = cancellableCatching {
            http.boundPost(
                host = host,
                port = port,
                pathWithQuery = "/api/v1/cluster/remove",
                body = payload,
                contentType = "application/json",
                timeoutMs = FileApexClient.CLUSTER_SYNC_TIMEOUT_MS
            )
        }.getOrNull()
        if (response != null && response.statusCode in 200..299) return true
        val fallback = cancellableCatching {
            http.boundPost(
                host = host,
                port = port,
                pathWithQuery = "/cluster/remove",
                body = payload,
                contentType = "application/json",
                timeoutMs = FileApexClient.CLUSTER_SYNC_TIMEOUT_MS
            )
        }.getOrNull()
        return fallback != null && fallback.statusCode in 200..299
    }
}
