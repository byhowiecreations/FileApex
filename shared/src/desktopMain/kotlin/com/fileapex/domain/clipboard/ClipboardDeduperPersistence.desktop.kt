package com.fileapex.domain.clipboard

actual object ClipboardDeduperPersistence {
    private var cachedLastHash: String? = null
    private val cachedSeen = mutableMapOf<String, Long>()

    actual fun load(): PersistedDeduperState =
        PersistedDeduperState(cachedLastHash, cachedSeen.toMap())

    actual fun save(lastHash: String?, seenHashes: Map<String, Long>) {
        cachedLastHash = lastHash
        cachedSeen.clear()
        cachedSeen.putAll(seenHashes)
    }

    actual fun clear() {
        cachedLastHash = null
        cachedSeen.clear()
    }
}
