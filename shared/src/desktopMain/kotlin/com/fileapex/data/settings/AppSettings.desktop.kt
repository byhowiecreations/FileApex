package com.fileapex.data.settings

import com.fileapex.util.cancellableCatching
import java.util.prefs.Preferences

internal class DesktopSettingsKvStore(
    private val prefs: Preferences
) : SettingsKvStore {
    override fun contains(key: String): Boolean = prefs.get(key, null) != null
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

    override fun keys(prefix: String): List<String> = prefs.keys().filter { it.startsWith(prefix) }
    override fun remove(key: String) {
        prefs.remove(key)
    }

    override fun clear() {
        cancellableCatching { prefs.clear() }
    }
}

/** Every value sealed with the settings key, the secrets (PIN, private keys, tokens) sealed on their own by the platform. */
private fun sealedSettingsStore(base: SettingsKvStore): SettingsKvStore {
    val master = SettingsMasterKey(base, DesktopSettingsSecrets.sealer())
    val values = SealedValuesKvStore(base, { master.sealer() }, BaseAppSettings.SECRET_KEYS + SettingsMasterKey.KEY)
    values.sealAll()
    // Counts only, no keys and no values: lets a lost theme or layout be told apart from a store that never opened.
    val health = values.health()
    com.fileapex.platform.DesktopLifecycleLog.log("settings: key ${master.outcome}, $health")
    if (health.unreadableKeys.isNotEmpty()) {
        com.fileapex.platform.DesktopLifecycleLog.log("settings: cannot be opened (names only): ${health.unreadableKeys.joinToString(", ")}")
    }
    return SealingKvStore(values, DesktopSettingsSecrets.sealer(), BaseAppSettings.SECRET_KEYS)
}

/** Built once: two threads asking for the settings together must not each open and key the store. */
private val desktopSettings = com.fileapex.util.SingleInstance<AppSettings> {
    BaseAppSettings(
        sealedSettingsStore(DesktopSettingsKvStore(Preferences.userRoot().node("com.fileapex.settings"))),
        existingInstall = com.fileapex.platform.DesktopPlatformPaths.databaseFile().exists()
    )
}

actual fun createAppSettings(): AppSettings = desktopSettings.get()
