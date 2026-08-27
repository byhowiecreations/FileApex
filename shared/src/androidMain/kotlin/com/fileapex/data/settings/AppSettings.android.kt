package com.fileapex.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

private class AndroidSettingsKvStore(
    private val prefs: SharedPreferences,
    private val googleBackup: SharedPreferences
) : SettingsKvStore {
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        mirrorGoogleBackupIfNeeded(key)
    }

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        mirrorGoogleBackupIfNeeded(key)
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    private fun mirrorGoogleBackupIfNeeded(key: String) {
        if (key != BaseAppSettings.KEY_GOOGLE &&
            key != BaseAppSettings.KEY_GOOGLE_EMAIL &&
            key != BaseAppSettings.KEY_GOOGLE_UID
        ) {
            return
        }
        writeGoogleBackup(prefs, googleBackup)
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
    val settingsPrefs = androidAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val googleBackup = androidAppContext.getSharedPreferences(GOOGLE_BACKUP_PREFS, Context.MODE_PRIVATE)
    hydrateGoogleBackup(settingsPrefs, googleBackup)
    androidSettings = BaseAppSettings(AndroidSettingsKvStore(settingsPrefs, googleBackup))
}

actual fun createAppSettings(): AppSettings {
    val existing = androidSettings
    if (existing != null) return existing
    check(::androidAppContext.isInitialized) {
        "Call initAndroidAppSettings(context) before createAppSettings()"
    }
    val settingsPrefs = androidAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val googleBackup = androidAppContext.getSharedPreferences(GOOGLE_BACKUP_PREFS, Context.MODE_PRIVATE)
    hydrateGoogleBackup(settingsPrefs, googleBackup)
    return BaseAppSettings(AndroidSettingsKvStore(settingsPrefs, googleBackup))
        .also { androidSettings = it }
}

/**
 * Google Backup includes only [GOOGLE_BACKUP_PREFS]. Hydrate into live settings after a
 * device restore; otherwise seed the backup file from the current link so the next
 * backup carries email/uid.
 */
private fun hydrateGoogleBackup(settings: SharedPreferences, backup: SharedPreferences) {
    val settingsEmail = settings.getString(BaseAppSettings.KEY_GOOGLE_EMAIL, "").orEmpty()
    val backupEmail = backup.getString(BaseAppSettings.KEY_GOOGLE_EMAIL, "").orEmpty()
    if (settingsEmail.isBlank() && backupEmail.isNotBlank()) {
        settings.edit()
            .putBoolean(
                BaseAppSettings.KEY_GOOGLE,
                backup.getBoolean(BaseAppSettings.KEY_GOOGLE, false)
            )
            .putString(BaseAppSettings.KEY_GOOGLE_EMAIL, backupEmail)
            .putString(
                BaseAppSettings.KEY_GOOGLE_UID,
                backup.getString(BaseAppSettings.KEY_GOOGLE_UID, "").orEmpty()
            )
            .apply()
        Log.i(GOOGLE_BACKUP_TAG, "Hydrated Google link from backup prefs")
    } else {
        writeGoogleBackup(settings, backup)
    }
}

private fun writeGoogleBackup(settings: SharedPreferences, backup: SharedPreferences) {
    backup.edit()
        .putBoolean(
            BaseAppSettings.KEY_GOOGLE,
            settings.getBoolean(BaseAppSettings.KEY_GOOGLE, false)
        )
        .putString(
            BaseAppSettings.KEY_GOOGLE_EMAIL,
            settings.getString(BaseAppSettings.KEY_GOOGLE_EMAIL, "").orEmpty()
        )
        .putString(
            BaseAppSettings.KEY_GOOGLE_UID,
            settings.getString(BaseAppSettings.KEY_GOOGLE_UID, "").orEmpty()
        )
        .apply()
}

private const val PREFS_NAME = "fileapex_settings"
private const val GOOGLE_BACKUP_PREFS = "fileapex_google_backup"
private const val GOOGLE_BACKUP_TAG = "GoogleBackup"
