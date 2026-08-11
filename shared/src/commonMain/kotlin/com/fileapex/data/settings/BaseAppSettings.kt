package com.fileapex.data.settings

import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.util.TimestampDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic key/value persistence for [BaseAppSettings].
 */
interface SettingsKvStore {
    fun contains(key: String): Boolean
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
    private val clipboardSharing = MutableStateFlow(store.getBoolean(KEY_CLIPBOARD_SHARING, false))
    private val transferNotifications =
        MutableStateFlow(store.getBoolean(KEY_TRANSFER_NOTIFICATIONS, false))
    private val liveTransferCapsuleFlow =
        MutableStateFlow(store.getBoolean(KEY_LIVE_TRANSFER_CAPSULE, false))
    private val liveTransferShowQueueFlow =
        MutableStateFlow(store.getBoolean(KEY_LIVE_TRANSFER_SHOW_QUEUE, false))
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
    private val skippedUpdateVersionFlow =
        MutableStateFlow(store.getString(KEY_SKIPPED_UPDATE_VERSION, ""))
    private val serviceWatchdog = MutableStateFlow(store.getBoolean(KEY_SERVICE_WATCHDOG, true))
    private val autoLaunchOnRebootFlow = MutableStateFlow(loadAutoLaunchOnReboot())
    private val deviceOrderIdsFlow = MutableStateFlow(store.getString(KEY_DEVICE_ORDER, ""))
    private val deviceOrderUpdatedAt = MutableStateFlow(store.getLong(KEY_DEVICE_ORDER_UPDATED_AT, 0L))
    private val desktopLayout = MutableStateFlow(
        DesktopLayoutMode.fromStorage(store.getString(KEY_DESKTOP_LAYOUT, DesktopLayoutMode.DEFAULT.name))
    )
    private val desktopUiStyleFlow = MutableStateFlow(
        DesktopUiStyle.fromStorage(store.getString(KEY_DESKTOP_UI_STYLE, DesktopUiStyle.DEFAULT.name))
    )
    private val explorerViewModeFlow = MutableStateFlow(
        ExplorerViewMode.fromStorage(store.getString(KEY_EXPLORER_VIEW_MODE, ExplorerViewMode.List.name))
    )
    private val devicesViewModeFlow = MutableStateFlow(
        ExplorerViewMode.fromStorage(store.getString(KEY_DEVICES_VIEW_MODE, ExplorerViewMode.List.name))
    )
    private val kineticNodeOffsetsFlow = MutableStateFlow(
        decodeKineticOffsets(store.getString(KEY_KINETIC_NODE_OFFSETS, ""))
    )
    private val deviceDetailsDisplayPreferencesFlow = MutableStateFlow(
        DeviceDetailsDisplayPreferences.decode(
            store.getString(KEY_DEVICE_DETAILS_DISPLAY, "")
        )
    )
    private val deviceDetailsAllowOverCellularFlow = MutableStateFlow(
        store.getBoolean(KEY_DEVICE_DETAILS_ALLOW_CELLULAR, false)
    )
    private val appThemeFlow = MutableStateFlow(
        AppTheme.fromStorage(store.getString(KEY_APP_THEME, AppTheme.DEFAULT.name))
    )
    private val kineticSphereCleanModeFlow = MutableStateFlow(
        store.getBoolean(KEY_KINETIC_SPHERE_CLEAN_MODE, false)
    )
    private val kineticSphereConnectedLinesFlow = MutableStateFlow(
        store.getBoolean(KEY_KINETIC_SPHERE_CONNECTED_LINES, !store.getBoolean(KEY_KINETIC_SPHERE_CLEAN_MODE, false))
    )
    private val kineticSphereOrbitalRingsFlow = MutableStateFlow(
        store.getBoolean(KEY_KINETIC_SPHERE_ORBITAL_RINGS, !store.getBoolean(KEY_KINETIC_SPHERE_CLEAN_MODE, false))
    )
    private val settingsGroupSystemPerformanceFlow = MutableStateFlow(
        store.getBoolean(KEY_SETTINGS_GROUP_SYSTEM_PERFORMANCE, true)
    )
    private val settingsGroupAppearanceBehaviorFlow = MutableStateFlow(
        store.getBoolean(KEY_SETTINGS_GROUP_APPEARANCE_BEHAVIOR, true)
    )
    private val settingsGroupSecurityAccountFlow = MutableStateFlow(
        store.getBoolean(KEY_SETTINGS_GROUP_SECURITY_ACCOUNT, true)
    )
    private val diagnosticsPrivateKeyBase64Stored = MutableStateFlow(
        store.getString(KEY_DIAGNOSTICS_PRIVATE_KEY, "")
    )


