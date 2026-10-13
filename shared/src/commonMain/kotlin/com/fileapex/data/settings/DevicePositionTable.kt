package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device positions for one layout, one settings entry per id ("x|y") instead of a single growing string.
 * A drag only rewrites the entries it moved, and java.util.prefs never sees a value near its 8192-char limit.
 */
internal class DevicePositionTable(
    private val store: SettingsKvStore,
    private val prefix: String,
    importFrom: List<String>,
    removeAfterImport: List<String>,
) {
    private val state = MutableStateFlow(load(importFrom, removeAfterImport))
    val flow: StateFlow<Map<String, Pair<Float, Float>>> = state.asStateFlow()

    fun put(entries: Map<String, Pair<Float, Float>>, persist: Boolean) {
        if (entries.isEmpty()) return
        state.value = state.value + entries
        if (persist) entries.forEach { (id, position) -> write(id, position) }
    }

    fun reset() {
        state.value = emptyMap()
        store.keys(prefix).forEach(store::remove)
    }

    fun forgetInMemory() {
        state.value = emptyMap()
    }

    private fun write(id: String, position: Pair<Float, Float>) {
        if (prefix.length + id.length > MAX_KEY_LENGTH) return
        store.putString(prefix + id, "${position.first}|${position.second}")
    }

    private fun load(importFrom: List<String>, removeAfterImport: List<String>): Map<String, Pair<Float, Float>> {
        val saved = store.keys(prefix).mapNotNull { key ->
            val parts = store.getString(key, "").split("|")
            val x = parts.getOrNull(0)?.toFloatOrNull()
            val y = parts.getOrNull(1)?.toFloatOrNull()
            if (parts.size == 2 && x != null && y != null) key.removePrefix(prefix) to Pair(x, y) else null
        }.toMap()
        if (saved.isNotEmpty()) {
            removeAfterImport.forEach(store::remove)
            return saved
        }
        val legacy = importFrom.firstNotNullOfOrNull { key ->
            decodeKineticOffsets(store.getString(key, "")).takeIf { it.isNotEmpty() }
        }.orEmpty()
        val imported = legacy.filterNot { (id, _) ->
            (id.startsWith("cmp_") && "cmp:" + id.removePrefix("cmp_") in legacy) ||
                (id.startsWith("exp_") && "exp:" + id.removePrefix("exp_") in legacy)
        }
        imported.forEach { (id, position) -> write(id, position) }
        removeAfterImport.forEach(store::remove)
        return imported
    }

    private companion object {
        const val MAX_KEY_LENGTH = 80
    }
}
