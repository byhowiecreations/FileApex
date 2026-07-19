package com.omninode.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private class AndroidAppSettings(context: Context) : AppSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val google = MutableStateFlow(prefs.getBoolean(KEY_GOOGLE, false))
    private val googleEmail = MutableStateFlow(prefs.getString(KEY_GOOGLE_EMAIL, "") ?: "")
    private val googleUid = MutableStateFlow(prefs.getString(KEY_GOOGLE_UID, "") ?: "")
    private val multiCopyIntro = MutableStateFlow(prefs.getBoolean(KEY_MULTI_COPY_INTRO, false))
    private val transferNotifications =
        MutableStateFlow(prefs.getBoolean(KEY_TRANSFER_NOTIFICATIONS, false))
    private val pinRequired = MutableStateFlow(prefs.getBoolean(KEY_PIN_REQUIRED, false))
    private val pin = MutableStateFlow(prefs.getString(KEY_DEVICE_PIN, "") ?: "")
    private val pinIdle = MutableStateFlow(
        PinIdleTimeout.fromStorage(
            prefs.getString(KEY_PIN_IDLE_TIMEOUT, PinIdleTimeout.DEFAULT.name)
                ?: PinIdleTimeout.DEFAULT.name
        )
    )
    private val checkForUpdates = MutableStateFlow(prefs.getBoolean(KEY_CHECK_FOR_UPDATES, false))
    private val updateUnit = MutableStateFlow(
        UpdateCheckUnit.fromStorage(prefs.getString(KEY_UPDATE_UNIT, UpdateCheckUnit.Days.name) ?: UpdateCheckUnit.Days.name)
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

    override fun setGoogleAccountLinkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GOOGLE, enabled).apply()
        google.value = enabled
        if (!enabled) {
            setGoogleAccountEmail("")
            setGoogleAccountUid("")
        }
    }

    override fun setGoogleAccountEmail(email: String) {
        val cleaned = email.trim()
        prefs.edit().putString(KEY_GOOGLE_EMAIL, cleaned).apply()
        googleEmail.value = cleaned
    }

    override fun setGoogleAccountUid(uid: String) {
        val cleaned = uid.trim()
        prefs.edit().putString(KEY_GOOGLE_UID, cleaned).apply()
        googleUid.value = cleaned
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

    override fun setPinIdleTimeout(timeout: PinIdleTimeout) {
        prefs.edit().putString(KEY_PIN_IDLE_TIMEOUT, timeout.name).apply()
        pinIdle.value = timeout
    }

    override fun setCheckForUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHECK_FOR_UPDATES, enabled).apply()
        checkForUpdates.value = enabled
    }

    override fun setCheckForUpdatesInterval(unit: UpdateCheckUnit, amount: Int) {
        val safeAmount = UpdateCheckFrequency.sanitizeAmount(unit, amount)
        prefs.edit()
            .putString(KEY_UPDATE_UNIT, unit.name)
            .putInt(KEY_UPDATE_AMOUNT, safeAmount)
            .apply()
        updateUnit.value = unit
        updateAmount.value = safeAmount
    }

    override fun setLastUpdateCheckEpochMs(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, epochMs).apply()
        lastUpdateCheck.value = epochMs
    }

    private companion object {
        const val PREFS = "omninode_settings"
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
