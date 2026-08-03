package com.fileapex.data.settings

import com.fileapex.presentation.ExplorerViewMode
import kotlinx.coroutines.flow.StateFlow

interface AppSettings {
    val googleAccountLinkEnabled: StateFlow<Boolean>
    /** Email of the linked Google account when [googleAccountLinkEnabled] is on; empty otherwise. */
    val googleAccountEmail: StateFlow<String>
    /** Firebase Auth UID for Firestore path users/{uid}/devices. Empty when unlinked. */
    val googleAccountUid: StateFlow<String>
    val multiCopyIntroAcknowledged: StateFlow<Boolean>
    /** When true, this device shows a notification after successfully receiving files. Default off. */
    val fileTransferNotificationsEnabled: StateFlow<Boolean>
    /** When true, scanners must supply this device's PIN to pair. Default off. */
    val pinRequiredEnabled: StateFlow<Boolean>
    /** Local PIN others must enter when [pinRequiredEnabled] is on. */
    val devicePin: StateFlow<String>
    /** Browse unlock idle window for peers (this device as the browser). Default [PinIdleTimeout.FiveMinutes]. */
    val pinIdleTimeout: StateFlow<PinIdleTimeout>
    /** When true, check GitHub Releases for updates on the configured schedule. Default off. */
    val checkForUpdatesEnabled: StateFlow<Boolean>
    /** Unit for [checkForUpdatesIntervalAmount]. Default [UpdateCheckUnit.Days]. */
    val checkForUpdatesIntervalUnit: StateFlow<UpdateCheckUnit>
    /** Amount paired with [checkForUpdatesIntervalUnit]. Default 1. */
    val checkForUpdatesIntervalAmount: StateFlow<Int>
    /** Epoch millis of the last completed update check (0 = never). */
    val lastUpdateCheckEpochMs: StateFlow<Long>
    /** Remote version the user skipped; suppresses repeat prompts until a newer tag appears. */
    val skippedUpdateVersion: StateFlow<String>
    /** When true, AlarmManager may restart the Android share-server FGS after OEM kills. */
    val enableServiceWatchdog: StateFlow<Boolean>
    /** When true, Android starts the share server after device reboot. Default on. */
    val autoLaunchOnReboot: StateFlow<Boolean>
    /** Encoded paired-device order (see DeviceOrderCoordinator). */
    val deviceOrderIds: StateFlow<String>
    /** Epoch millis when [deviceOrderIds] last changed locally. */
    val deviceOrderUpdatedAtEpochMs: StateFlow<Long>
    /** Desktop-only: force compact or expanded adaptive layout regardless of window width. */
    val desktopLayoutMode: StateFlow<DesktopLayoutMode>
    /**
     * Windows desktop visual style ([DesktopUiStyle.Standard] vs Fluent / Windows 11 Modern).
     * Ignored on Android and non-Windows desktops.
     */
    val desktopUiStyle: StateFlow<DesktopUiStyle>
    /** File explorer layout (list vs grid). Default [com.fileapex.presentation.ExplorerViewMode.List]. */
    val explorerViewMode: StateFlow<ExplorerViewMode>
    /** Paired-devices layout (list vs grid). Default [com.fileapex.presentation.ExplorerViewMode.List]. */
    val devicesViewMode: StateFlow<ExplorerViewMode>

    fun setGoogleAccountLinkEnabled(enabled: Boolean)
    fun setGoogleAccountEmail(email: String)
    fun setGoogleAccountUid(uid: String)
    fun setMultiCopyIntroAcknowledged(acknowledged: Boolean)
    fun setFileTransferNotificationsEnabled(enabled: Boolean)
    fun setPinRequiredEnabled(enabled: Boolean)
    fun setDevicePin(pinValue: String)
    fun setPinIdleTimeout(timeout: PinIdleTimeout)
    fun setCheckForUpdatesEnabled(enabled: Boolean)
    fun setCheckForUpdatesInterval(unit: UpdateCheckUnit, amount: Int)
    fun setLastUpdateCheckEpochMs(epochMs: Long)

    fun setSkippedUpdateVersion(version: String)

    fun setEnableServiceWatchdog(enabled: Boolean)

    fun setAutoLaunchOnReboot(enabled: Boolean)

    fun setDeviceOrderIds(encodedOrder: String)

    fun setDeviceOrderUpdatedAtEpochMs(epochMs: Long)

    fun setDesktopLayoutMode(mode: DesktopLayoutMode)

    fun setDesktopUiStyle(style: DesktopUiStyle)

    fun setExplorerViewMode(mode: ExplorerViewMode)

    fun setDevicesViewMode(mode: ExplorerViewMode)

    fun checkForUpdatesIntervalMillis(): Long {
        return UpdateCheckFrequency.toMillis(
            checkForUpdatesIntervalUnit.value,
            checkForUpdatesIntervalAmount.value
        )
    }
}

expect fun createAppSettings(): AppSettings
