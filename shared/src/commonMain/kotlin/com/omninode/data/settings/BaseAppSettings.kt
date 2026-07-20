package com.omninode.data.settings

import com.omninode.util.TimestampDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic key/value persistence for [BaseAppSettings].
 */
interface SettingsKvStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

/**
 * Shared AppSettings logic — Android/Desktop only supply a [SettingsKvStore].
 */
class BaseAppSettings(
    private val store: SettingsKvStore
) : AppSettings {
    private val google = MutableStateFlow(store.getBoolean(KEY_GOOGLE, false))
    private val googleEmail = MutableStateFlow(store.getString(KEY_GOOGLE_EMAIL, ""))
    private val googleUid = MutableStateFlow(store.getString(KEY_GOOGLE_UID, ""))
    private val multiCopyIntro = MutableStateFlow(store.getBoolean(KEY_MULTI_COPY_INTRO, false))
    private val transferNotifications =
        MutableStateFlow(store.getBoolean(KEY_TRANSFER_NOTIFICATIONS, false))
    private val pinRequired = MutableStateFlow(store.getBoolean(KEY_PIN_REQUIRED, false))
    private val pin = MutableStateFlow(store.getString(KEY_DEVICE_PIN, ""))
    private val pinIdle = MutableStateFlow(
        PinIdleTimeout.fromStorage(store.getString(KEY_PIN_IDLE_TIMEOUT, PinIdleTimeout.DEFAULT.name))
    )
    private val checkForUpdates = MutableStateFlow(store.getBoolean(KEY_CHECK_FOR_UPDATES, false))
    private val updateUnit = MutableStateFlow(
        UpdateCheckUnit.fromStorage(store.getString(KEY_UPDATE_UNIT, UpdateCheckUnit.Days.name))
    )
    private val updateAmount = MutableStateFlow(
        UpdateCheckFrequency.sanitizeAmount(
            updateUnit.value,
            store.getInt(KEY_UPDATE_AMOUNT, 1)
        )
    )
    private val lastUpdateCheck = MutableStateFlow(store.getLong(KEY_LAST_UPDATE_CHECK, 0L))
    private val serviceWatchdog = MutableStateFlow(store.getBoolean(KEY_SERVICE_WATCHDOG, true))
    private val desktopLayout = MutableStateFlow(
        DesktopLayoutMode.fromStorage(store.getString(KEY_DESKTOP_LAYOUT, DesktopLayoutMode.DEFAULT.name))
    )

    override val googleAccountLinkEnabled: StateFlow<Boolean> = google.asStateFlow()
    override val googleAccountEmail: StateFlow<String> = googleEmail.asStateFlow()
    override val googleAccountUid: StateFlow<String> = googleUid.asStateFlow()
    override val multiCopyIntroAcknowledged: StateFlow<Boolean> = multiCopyIntro.asStateFlow()
    override val fileTransferNotificationsEnabled: StateFlow<Boolean> =
        transferNotifications.asStateFlow()
    override val pinRequiredEnabled: StateFlow<Boolean> = pinRequired.asStateFlow()
    override val devicePin: StateFlow<String> = pin.asStateFlow()
    override val pinIdleTimeout: StateFlow<PinIdleTimeout> = pinIdle.asStateFlow()
    override val checkForUpdatesEnabled: StateFlow<Boolean> = checkForUpdates.asStateFlow()
    override val checkForUpdatesIntervalUnit: StateFlow<UpdateCheckUnit> = updateUnit.asStateFlow()
    override val checkForUpdatesIntervalAmount: StateFlow<Int> = updateAmount.asStateFlow()
    override val lastUpdateCheckEpochMs: StateFlow<Long> = lastUpdateCheck.asStateFlow()
    override val enableServiceWatchdog: StateFlow<Boolean> = serviceWatchdog.asStateFlow()
    override val desktopLayoutMode: StateFlow<DesktopLayoutMode> = desktopLayout.asStateFlow()

    override fun setGoogleAccountLinkEnabled(enabled: Boolean) {
        store.putBoolean(KEY_GOOGLE, enabled)
        google.value = enabled
        if (!enabled) {
            setGoogleAccountEmail("")
            setGoogleAccountUid("")
        }
    }

    override fun setGoogleAccountEmail(email: String) {
        val cleaned = email.trim()
        store.putString(KEY_GOOGLE_EMAIL, cleaned)
        googleEmail.value = cleaned
    }

    override fun setGoogleAccountUid(uid: String) {
        val cleaned = uid.trim()
        store.putString(KEY_GOOGLE_UID, cleaned)
        googleUid.value = cleaned
    }

    override fun setMultiCopyIntroAcknowledged(acknowledged: Boolean) {
        store.putBoolean(KEY_MULTI_COPY_INTRO, acknowledged)
        multiCopyIntro.value = acknowledged
    }

    override fun setFileTransferNotificationsEnabled(enabled: Boolean) {
        store.putBoolean(KEY_TRANSFER_NOTIFICATIONS, enabled)
        transferNotifications.value = enabled
    }

    override fun setPinRequiredEnabled(enabled: Boolean) {
        store.putBoolean(KEY_PIN_REQUIRED, enabled)
        pinRequired.value = enabled
    }

    override fun setDevicePin(pinValue: String) {
        val cleaned = pinValue.filter { it.isDigit() }.take(8)
        store.putString(KEY_DEVICE_PIN, cleaned)
        pin.value = cleaned
    }

    override fun setPinIdleTimeout(timeout: PinIdleTimeout) {
        store.putString(KEY_PIN_IDLE_TIMEOUT, timeout.name)
        pinIdle.value = timeout
    }

    override fun setCheckForUpdatesEnabled(enabled: Boolean) {
        store.putBoolean(KEY_CHECK_FOR_UPDATES, enabled)
        checkForUpdates.value = enabled
    }

    override fun setCheckForUpdatesInterval(unit: UpdateCheckUnit, amount: Int) {
        val safeAmount = UpdateCheckFrequency.sanitizeAmount(unit, amount)
        store.putString(KEY_UPDATE_UNIT, unit.name)
        store.putInt(KEY_UPDATE_AMOUNT, safeAmount)
        updateUnit.value = unit
        updateAmount.value = safeAmount
    }

    override fun setLastUpdateCheckEpochMs(epochMs: Long) {
        TimestampDiagnostics.logMutation("AppSettings.lastUpdateCheckEpochMs", epochMs)
        store.putLong(KEY_LAST_UPDATE_CHECK, epochMs)
        lastUpdateCheck.value = epochMs
    }

    override fun setEnableServiceWatchdog(enabled: Boolean) {
        store.putBoolean(KEY_SERVICE_WATCHDOG, enabled)
        serviceWatchdog.value = enabled
    }

    override fun setDesktopLayoutMode(mode: DesktopLayoutMode) {
        store.putString(KEY_DESKTOP_LAYOUT, mode.name)
        desktopLayout.value = mode
    }

    companion object {
        const val KEY_GOOGLE = "google_account_link"
        const val KEY_GOOGLE_EMAIL = "google_account_email"
        const val KEY_GOOGLE_UID = "google_account_uid"
        const val KEY_MULTI_COPY_INTRO = "multi_copy_intro_ack"
        const val KEY_TRANSFER_NOTIFICATIONS = "file_transfer_notifications"
        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_DEVICE_PIN = "device_pin"
        const val KEY_PIN_IDLE_TIMEOUT = "pin_idle_timeout"
        const val KEY_CHECK_FOR_UPDATES = "auto_update"
        const val KEY_UPDATE_UNIT = "auto_update_unit"
        const val KEY_UPDATE_AMOUNT = "auto_update_amount"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_epoch_ms"
        const val KEY_SERVICE_WATCHDOG = "enable_service_watchdog"
        const val KEY_DESKTOP_LAYOUT = "desktop_layout_mode"
    }
}
