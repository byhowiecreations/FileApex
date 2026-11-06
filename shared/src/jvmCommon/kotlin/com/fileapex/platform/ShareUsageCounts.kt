package com.fileapex.platform

import com.fileapex.data.seal.SealedStrings
import com.fileapex.data.seal.StringSlots
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * How often each device was a Direct Share target, kept as one sealed blob. Older versions kept one plain
 * `share_count_<deviceId>` slot per device; those are folded into the blob the first time it is saved.
 */
class ShareUsageCounts(private val sealed: SealedStrings, private val legacy: StringSlots) {
    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    fun count(deviceId: String): Int = load()[deviceId] ?: 0

    fun record(deviceId: String) {
        val next = load().toMutableMap()
        next[deviceId] = (next[deviceId] ?: 0) + 1
        save(next)
    }

    fun clear(deviceId: String) {
        val next = load().toMutableMap()
        if (next.remove(deviceId) != null) save(next)
    }

    private fun load(): Map<String, Int> {
        sealed.get(BLOB)?.let { return runCatching { Json.decodeFromString(serializer, it) }.getOrDefault(emptyMap()) }
        return legacy.keys().filter { it.startsWith(LEGACY_PREFIX) }
            .mapNotNull { key -> legacy.get(key)?.toIntOrNull()?.let { key.removePrefix(LEGACY_PREFIX) to it } }
            .toMap()
    }

    private fun save(counts: Map<String, Int>) {
        if (!sealed.put(BLOB, Json.encodeToString(serializer, counts))) return
        legacy.keys().filter { it.startsWith(LEGACY_PREFIX) }.forEach(legacy::remove)
    }

    private companion object {
        const val BLOB = "counts"
        const val LEGACY_PREFIX = "share_count_"
    }
}
