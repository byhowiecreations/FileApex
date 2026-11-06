package com.fileapex.domain.clipboard

data class PersistedDeduperState(
    val lastHash: String?,
    val seenHashes: Map<String, Long>
)

expect object ClipboardDeduperPersistence {
    /** Null when the stored state exists but cannot be opened yet (sealing key not available). */
    fun load(): PersistedDeduperState?
    fun save(lastHash: String?, seenHashes: Map<String, Long>)
    fun clear()
}
