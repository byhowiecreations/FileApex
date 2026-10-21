package com.fileapex.update

/** Where [PendingUpdateStore] keeps its values: SharedPreferences on Android, java.util.prefs on desktop. */
internal interface UpdateKeyValueStore {
    fun getString(key: String): String?
    fun getLong(key: String): Long
    fun getStringSet(key: String): Set<String>

    /** Applies every change made in [block] together and saves them before returning. */
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putLong(key: String, value: Long)
        fun putStringSet(key: String, value: Set<String>)
        fun remove(key: String)
    }
}

/** The store for this platform, or null when it is not available yet (Android before the app context exists). */
internal expect fun openUpdateKeyValueStore(): UpdateKeyValueStore?

/** File extensions a downloaded update package can have on this platform. */
internal expect val updatePackageExtensions: List<String>
