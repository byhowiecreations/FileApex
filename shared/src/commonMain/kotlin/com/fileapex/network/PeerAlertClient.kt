package com.fileapex.network

import com.fileapex.network.FileApexClient.Companion.PEER_REQUEST_TIMEOUT_MS

class PeerAlertClient internal constructor(private val http: FileApexClient) {
    suspend fun triggerDeviceBeep(host: String, port: Int, continuous: Boolean = false): Boolean =
        triggerDeviceBeepStatus(host, port, continuous) in 200..299

    /** HTTP status of the beep request; throws when the peer cannot be reached. */
    suspend fun triggerDeviceBeepStatus(host: String, port: Int, continuous: Boolean = false): Int {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(
                basePath = "/api/v1/device/beep",
                host = host,
                port = port,
                params = if (continuous) mapOf("continuous" to "1") else emptyMap()
            ),
            body = "{}",
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode
    }

    suspend fun reportLocateFound(host: String, port: Int): Boolean {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/device/beep/found", host = host, port = port),
            body = "{}",
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode in 200..299
    }

    suspend fun stopDeviceBeep(host: String, port: Int): Boolean {
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(basePath = "/api/v1/device/beep/stop", host = host, port = port),
            body = "{}",
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode in 200..299
    }

    suspend fun sendDirectAlert(host: String, port: Int, title: String, text: String): Boolean {
        val bodyStr = """{"title":${http.json.encodeToString(title)},"text":${http.json.encodeToString(text)}}"""
        val response = http.boundPost(
            host = host,
            port = port,
            pathWithQuery = http.queryPath(
                basePath = "/api/v1/device/alert",
                host = host,
                port = port
            ),
            body = bodyStr,
            contentType = "application/json",
            timeoutMs = PEER_REQUEST_TIMEOUT_MS
        )
        return response.statusCode in 200..299
    }
}
