package com.fileapex.data.settings

import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences

private class DesktopSettingsKvStore(
    private val prefs: Preferences
) : SettingsKvStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
    }

    override fun getString(key: String, default: String): String = prefs.get(key, default)
    override fun putString(key: String, value: String) {
        prefs.put(key, value)
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        prefs.putInt(key, value)
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
    }
}

private val desktopSettings = AtomicReference<AppSettings?>(null)

actual fun createAppSettings(): AppSettings {
    return desktopSettings.updateAndGet { existing ->
        existing ?: BaseAppSettings(
            DesktopSettingsKvStore(Preferences.userRoot().node("com.fileapex.settings"))
        )
    }!!
}
