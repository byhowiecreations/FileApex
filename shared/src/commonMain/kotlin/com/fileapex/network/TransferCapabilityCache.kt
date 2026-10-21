package com.fileapex.network

import com.fileapex.network.FileApexClient.Companion.HEALTH_PROBE_TIMEOUT_MS
import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * What a peer's file server can do (ranged streams, segmented uploads), cached for ten minutes per endpoint.
 */
class TransferCapabilityCache internal constructor(private val http: FileApexClient) {
    private val cache = ConcurrentHashMap<String, Pair<TransferCapabilities, Long>>()

    suspend fun of(host: String, port: Int): TransferCapabilities {
        val key = http.endpointKey(host, port)
        val now = TimeUtils.now()
        cache[key]?.let { (cached, at) ->
            if (now - at < CAPABILITY_CACHE_MS) return cached
        }
        val response = http.boundGet(host, port, "/api/v1/files/capabilities", HEALTH_PROBE_TIMEOUT_MS)
        val capabilities = if (response.statusCode in 200..299) {
            runCatching {
                http.json.decodeFromString(TransferCapabilities.serializer(), response.body)
            }.getOrDefault(TransferCapabilities())
        } else {
            TransferCapabilities()
        }
        cache[key] = capabilities to now
        return capabilities
    }

    private companion object {
private const val CAPABILITY_CACHE_MS = 10 * 60 * 1000L
    }
}
