package com.fileapex.update

import android.content.Context
import android.content.SharedPreferences
import com.fileapex.data.settings.androidAppContextOrNull

internal actual fun openUpdateKeyValueStore(): UpdateKeyValueStore? {
    val context = androidAppContextOrNull() ?: return null
    return SharedPreferencesUpdateStore(context.getSharedPreferences("fileapex_pending_update", Context.MODE_PRIVATE))
}

internal actual val updatePackageExtensions: List<String> = listOf(".apk")

private class SharedPreferencesUpdateStore(private val prefs: SharedPreferences) : UpdateKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getLong(key: String): Long = prefs.getLong(key, 0L)

    override fun getStringSet(key: String): Set<String> = prefs.getStringSet(key, emptySet()) ?: emptySet()

    override fun edit(block: UpdateKeyValueStore.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : UpdateKeyValueStore.Editor {
            override fun putString(key: String, value: String) { editor.putString(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putStringSet(key: String, value: Set<String>) { editor.putStringSet(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        }.block()
        editor.commit()
    }
}
