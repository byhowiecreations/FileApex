package com.fileapex.update

import java.util.prefs.Preferences

private val desktopUpdateStore = DesktopPreferencesUpdateStore(
    Preferences.userRoot().node("com.fileapex.pending_update")
)

internal actual fun openUpdateKeyValueStore(): UpdateKeyValueStore? = desktopUpdateStore

internal actual val updatePackageExtensions: List<String> = listOf(".apk", ".dmg")

/** Sets are kept as one comma-separated string, the format this store has always used. */
private class DesktopPreferencesUpdateStore(private val prefs: Preferences) : UpdateKeyValueStore {
    override fun getString(key: String): String? = prefs.get(key, null)

    override fun getLong(key: String): Long = prefs.getLong(key, 0L)

    override fun getStringSet(key: String): Set<String> =
        prefs.get(key, "").split(",").filter { it.isNotBlank() }.toSet()

    override fun edit(block: UpdateKeyValueStore.Editor.() -> Unit) {
        object : UpdateKeyValueStore.Editor {
            override fun putString(key: String, value: String) = prefs.put(key, value)
            override fun putLong(key: String, value: Long) = prefs.putLong(key, value)
            override fun putStringSet(key: String, value: Set<String>) = prefs.put(key, value.joinToString(","))
            override fun remove(key: String) = prefs.remove(key)
        }.block()
        prefs.flush()
    }
}
