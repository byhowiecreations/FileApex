package com.fileapex.notice

import com.fileapex.data.settings.SettingsKvStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the notice feature remembers, in the same settings store as the rest so Wipe Data clears it. The accepted
 * file is kept in chunks because the desktop preferences backend limits a single value to 8192 characters.
 */
class NoticeSettingsStore(private val store: SettingsKvStore) {
    private val enabledFlow = MutableStateFlow(store.getBoolean(KEY_ENABLED, true))
    private val choiceFlow = MutableStateFlow(store.getBoolean(KEY_CHOICE, false))
    private val revisionFlow = MutableStateFlow(0)

    /** The Critical Notifications setting; off means the app never checks and shows nothing. */
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    /** True once the person has seen the explanation, by answering or dismissing it. No request is made before. */
    val choiceMade: StateFlow<Boolean> = choiceFlow.asStateFlow()

    /** Bumped whenever the accepted file, the dismissed ids or the seen ids change. */
    val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    fun setEnabled(value: Boolean) {
        store.putBoolean(KEY_ENABLED, value)
        enabledFlow.value = value
    }

    fun markChoiceMade() {
        store.putBoolean(KEY_CHOICE, true)
        choiceFlow.value = true
    }

    /** Re-reads every value after the backing store was cleared. */
    fun reload() {
        enabledFlow.value = store.getBoolean(KEY_ENABLED, true)
        choiceFlow.value = store.getBoolean(KEY_CHOICE, false)
        revisionFlow.value++
    }

    fun highestSerial(): Long = store.getLong(KEY_SERIAL, 0L)

    fun etag(): String? = store.getString(KEY_ETAG, "").ifEmpty { null }

    fun lastSuccessMs(): Long = store.getLong(KEY_LAST_SUCCESS, 0L)

    fun lastAttemptMs(): Long = store.getLong(KEY_LAST_ATTEMPT, 0L)

    fun setLastAttemptMs(value: Long) = store.putLong(KEY_LAST_ATTEMPT, value)

    fun setLastSuccessMs(value: Long) = store.putLong(KEY_LAST_SUCCESS, value)

    /** The last accepted file exactly as fetched; it is verified again whenever it is loaded. */
    fun cachedFile(): ByteArray? {
        val count = store.getInt(KEY_CACHE_CHUNKS, 0)
        if (count <= 0) return null
        val text = StringBuilder()
        for (index in 0 until count) {
            if (!store.contains("$KEY_CACHE_PREFIX$index")) return null
            text.append(store.getString("$KEY_CACHE_PREFIX$index", ""))
        }
        return text.toString().encodeToByteArray()
    }

    fun saveAccepted(file: ByteArray, serial: Long, etag: String?, liveIds: Set<String>) {
        val text = file.decodeToString()
        val chunks = text.chunked(CHUNK_CHARS)
        val previous = store.getInt(KEY_CACHE_CHUNKS, 0)
        chunks.forEachIndexed { index, chunk -> store.putString("$KEY_CACHE_PREFIX$index", chunk) }
        for (index in chunks.size until previous) store.remove("$KEY_CACHE_PREFIX$index")
        store.putInt(KEY_CACHE_CHUNKS, chunks.size)
        store.putLong(KEY_SERIAL, serial)
        store.putString(KEY_ETAG, etag.orEmpty())
        writeDismissed(dismissed().filterKeys { it in liveIds })
        writeSeen(seen().filter { it in liveIds }.toSet())
        revisionFlow.value++
    }

    fun dismissed(): Map<String, Long> = store.getString(KEY_DISMISSED, "").split(',')
        .mapNotNull { entry ->
            val at = entry.indexOf('=')
            if (at <= 0) null else entry.substring(0, at) to (entry.substring(at + 1).toLongOrNull() ?: return@mapNotNull null)
        }.toMap()

    /** [forgetSeen] makes the notice count as unread again when it comes back after a dismissal. */
    fun dismiss(id: String, nowMs: Long, forgetSeen: Boolean) {
        writeDismissed(dismissed() + (id to nowMs))
        if (forgetSeen) writeSeen(seen() - id)
        revisionFlow.value++
    }

    fun seen(): Set<String> = store.getString(KEY_SEEN, "").split(',').filter { it.isNotEmpty() }.toSet()

    fun markSeen(ids: Collection<String>) {
        val merged = seen() + ids
        if (merged == seen()) return
        writeSeen(merged)
        revisionFlow.value++
    }

    private fun writeDismissed(map: Map<String, Long>) =
        store.putString(KEY_DISMISSED, map.entries.joinToString(",") { "${it.key}=${it.value}" })

    private fun writeSeen(ids: Set<String>) = store.putString(KEY_SEEN, ids.joinToString(","))

    companion object {
        private const val CHUNK_CHARS = 6000
        private const val KEY_ENABLED = "notice_enabled"
        private const val KEY_CHOICE = "notice_choice_made"
        private const val KEY_SERIAL = "notice_serial"
        private const val KEY_ETAG = "notice_etag"
        private const val KEY_LAST_SUCCESS = "notice_last_success_ms"
        private const val KEY_LAST_ATTEMPT = "notice_last_attempt_ms"
        private const val KEY_DISMISSED = "notice_dismissed"
        private const val KEY_SEEN = "notice_seen"
        private const val KEY_CACHE_CHUNKS = "notice_cache_chunks"
        private const val KEY_CACHE_PREFIX = "notice_cache_"
    }
}
