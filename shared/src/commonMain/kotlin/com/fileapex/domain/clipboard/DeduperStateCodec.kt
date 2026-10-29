package com.fileapex.domain.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The deduper state as one text, so the platform stores it sealed instead of as separate preference entries. */
object DeduperStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Stored(val last: String? = null, val seen: Map<String, Long> = emptyMap())

    fun encode(lastHash: String?, seenHashes: Map<String, Long>): String =
        json.encodeToString(Stored.serializer(), Stored(lastHash, seenHashes))

    fun decode(text: String): PersistedDeduperState {
        val stored = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull()
            ?: return PersistedDeduperState(null, emptyMap())
        return PersistedDeduperState(
            lastHash = stored.last?.trim()?.takeIf { it.isNotEmpty() },
            seenHashes = stored.seen.filter { (hash, ts) -> hash.isNotBlank() && ts > 0L }
        )
    }
}
