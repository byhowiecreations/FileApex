package com.omninode.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private class AndroidAppSettings(context: Context) : AppSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val google = MutableStateFlow(prefs.getBoolean(KEY_GOOGLE, false))
    private val multiCopyIntro = MutableStateFlow(prefs.getBoolean(KEY_MULTI_COPY_INTRO, false))
    private val transferNotifications =
        MutableStateFlow(prefs.getBoolean(KEY_TRANSFER_NOTIFICATIONS, false))
    private val pinRequired = MutableStateFlow(prefs.getBoolean(KEY_PIN_REQUIRED, false))
    private val pin = MutableStateFlow(prefs.getString(KEY_DEVICE_PIN, "") ?: "")
    private val autoUpdate = MutableStateFlow(prefs.getBoolean(KEY_AUTO_UPDATE, false))

    override val googleAccountLinkEnabled: StateFlow<Boolean> = google.asStateFlow()
    override val multiCopyIntroAcknowledged: StateFlow<Boolean> = multiCopyIntro.asStateFlow()
    override val fileTransferNotificationsEnabled: StateFlow<Boolean> =
        transferNotifications.asStateFlow()
    override val pinRequiredEnabled: StateFlow<Boolean> = pinRequired.asStateFlow()
    override val devicePin: StateFlow<String> = pin.asStateFlow()
    override val autoUpdateEnabled: StateFlow<Boolean> = autoUpdate.asStateFlow()

    override fun setGoogleAccountLinkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GOOGLE, enabled).apply()
        google.value = enabled
    }

    override fun setMultiCopyIntroAcknowledged(acknowledged: Boolean) {
        prefs.edit().putBoolean(KEY_MULTI_COPY_INTRO, acknowledged).apply()
        multiCopyIntro.value = acknowledged
    }

    override fun setFileTransferNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRANSFER_NOTIFICATIONS, enabled).apply()
        transferNotifications.value = enabled
    }

    override fun setPinRequiredEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PIN_REQUIRED, enabled).apply()
        pinRequired.value = enabled
    }

    override fun setDevicePin(pinValue: String) {
        val cleaned = pinValue.filter { it.isDigit() }.take(8)
        prefs.edit().putString(KEY_DEVICE_PIN, cleaned).apply()
        pin.value = cleaned
    }

    override fun setAutoUpdateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
        autoUpdate.value = enabled
    }

    private companion object {
        const val PREFS = "omninode_settings"
        const val KEY_GOOGLE = "google_account_link"
        const val KEY_MULTI_COPY_INTRO = "multi_copy_intro_ack"
        const val KEY_TRANSFER_NOTIFICATIONS = "file_transfer_notifications"
        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_DEVICE_PIN = "device_pin"
        const val KEY_AUTO_UPDATE = "auto_update"
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
    androidSettings = AndroidAppSettings(androidAppContext)
}

actual fun createAppSettings(): AppSettings {
    val existing = androidSettings
    if (existing != null) return existing
    check(::androidAppContext.isInitialized) {
        "Call initAndroidAppSettings(context) before createAppSettings()"
    }
    return AndroidAppSettings(androidAppContext).also { androidSettings = it }
}