    override val googleAccountLinkEnabled: StateFlow<Boolean> = google.asStateFlow()
    override val googleAccountEmail: StateFlow<String> = googleEmail.asStateFlow()
    override val googleAccountUid: StateFlow<String> = googleUid.asStateFlow()
    override val multiCopyIntroAcknowledged: StateFlow<Boolean> = multiCopyIntro.asStateFlow()
    override val clipboardSharingEnabled: StateFlow<Boolean> = clipboardSharing.asStateFlow()
    override val fileTransferNotificationsEnabled: StateFlow<Boolean> =
        transferNotifications.asStateFlow()
    override val liveTransferCapsuleEnabled: StateFlow<Boolean> =
        liveTransferCapsuleFlow.asStateFlow()
    override val liveTransferShowQueueEnabled: StateFlow<Boolean> =
        liveTransferShowQueueFlow.asStateFlow()
    override val appTheme: StateFlow<AppTheme> = appThemeFlow.asStateFlow()
    override val kineticSphereCleanMode: StateFlow<Boolean> = kineticSphereCleanModeFlow.asStateFlow()
    override val kineticSphereConnectedLinesEnabled: StateFlow<Boolean> = kineticSphereConnectedLinesFlow.asStateFlow()
    override val kineticSphereOrbitalRingsEnabled: StateFlow<Boolean> = kineticSphereOrbitalRingsFlow.asStateFlow()
    override val settingsGroupSystemPerformanceExpanded: StateFlow<Boolean> =
        settingsGroupSystemPerformanceFlow.asStateFlow()
    override val settingsGroupAppearanceBehaviorExpanded: StateFlow<Boolean> =
        settingsGroupAppearanceBehaviorFlow.asStateFlow()
    override val settingsGroupSecurityAccountExpanded: StateFlow<Boolean> =
        settingsGroupSecurityAccountFlow.asStateFlow()

    override val pinRequiredEnabled: StateFlow<Boolean> = pinRequired.asStateFlow()
    override val devicePin: StateFlow<String> = pin.asStateFlow()
    override val pinIdleTimeout: StateFlow<PinIdleTimeout> = pinIdle.asStateFlow()
    override val checkForUpdatesEnabled: StateFlow<Boolean> = checkForUpdates.asStateFlow()
    override val checkForUpdatesIntervalUnit: StateFlow<UpdateCheckUnit> = updateUnit.asStateFlow()
    override val checkForUpdatesIntervalAmount: StateFlow<Int> = updateAmount.asStateFlow()
    override val lastUpdateCheckEpochMs: StateFlow<Long> = lastUpdateCheck.asStateFlow()
    override val skippedUpdateVersion: StateFlow<String> = skippedUpdateVersionFlow.asStateFlow()
    override val enableServiceWatchdog: StateFlow<Boolean> = serviceWatchdog.asStateFlow()
    override val autoLaunchOnReboot: StateFlow<Boolean> = autoLaunchOnRebootFlow.asStateFlow()
    override val deviceOrderIds: StateFlow<String> = deviceOrderIdsFlow.asStateFlow()
    override val deviceOrderUpdatedAtEpochMs: StateFlow<Long> = deviceOrderUpdatedAt.asStateFlow()
    override val desktopLayoutMode: StateFlow<DesktopLayoutMode> = desktopLayout.asStateFlow()
    override val desktopUiStyle: StateFlow<DesktopUiStyle> = desktopUiStyleFlow.asStateFlow()
    override val explorerViewMode: StateFlow<ExplorerViewMode> = explorerViewModeFlow.asStateFlow()
    override val devicesViewMode: StateFlow<ExplorerViewMode> = devicesViewModeFlow.asStateFlow()
    override val kineticNodeOffsets: StateFlow<Map<String, Pair<Float, Float>>> =
        kineticNodeOffsetsFlow.asStateFlow()
    override val deviceDetailsDisplayPreferences: StateFlow<DeviceDetailsDisplayPreferences> =
        deviceDetailsDisplayPreferencesFlow.asStateFlow()
    override val deviceDetailsAllowOverCellular: StateFlow<Boolean> =
        deviceDetailsAllowOverCellularFlow.asStateFlow()


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

