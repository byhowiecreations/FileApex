package com.fileapex.data.identity

import android.content.Context
import com.fileapex.data.seal.SealedStrings
import com.fileapex.platform.PrefsStringSlots
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.platform.generateDeviceId
import com.fileapex.platform.platformDeviceName

private lateinit var identityContext: Context

fun initAndroidLocalIdentity(context: Context) {
    identityContext = context.applicationContext
    initAndroidDeviceNamePeerLabels(context)
}

actual fun loadLocalIdentity(): LocalIdentity {
    check(::identityContext.isInitialized) {
        "Call initAndroidLocalIdentity(context) before loadLocalIdentity()"
    }
    val prefs = identityContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val deviceId = com.fileapex.security.LocalIdentityBinding.resolve(
        storedId = prefs.getString(KEY_ID, null),
        fallbackId = ::generateDeviceId,
        persist = { id -> prefs.edit().putString(KEY_ID, id).apply() },
        remember = { previous -> prefs.edit().putString(KEY_PREVIOUS_ID, previous).apply() }
    )
    val storedName = sealedName(prefs).get(KEY_NAME)?.trim().orEmpty()
    return LocalIdentity(
        deviceId = deviceId,
        deviceName = storedName.ifBlank { platformDeviceName() },
        rootPath = defaultStorageRoot(),
        sharePort = LocalIdentity.DEFAULT_SHARE_PORT,
        previousDeviceId = prefs.getString(KEY_PREVIOUS_ID, null)?.trim().orEmpty(),
        deviceNameReadable = storedName.isNotBlank() || !prefs.contains(KEY_NAME)
    )
}

actual fun clearPreviousDeviceId() {
    check(::identityContext.isInitialized) { "Call initAndroidLocalIdentity(context) before clearPreviousDeviceId()" }
    identityContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PREVIOUS_ID).apply()
}

actual fun updateLocalDeviceName(newName: String) {
    check(::identityContext.isInitialized) {
        "Call initAndroidLocalIdentity(context) before updateLocalDeviceName()"
    }
    val trimmed = newName.trim()
    require(trimmed.isNotEmpty()) { com.fileapex.i18n.AppI18n.t("device_name_empty") }
    val saved = sealedName(identityContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).put(KEY_NAME, trimmed)
    check(saved) { "The device name could not be saved: the key store is locked" }
}

// The device name is sealed. The random device id and the previous id stay plain on purpose: a failed read of a sealed id
// would make loadLocalIdentity() invent a new one and break its binding to the E2EE key.
private fun sealedName(prefs: android.content.SharedPreferences) = SealedStrings(PrefsStringSlots(prefs), "identity")

private const val PREFS = "fileapex_identity"
private const val KEY_ID = "device_id"
private const val KEY_PREVIOUS_ID = "previous_device_id"
private const val KEY_NAME = "device_name"
