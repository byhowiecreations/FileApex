package com.fileapex.data.identity

import android.content.Context
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.platform.generateDeviceId
import com.fileapex.platform.platformDeviceName

private lateinit var identityContext: Context

fun initAndroidLocalIdentity(context: Context) {
    identityContext = context.applicationContext
}

actual fun loadLocalIdentity(): LocalIdentity {
    check(::identityContext.isInitialized) {
        "Call initAndroidLocalIdentity(context) before loadLocalIdentity()"
    }
    val prefs = identityContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val deviceId = prefs.getString(KEY_ID, null) ?: generateDeviceId().also { id ->
        prefs.edit().putString(KEY_ID, id).apply()
    }
    val storedName = prefs.getString(KEY_NAME, null)?.trim().orEmpty()
    return LocalIdentity(
        deviceId = deviceId,
        deviceName = storedName.ifBlank { platformDeviceName() },
        rootPath = defaultStorageRoot(),
        sharePort = LocalIdentity.DEFAULT_SHARE_PORT
    )
}

actual fun updateLocalDeviceName(newName: String) {
    check(::identityContext.isInitialized) {
        "Call initAndroidLocalIdentity(context) before updateLocalDeviceName()"
    }
    val trimmed = newName.trim()
    require(trimmed.isNotEmpty()) { "Device name cannot be empty" }
    identityContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_NAME, trimmed)
        .apply()
}

private const val PREFS = "fileapex_identity"
private const val KEY_ID = "device_id"
private const val KEY_NAME = "device_name"