    override fun setClipboardSharingEnabled(enabled: Boolean) {
        store.putBoolean(KEY_CLIPBOARD_SHARING, enabled)
        clipboardSharing.value = enabled
    }

    override fun setFileTransferNotificationsEnabled(enabled: Boolean) {
        store.putBoolean(KEY_TRANSFER_NOTIFICATIONS, enabled)
        transferNotifications.value = enabled
    }

    override fun setLiveTransferCapsuleEnabled(enabled: Boolean) {
        store.putBoolean(KEY_LIVE_TRANSFER_CAPSULE, enabled)
        liveTransferCapsuleFlow.value = enabled
    }

    override fun setLiveTransferShowQueueEnabled(enabled: Boolean) {
        store.putBoolean(KEY_LIVE_TRANSFER_SHOW_QUEUE, enabled)
        liveTransferShowQueueFlow.value = enabled
    }

    override fun setAppTheme(theme: AppTheme) {
        store.putString(KEY_APP_THEME, theme.name)
        appThemeFlow.value = theme
    }

    override fun setKineticSphereCleanMode(enabled: Boolean) {
        store.putBoolean(KEY_KINETIC_SPHERE_CLEAN_MODE, enabled)
        kineticSphereCleanModeFlow.value = enabled
        if (enabled) {
            setKineticSphereConnectedLinesEnabled(false)
            setKineticSphereOrbitalRingsEnabled(false)
        } else {
            setKineticSphereConnectedLinesEnabled(true)
            setKineticSphereOrbitalRingsEnabled(true)
        }
    }

    override fun setKineticSphereConnectedLinesEnabled(enabled: Boolean) {
        store.putBoolean(KEY_KINETIC_SPHERE_CONNECTED_LINES, enabled)
        kineticSphereConnectedLinesFlow.value = enabled
    }

    override fun setKineticSphereOrbitalRingsEnabled(enabled: Boolean) {
        store.putBoolean(KEY_KINETIC_SPHERE_ORBITAL_RINGS, enabled)
        kineticSphereOrbitalRingsFlow.value = enabled
    }

    override fun setSettingsGroupSystemPerformanceExpanded(expanded: Boolean) {
        store.putBoolean(KEY_SETTINGS_GROUP_SYSTEM_PERFORMANCE, expanded)
        settingsGroupSystemPerformanceFlow.value = expanded
    }

    override fun setSettingsGroupAppearanceBehaviorExpanded(expanded: Boolean) {
        store.putBoolean(KEY_SETTINGS_GROUP_APPEARANCE_BEHAVIOR, expanded)
        settingsGroupAppearanceBehaviorFlow.value = expanded
    }

