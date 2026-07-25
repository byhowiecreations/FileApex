package com.fileapex.data.settings

import android.content.Context
import android.content.SharedPreferences

private class AndroidSettingsKvStore(
    private val prefs: SharedPreferences
) : SettingsKvStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}

private lateinit var androidAppContext: Context
private var androidSettings: AppSettings? = null

/** Application context for platform features that need Android APIs (updates, etc.). */
fun androidAppContextOrNull(): Context? {
    return if (::androidAppContext.isInitialized) androidAppContext else null
}

fun initAndroidAppSettings(context: Context) {
    androidAppContext = context.applicationContext
    androidSettings = BaseAppSettings(
        AndroidSettingsKvStore(
            androidAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )
}

actual fun createAppSettings(): AppSettings {
    val existing = androidSettings
    if (existing != null) return existing
    check(::androidAppContext.isInitialized) {
        "Call initAndroidAppSettings(context) before createAppSettings()"
    }
    return BaseAppSettings(
        AndroidSettingsKvStore(
            androidAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    ).also { androidSettings = it }
}

private const val PREFS_NAME = "fileapex_settings"
