package com.fileapex.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.fileapex.platform.log.Log

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

    override fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().commit()
        googleBackup.edit().clear().commit()
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

/** Every setting value sealed with the settings key, the secrets (PIN, private keys, tokens) sealed on their own by the Android Keystore. */
private fun sealedSettingsStore(prefs: SharedPreferences, googleBackup: SharedPreferences): SettingsKvStore {
    val base = AndroidSettingsKvStore(prefs, googleBackup)
    val master = SettingsMasterKey(base, AndroidSettingsSecrets.sealer())
    // The Google link flag, email and uid are mirrored in clear text into the backup file so a restore on a new phone can find them.
    val mirrored = setOf(BaseAppSettings.KEY_GOOGLE, BaseAppSettings.KEY_GOOGLE_EMAIL, BaseAppSettings.KEY_GOOGLE_UID)
    val values = SealedValuesKvStore(base, { master.sealer() }, BaseAppSettings.SECRET_KEYS + SettingsMasterKey.KEY + mirrored)
    values.sealAll()
    return SealingKvStore(values, AndroidSettingsSecrets.sealer(), BaseAppSettings.SECRET_KEYS)
}

private lateinit var androidAppContext: Context
private var androidSettings: AppSettings? = null
private val androidSettingsLock = Any()

/** Application context for platform features that need Android APIs (updates, etc.). */
fun androidAppContextOrNull(): Context? {
    return if (::androidAppContext.isInitialized) androidAppContext else null
}

private fun buildAndroidSettings(): AppSettings {
    val settingsPrefs = androidAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val googleBackup = androidAppContext.getSharedPreferences(GOOGLE_BACKUP_PREFS, Context.MODE_PRIVATE)
    hydrateGoogleBackup(settingsPrefs, googleBackup)
    return BaseAppSettings(
        sealedSettingsStore(settingsPrefs, googleBackup),
        existingInstall = androidAppContext.getDatabasePath("fileapex.db").exists()
    )
}

fun initAndroidAppSettings(context: Context) {
    androidAppContext = context.applicationContext
    synchronized(androidSettingsLock) {
        if (androidSettings == null) androidSettings = buildAndroidSettings()
    }
}

actual fun createAppSettings(): AppSettings {
    check(::androidAppContext.isInitialized) {
        "Call initAndroidAppSettings(context) before createAppSettings()"
    }
    return synchronized(androidSettingsLock) {
        androidSettings ?: buildAndroidSettings().also { androidSettings = it }
    }
}

/**
 * Google Backup includes only [GOOGLE_BACKUP_PREFS]. Hydrate into live settings after a
 * device restore; otherwise seed the backup file from the current link so the next
 * backup carries email/uid.
 */
private fun hydrateGoogleBackup(settings: SharedPreferences, backup: SharedPreferences) {
    val settingsEmail = settings.getString(BaseAppSettings.KEY_GOOGLE_EMAIL, "").orEmpty()
    val backupEmail = backup.getString(BaseAppSettings.KEY_GOOGLE_EMAIL, "").orEmpty()
    val backupLinked = backup.getBoolean(BaseAppSettings.KEY_GOOGLE, false)
    if (settingsEmail.isBlank() && backupEmail.isNotBlank() && backupLinked) {
        settings.edit()
            .putBoolean(BaseAppSettings.KEY_GOOGLE, false)
            .putBoolean(BaseAppSettings.KEY_GOOGLE_RESTORE_PENDING, true)
            .putString(BaseAppSettings.KEY_GOOGLE_BACKUP_EMAIL_HINT, backupEmail)
            .putString(
                BaseAppSettings.KEY_GOOGLE_UID,
                backup.getString(BaseAppSettings.KEY_GOOGLE_UID, "").orEmpty()
            )
            .apply()
        Log.i(GOOGLE_BACKUP_TAG, "Reinstall detected from backup prefs: set restore pending for $backupEmail")
    } else {
        writeGoogleBackup(settings, backup)
    }
}

private fun writeGoogleBackup(settings: SharedPreferences, backup: SharedPreferences) {
    val linked = settings.getBoolean(BaseAppSettings.KEY_GOOGLE, false)
    backup.edit()
        .putBoolean(BaseAppSettings.KEY_GOOGLE, linked)
        .putString(
            BaseAppSettings.KEY_GOOGLE_EMAIL,
            if (linked) settings.getString(BaseAppSettings.KEY_GOOGLE_EMAIL, "").orEmpty() else ""
        )
        .putString(
            BaseAppSettings.KEY_GOOGLE_UID,
            if (linked) settings.getString(BaseAppSettings.KEY_GOOGLE_UID, "").orEmpty() else ""
        )
        .apply()
}

private const val PREFS_NAME = "fileapex_settings"
private const val GOOGLE_BACKUP_PREFS = "fileapex_google_backup"
private const val GOOGLE_BACKUP_TAG = "GoogleBackup"
