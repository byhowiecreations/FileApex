package com.fileapex.domain.clipboard

data class PersistedDeduperState(
    val lastHash: String?,
    val seenHashes: Map<String, Long>
)

expect object ClipboardDeduperPersistence {
    fun load(): PersistedDeduperState
    fun save(lastHash: String?, seenHashes: Map<String, Long>)
    fun clear()
}
