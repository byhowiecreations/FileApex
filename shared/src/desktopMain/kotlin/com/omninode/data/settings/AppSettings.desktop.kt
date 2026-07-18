package com.omninode.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences

private class DesktopAppSettings : AppSettings {
    private val prefs = Preferences.userRoot().node("com.omninode.settings")
    private val google = MutableStateFlow(prefs.getBoolean(KEY_GOOGLE, false))
    private val multiCopyIntro = MutableStateFlow(prefs.getBoolean(KEY_MULTI_COPY_INTRO, false))
    private val transferNotifications =
        MutableStateFlow(prefs.getBoolean(KEY_TRANSFER_NOTIFICATIONS, false))
    private val pinRequired = MutableStateFlow(prefs.getBoolean(KEY_PIN_REQUIRED, false))
    private val pin = MutableStateFlow(prefs.get(KEY_DEVICE_PIN, ""))
    private val autoUpdate = MutableStateFlow(prefs.getBoolean(KEY_AUTO_UPDATE, false))

    override val googleAccountLinkEnabled: StateFlow<Boolean> = google.asStateFlow()
    override val multiCopyIntroAcknowledged: StateFlow<Boolean> = multiCopyIntro.asStateFlow()
    override val fileTransferNotificationsEnabled: StateFlow<Boolean> =
        transferNotifications.asStateFlow()
    override val pinRequiredEnabled: StateFlow<Boolean> = pinRequired.asStateFlow()
    override val devicePin: StateFlow<String> = pin.asStateFlow()
    override val autoUpdateEnabled: StateFlow<Boolean> = autoUpdate.asStateFlow()

    override fun setGoogleAccountLinkEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_GOOGLE, enabled)
        google.value = enabled
    }

    override fun setMultiCopyIntroAcknowledged(acknowledged: Boolean) {
        prefs.putBoolean(KEY_MULTI_COPY_INTRO, acknowledged)
        multiCopyIntro.value = acknowledged
    }

    override fun setFileTransferNotificationsEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_TRANSFER_NOTIFICATIONS, enabled)
        transferNotifications.value = enabled
    }

    override fun setPinRequiredEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_PIN_REQUIRED, enabled)
        pinRequired.value = enabled
    }

    override fun setDevicePin(pinValue: String) {
        val cleaned = pinValue.filter { it.isDigit() }.take(8)
        prefs.put(KEY_DEVICE_PIN, cleaned)
        pin.value = cleaned
    }

    override fun setAutoUpdateEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_AUTO_UPDATE, enabled)
        autoUpdate.value = enabled
    }

    private companion object {
        const val KEY_GOOGLE = "google_account_link"
        const val KEY_MULTI_COPY_INTRO = "multi_copy_intro_ack"
        const val KEY_TRANSFER_NOTIFICATIONS = "file_transfer_notifications"
        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_DEVICE_PIN = "device_pin"
        const val KEY_AUTO_UPDATE = "auto_update"
    }
}

private val desktopSettings = AtomicReference<AppSettings?>(null)

actual fun createAppSettings(): AppSettings {
    return desktopSettings.updateAndGet { existing ->
        existing ?: DesktopAppSettings()
    }!!
}
