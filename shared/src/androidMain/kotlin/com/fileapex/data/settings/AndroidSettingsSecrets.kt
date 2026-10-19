package com.fileapex.data.settings

import android.content.SharedPreferences
import com.fileapex.security.VaultSealer
import com.fileapex.security.tls.AndroidKeystoreTlsVault

/** The Android Keystore wraps the settings secrets. */
object AndroidSettingsSecrets {
    fun sealer(): SecretSealer = VaultSealer(AndroidKeystoreTlsVault(AndroidKeystoreTlsVault.SETTINGS_ALIAS))
}

/** A plain SharedPreferences file as a settings store, for files that hold only a few values (OAuth tokens). */
class SharedPreferencesKvStore(private val prefs: SharedPreferences) : SettingsKvStore {
    override fun contains(key: String) = prefs.contains(key)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun clear() { prefs.edit().clear().apply() }
    override fun keys(prefix: String) = prefs.all.keys.filter { it.startsWith(prefix) }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