    override fun setSettingsGroupSecurityAccountExpanded(expanded: Boolean) {
        store.putBoolean(KEY_SETTINGS_GROUP_SECURITY_ACCOUNT, expanded)
        settingsGroupSecurityAccountFlow.value = expanded
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

    override fun setSkippedUpdateVersion(version: String) {
        val cleaned = version.trim()
        store.putString(KEY_SKIPPED_UPDATE_VERSION, cleaned)
        skippedUpdateVersionFlow.value = cleaned
    }

    override fun setEnableServiceWatchdog(enabled: Boolean) {
        store.putBoolean(KEY_SERVICE_WATCHDOG, enabled)
        serviceWatchdog.value = enabled
    }

    override fun setAutoLaunchOnReboot(enabled: Boolean) {
        store.putBoolean(KEY_AUTO_LAUNCH_ON_REBOOT, enabled)
        autoLaunchOnRebootFlow.value = enabled
    }

    override fun setDeviceOrderIds(encodedOrder: String) {
        store.putString(KEY_DEVICE_ORDER, encodedOrder)
        deviceOrderIdsFlow.value = encodedOrder
    }

    override fun setDeviceOrderUpdatedAtEpochMs(epochMs: Long) {
        TimestampDiagnostics.logMutation("AppSettings.deviceOrderUpdatedAtEpochMs", epochMs)
        store.putLong(KEY_DEVICE_ORDER_UPDATED_AT, epochMs)
        deviceOrderUpdatedAt.value = epochMs
    }

    override fun setDesktopLayoutMode(mode: DesktopLayoutMode) {
        store.putString(KEY_DESKTOP_LAYOUT, mode.name)
        desktopLayout.value = mode
    }

    override fun setDesktopUiStyle(style: DesktopUiStyle) {
        store.putString(KEY_DESKTOP_UI_STYLE, style.name)
        desktopUiStyleFlow.value = style
    }

    override fun setExplorerViewMode(mode: ExplorerViewMode) {
        store.putString(KEY_EXPLORER_VIEW_MODE, mode.name)
        explorerViewModeFlow.value = mode
    }

    override fun setDevicesViewMode(mode: ExplorerViewMode) {
        store.putString(KEY_DEVICES_VIEW_MODE, mode.name)
        devicesViewModeFlow.value = mode
    }

    override fun setKineticNodeOffset(deviceId: String, dx: Float, dy: Float) {
        val updated = kineticNodeOffsetsFlow.value + (deviceId to Pair(dx, dy))
        kineticNodeOffsetsFlow.value = updated
        store.putString(KEY_KINETIC_NODE_OFFSETS, encodeKineticOffsets(updated))
    }

    override fun resetKineticNodeOffsets() {
        kineticNodeOffsetsFlow.value = emptyMap()
        store.putString(KEY_KINETIC_NODE_OFFSETS, "")
    }

    override fun setDeviceDetailsDisplayPreferences(preferences: DeviceDetailsDisplayPreferences) {
        val normalized = preferences.normalized()
        store.putString(KEY_DEVICE_DETAILS_DISPLAY, DeviceDetailsDisplayPreferences.encode(normalized))
        deviceDetailsDisplayPreferencesFlow.value = normalized
    }

    override fun setDeviceDetailsAllowOverCellular(enabled: Boolean) {
        store.putBoolean(KEY_DEVICE_DETAILS_ALLOW_CELLULAR, enabled)
        deviceDetailsAllowOverCellularFlow.value = enabled
    }

    override fun diagnosticsPrivateKeyBase64(): String = diagnosticsPrivateKeyBase64Stored.value

    override fun setDiagnosticsPrivateKeyBase64(value: String) {
        val cleaned = value.trim()
        store.putString(KEY_DIAGNOSTICS_PRIVATE_KEY, cleaned)
        diagnosticsPrivateKeyBase64Stored.value = cleaned
    }

    /**
     * Pre-0.6.1a boot restart followed the service watchdog toggle; migrate once on first read.
     */
    private fun loadAutoLaunchOnReboot(): Boolean {
        if (!store.contains(KEY_AUTO_LAUNCH_ON_REBOOT)) {
            val migrated = store.getBoolean(KEY_SERVICE_WATCHDOG, true)
            store.putBoolean(KEY_AUTO_LAUNCH_ON_REBOOT, migrated)
            return migrated
        }
        return store.getBoolean(KEY_AUTO_LAUNCH_ON_REBOOT, true)
    }

    companion object {
        const val KEY_GOOGLE = "google_account_link"
        const val KEY_GOOGLE_EMAIL = "google_account_email"
        const val KEY_GOOGLE_UID = "google_account_uid"
        const val KEY_MULTI_COPY_INTRO = "multi_copy_intro_ack"
        const val KEY_CLIPBOARD_SHARING = "clipboard_sharing_enabled"
        const val KEY_TRANSFER_NOTIFICATIONS = "file_transfer_notifications"
        const val KEY_LIVE_TRANSFER_CAPSULE = "live_transfer_capsule_enabled"
        const val KEY_LIVE_TRANSFER_SHOW_QUEUE = "live_transfer_show_queue_enabled"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_KINETIC_SPHERE_CLEAN_MODE = "kinetic_sphere_clean_mode"
        const val KEY_KINETIC_SPHERE_CONNECTED_LINES = "kinetic_sphere_connected_lines"
        const val KEY_KINETIC_SPHERE_ORBITAL_RINGS = "kinetic_sphere_orbital_rings"


        const val KEY_PIN_REQUIRED = "pin_required"
        const val KEY_DEVICE_PIN = "device_pin"
        const val KEY_PIN_IDLE_TIMEOUT = "pin_idle_timeout"
        const val KEY_CHECK_FOR_UPDATES = "auto_update"
        const val KEY_UPDATE_UNIT = "auto_update_unit"
        const val KEY_UPDATE_AMOUNT = "auto_update_amount"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_epoch_ms"
        const val KEY_SKIPPED_UPDATE_VERSION = "skipped_update_version"
        const val KEY_SERVICE_WATCHDOG = "enable_service_watchdog"
        const val KEY_AUTO_LAUNCH_ON_REBOOT = "auto_launch_on_reboot"
        const val KEY_DEVICE_ORDER = "device_order_ids"
        const val KEY_DEVICE_ORDER_UPDATED_AT = "device_order_updated_at_epoch_ms"
        const val KEY_DESKTOP_LAYOUT = "desktop_layout_mode"
        const val KEY_DESKTOP_UI_STYLE = "desktop_ui_style"
        const val KEY_EXPLORER_VIEW_MODE = "explorer_view_mode"
        const val KEY_DEVICES_VIEW_MODE = "devices_view_mode"
        const val KEY_DEVICE_DETAILS_DISPLAY = "device_details_display"
        const val KEY_DEVICE_DETAILS_ALLOW_CELLULAR = "device_details_allow_cellular"
        const val KEY_DIAGNOSTICS_PRIVATE_KEY = "diagnostics_private_key_b64"
        const val KEY_KINETIC_NODE_OFFSETS = "kinetic_node_offsets"
        const val KEY_SETTINGS_GROUP_SYSTEM_PERFORMANCE = "settings_group_system_performance_expanded"
        const val KEY_SETTINGS_GROUP_APPEARANCE_BEHAVIOR = "settings_group_appearance_behavior_expanded"
        const val KEY_SETTINGS_GROUP_SECURITY_ACCOUNT = "settings_group_security_account_expanded"
    }
}

private fun encodeKineticOffsets(offsets: Map<String, Pair<Float, Float>>): String {
    return offsets.entries.joinToString(";") { (id, pair) ->
        val cleanId = id.replace(";", "_").replace("|", "_")
        "$cleanId|${pair.first}|${pair.second}"
    }
}

private fun decodeKineticOffsets(encoded: String): Map<String, Pair<Float, Float>> {
    if (encoded.isBlank()) return emptyMap()
    val map = mutableMapOf<String, Pair<Float, Float>>()
    encoded.split(";").forEach { token ->
        if (token.isBlank()) return@forEach
        val pipeParts = token.split("|")
        if (pipeParts.size == 3) {
            val id = pipeParts[0]
            val dx = pipeParts[1].toFloatOrNull()
            val dy = pipeParts[2].toFloatOrNull()
            if (id.isNotBlank() && dx != null && dy != null) {
                map[id] = Pair(dx, dy)
                if (id.startsWith("cmp_")) map["cmp:" + id.removePrefix("cmp_")] = Pair(dx, dy)
                if (id.startsWith("exp_")) map["exp:" + id.removePrefix("exp_")] = Pair(dx, dy)
            }
        } else {
            val colonParts = token.split(":")
            if (colonParts.size == 3) {
                val id = colonParts[0]
                val dx = colonParts[1].toFloatOrNull()
                val dy = colonParts[2].toFloatOrNull()
                if (id.isNotBlank() && dx != null && dy != null) {
                    map[id] = Pair(dx, dy)
                    if (id.startsWith("cmp_")) map["cmp:" + id.removePrefix("cmp_")] = Pair(dx, dy)
                    if (id.startsWith("exp_")) map["exp:" + id.removePrefix("exp_")] = Pair(dx, dy)
                }
            }
        }
    }
    return map
}
