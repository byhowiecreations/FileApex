package com.fileapex.domain.clipboard

import com.fileapex.util.TimeUtils
import com.fileapex.util.sha256Hex

object ClipboardPushDeduper {
    @Volatile
    var isInitializing: Boolean = true
        private set

    @Volatile
    private var lastHash: String? = null

    private val seenHashes = mutableMapOf<String, Long>()
    private val lock = Any()
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            synchronized(lock) {
                if (!loaded) {
                    val persisted = ClipboardDeduperPersistence.load()
                    lastHash = persisted.lastHash
                    seenHashes.putAll(persisted.seenHashes)
                    loaded = true
                }
            }
        }
    }

    fun beginInitialization() {
        isInitializing = true
        ensureLoaded()
    }

    fun endInitialization() {
        isInitializing = false
    }

    fun hashOf(text: String): String = sha256Hex(text.trim().encodeToByteArray())

    fun remember(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        ensureLoaded()
        val hash = hashOf(trimmed)
        val now = TimeUtils.now()
        synchronized(lock) {
            lastHash = hash
            seenHashes[hash] = now
            seenHashes.entries.removeAll { now - it.value > HASH_TTL_MS }
            ClipboardDeduperPersistence.save(lastHash, seenHashes)
        }
    }

    fun isDuplicate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        ensureLoaded()
        val hash = hashOf(trimmed)
        val now = TimeUtils.now()
        synchronized(lock) {
            if (lastHash == hash) return true
            val seenAt = seenHashes[hash] ?: return false
            if (now - seenAt < HASH_TTL_MS) return true
            return false
        }
    }

    fun shouldAllowAutomaticPush(text: String, clipTimestampMs: Long? = null): Boolean {
        if (isInitializing) return false
        if (clipTimestampMs != null && clipTimestampMs > 0L) {
            val ageMs = TimeUtils.now() - clipTimestampMs
            if (ageMs > STALE_CLIP_THRESHOLD_MS) {
                remember(text)
                return false
            }
        }
        return !isDuplicate(text)
    }

    fun shouldAllowManualPush(text: String): Boolean = !isDuplicate(text)

    fun clearSession() {
        synchronized(lock) {
            lastHash = null
            seenHashes.clear()
            ClipboardDeduperPersistence.clear()
            loaded = true
        }
        isInitializing = false
    }

    private const val HASH_TTL_MS = 24 * 60 * 60 * 1000L
    private const val STALE_CLIP_THRESHOLD_MS = 60_000L
}
