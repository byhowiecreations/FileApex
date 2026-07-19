package com.omninode.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences

private class DesktopAppSettings : AppSettings {
    private val prefs = Preferences.userRoot().node("com.omninode.settings")
    private val google = MutableStateFlow(prefs.getBoolean(KEY_GOOGLE, false))
    private val googleEmail = MutableStateFlow(prefs.get(KEY_GOOGLE_EMAIL, ""))
    private val multiCopyIntro = MutableStateFlow(prefs.getBoolean(KEY_MULTI_COPY_INTRO, false))
    private val transferNotifications =
        MutableStateFlow(prefs.getBoolean(KEY_TRANSFER_NOTIFICATIONS, false))
    private val pinRequired = MutableStateFlow(prefs.getBoolean(KEY_PIN_REQUIRED, false))
    private val pin = MutableStateFlow(prefs.get(KEY_DEVICE_PIN, ""))
    private val pinIdle = MutableStateFlow(
        PinIdleTimeout.fromStorage(prefs.get(KEY_PIN_IDLE_TIMEOUT, PinIdleTimeout.DEFAULT.name))
    )
    private val autoUpdate = MutableStateFlow(prefs.getBoolean(KEY_AUTO_UPDATE, false))
    private val updateUnit = MutableStateFlow(
        UpdateCheckUnit.fromStorage(prefs.get(KEY_UPDATE_UNIT, UpdateCheckUnit.Days.name))
    )
    private val updateAmount = MutableStateFlow(
        UpdateCheckFrequency.sanitizeAmount(
            updateUnit.value,
            prefs.getInt(KEY_UPDATE_AMOUNT, 1)
        )
    )
    private val lastUpdateCheck = MutableStateFlow(prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L))

    override val googleAccountLinkEnabled: StateFlow<Boolean> = google.asStateFlow()
    override val googleAccountEmail: StateFlow<String> = googleEmail.asStateFlow()
    override val multiCopyIntroAcknowledged: StateFlow<Boolean> = multiCopyIntro.asStateFlow()
    override val fileTransferNotificationsEnabled: StateFlow<Boolean> =
        transferNotifications.asStateFlow()
    override val pinRequiredEnabled: StateFlow<Boolean> = pinRequired.asStateFlow()
    override val devicePin: StateFlow<String> = pin.asStateFlow()
    override val pinIdleTimeout: StateFlow<PinIdleTimeout> = pinIdle.asStateFlow()
    override val autoUpdateEnabled: StateFlow<Boolean> = autoUpdate.asStateFlow()
    override val autoUpdateIntervalUnit: StateFlow<UpdateCheckUnit> = updateUnit.asStateFlow()
    override val autoUpdateIntervalAmount: StateFlow<Int> = updateAmount.asStateFlow()
    override val lastUpdateCheckEpochMs: StateFlow<Long> = lastUpdateCheck.asStateFlow()

    override fun setGoogleAccountLinkEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_GOOGLE, enabled)
        google.value = enabled
        if (!enabled) {
            setGoogleAccountEmail("")
        }
    }

    override fun setGoogleAccountEmail(email: String) {
        val cleaned = email.trim()
        prefs.put(KEY_GOOGLE_EMAIL, cleaned)
        googleEmail.value = cleaned
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

    override fun setPinIdleTimeout(timeout: PinIdleTimeout) {
        prefs.put(KEY_PIN_IDLE_TIMEOUT, timeout.name)
        pinIdle.value = timeout
    }

    override fun setAutoUpdateEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_AUTO_UPDATE, enabled)
        autoUpdate.value = enabled
    }

    override fun setAutoUpdateInterval(unit: UpdateCheckUnit, amount: Int) {
        val safeAmount = UpdateCheckFrequency.sanitizeAmount(unit, amount)
        prefs.put(KEY_UPDATE_UNIT, unit.name)
        prefs.putInt(KEY_UPDATE_AMOUNT, safeAmount)
        updateUnit.value = unit
        updateAmount.value = safeAmount
    }

    override fun setLastUpdateCheckEpochMs(epochMs: Long) {
        prefs.putLong(KEY_LAST_UPDATE_CHECK, epochMs)
        lastUpdateCheck.value = epochMs
    }

    private companion object {
        const val KEY_GOOGLE = "google_account_link"
        const val KEY_GOOGLE_EMAIL = "google_account_email"
        const val KEY_MULTI_COPY_INTRO = "multi_copy_intro_ack"
        const val KEY_TRANSFER_NOTIFICATIONS = "file_transfer_notifications"
        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_DEVICE_PIN = "device_pin"
        const val KEY_PIN_IDLE_TIMEOUT = "pin_idle_timeout"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_UPDATE_UNIT = "auto_update_unit"
        const val KEY_UPDATE_AMOUNT = "auto_update_amount"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_epoch_ms"
    }
}

private val desktopSettings = AtomicReference<AppSettings?>(null)

actual fun createAppSettings(): AppSettings {
    return desktopSettings.updateAndGet { existing ->
        existing ?: DesktopAppSettings()
    }!!
}
