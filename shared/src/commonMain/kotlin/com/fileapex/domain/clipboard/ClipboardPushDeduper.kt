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

    // Changes made while the stored state could not be opened yet; applied over it when it opens.
    private var unsaved = false
    private val forgottenWhileLocked = mutableSetOf<String>()

    @Volatile
    private var loader: () -> PersistedDeduperState? = { ClipboardDeduperPersistence.load() }

    @Volatile
    private var saver: (String?, Map<String, Long>) -> Unit = { last, seen -> ClipboardDeduperPersistence.save(last, seen) }

    /** Reads the stored state; a state that is still locked is not an empty one, so it is read again on the next call. */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val persisted = loader() ?: return
            persisted.seenHashes.forEach { (hash, at) -> if ((seenHashes[hash] ?: Long.MIN_VALUE) < at) seenHashes[hash] = at }
            forgottenWhileLocked.forEach { seenHashes.remove(it) }
            if (lastHash == null) lastHash = persisted.lastHash?.takeIf { it !in forgottenWhileLocked }
            forgottenWhileLocked.clear()
            loaded = true
            if (unsaved) saveLocked()
            unsaved = false
        }
    }

    private fun saveLocked() {
        if (loaded) saver(lastHash, seenHashes) else unsaved = true
    }

    internal fun resetForTest(loader: () -> PersistedDeduperState?, saver: (String?, Map<String, Long>) -> Unit) {
        synchronized(lock) {
            this.loader = loader
            this.saver = saver
            lastHash = null
            seenHashes.clear()
            forgottenWhileLocked.clear()
            unsaved = false
            loaded = false
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
            saveLocked()
        }
    }

    fun forget(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        ensureLoaded()
        val hash = hashOf(trimmed)
        synchronized(lock) {
            if (lastHash == hash) {
                lastHash = null
            }
            seenHashes.remove(hash)
            if (!loaded) forgottenWhileLocked += hash
            saveLocked()
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
            forgottenWhileLocked.clear()
            unsaved = false
            ClipboardDeduperPersistence.clear()
            loaded = true
        }
        isInitializing = false
    }

    private const val HASH_TTL_MS = 24 * 60 * 60 * 1000L
    private const val STALE_CLIP_THRESHOLD_MS = 60_000L
}
