package com.fileapex.data.settings

import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import kotlinx.coroutines.flow.StateFlow

interface AppSettings {
    val googleAccountLinkEnabled: StateFlow<Boolean>
    val googleAccountEmail: StateFlow<String>
    /** Firestore path users/{uid}/devices. Empty when unlinked. */
    val googleAccountUid: StateFlow<String>
    val multiCopyIntroAcknowledged: StateFlow<Boolean>
    val clipboardSharingEnabled: StateFlow<Boolean>
    val clipboardShareMode: StateFlow<ClipboardShareMode>
    val clipboardTargetDeviceIds: StateFlow<Set<String>>
    val clipboardViaCellularEnabled: StateFlow<Boolean>
    val clipboardAccessibilityEnabled: StateFlow<Boolean>
    val clipboardAutoSendEnabled: StateFlow<Boolean>
    val appLanguageTag: StateFlow<String>
    val fileTransferNotificationsEnabled: StateFlow<Boolean>
    val driveRelayNotificationsEnabled: StateFlow<Boolean>
    val pinRequiredEnabled: StateFlow<Boolean>
    val devicePin: StateFlow<String>
    val pinIdleTimeout: StateFlow<PinIdleTimeout>
    val checkForUpdatesEnabled: StateFlow<Boolean>
    val checkForUpdatesIntervalUnit: StateFlow<UpdateCheckUnit>
    val checkForUpdatesIntervalAmount: StateFlow<Int>
    val lastUpdateCheckEpochMs: StateFlow<Long>
    /** Suppresses repeat prompts until a newer GitHub tag appears. */
    val skippedUpdateVersion: StateFlow<String>
    /** AlarmManager may restart the Android share-server FGS after OEM kills. */
    val enableServiceWatchdog: StateFlow<Boolean>
    val autoLaunchOnReboot: StateFlow<Boolean>
    val deviceOrderIds: StateFlow<String>
    val deviceOrderUpdatedAtEpochMs: StateFlow<Long>
    val desktopLayoutMode: StateFlow<DesktopLayoutMode>
    /** Windows only; ignored on Android and non-Windows desktops. */
    val desktopUiStyle: StateFlow<DesktopUiStyle>
    val explorerViewMode: StateFlow<ExplorerViewMode>
    val devicesViewMode: StateFlow<ExplorerViewMode>
    val kineticNodeOffsets: StateFlow<Map<String, Pair<Float, Float>>>
    val deviceDetailsDisplayPreferences: StateFlow<DeviceDetailsDisplayPreferences>
    val deviceDetailsAllowOverCellular: StateFlow<Boolean>

    /** Drive Relay also requires a linked Google Account. */
    val cellularEnabled: StateFlow<Boolean>
    val googleDriveRelayEnabled: StateFlow<Boolean>
    val driveRelayMaxMb: StateFlow<DriveRelayMaxMb>
    val drivePurgeAfter72Hours: StateFlow<Boolean>
    val cellularSendPromptAcknowledged: StateFlow<Boolean>
    val cellularReceivePromptAcknowledged: StateFlow<Boolean>
    val driveRelayOptInPromptShown: StateFlow<Boolean>

    val notesNotificationsEnabled: StateFlow<Boolean>
    val notesNotificationPromptShown: StateFlow<Boolean>
    val bulletinRemoteFilePurgePreference: StateFlow<BulletinRemoteFilePurgePreference>

    val liveTransferCapsuleEnabled: StateFlow<Boolean>
    val liveTransferShowQueueEnabled: StateFlow<Boolean>
    val appTheme: StateFlow<AppTheme>

    val kineticSphereCleanMode: StateFlow<Boolean>
    val kineticSphereConnectedLinesEnabled: StateFlow<Boolean>
    val kineticSphereOrbitalRingsEnabled: StateFlow<Boolean>

    val settingsGroupSystemPerformanceExpanded: StateFlow<Boolean>
    val settingsGroupAppearanceBehaviorExpanded: StateFlow<Boolean>
    val settingsGroupSecurityAccountExpanded: StateFlow<Boolean>

    fun setGoogleAccountLinkEnabled(enabled: Boolean)
    fun setGoogleAccountEmail(email: String)
    fun setGoogleAccountUid(uid: String)
    fun setMultiCopyIntroAcknowledged(acknowledged: Boolean)
    fun setClipboardSharingEnabled(enabled: Boolean)
    fun setClipboardShareMode(mode: ClipboardShareMode)
    fun setClipboardTargetDeviceIds(deviceIds: Set<String>)
    fun setClipboardTargetDevice(deviceId: String, selected: Boolean)
    fun setClipboardViaCellularEnabled(enabled: Boolean)
    fun setClipboardAccessibilityEnabled(enabled: Boolean)
    fun setClipboardAutoSendEnabled(enabled: Boolean)
    fun setAppLanguageTag(tag: String)
    fun clipboardPrivateKeyBase64(): String
    fun setClipboardPrivateKeyBase64(value: String)
    fun setFileTransferNotificationsEnabled(enabled: Boolean)
    fun setDriveRelayNotificationsEnabled(enabled: Boolean)
    fun setNotesNotificationsEnabled(enabled: Boolean)
    fun setNotesNotificationPromptShown(shown: Boolean)
    fun setBulletinRemoteFilePurgePreference(preference: BulletinRemoteFilePurgePreference)
    fun setLiveTransferCapsuleEnabled(enabled: Boolean)
    fun setLiveTransferShowQueueEnabled(enabled: Boolean)
    fun setAppTheme(theme: AppTheme)
    fun setKineticSphereCleanMode(enabled: Boolean)
    fun setKineticSphereConnectedLinesEnabled(enabled: Boolean)
    fun setKineticSphereOrbitalRingsEnabled(enabled: Boolean)
    fun setSettingsGroupSystemPerformanceExpanded(expanded: Boolean)
    fun setSettingsGroupAppearanceBehaviorExpanded(expanded: Boolean)
    fun setSettingsGroupSecurityAccountExpanded(expanded: Boolean)


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

    fun setKineticNodeOffset(deviceId: String, dx: Float, dy: Float)

    fun resetKineticNodeOffsets()

    fun setDeviceDetailsDisplayPreferences(preferences: DeviceDetailsDisplayPreferences)

    fun setDeviceDetailsAllowOverCellular(enabled: Boolean)

    fun setCellularEnabled(enabled: Boolean)

    fun setGoogleDriveRelayEnabled(enabled: Boolean)

    fun setDriveRelayMaxMb(limit: DriveRelayMaxMb)

    fun setDrivePurgeAfter72Hours(enabled: Boolean)

    fun setDriveRelayOptInPromptShown(shown: Boolean)

    fun setCellularSendPromptAcknowledged(acknowledged: Boolean)

    fun setCellularReceivePromptAcknowledged(acknowledged: Boolean)

    fun diagnosticsPrivateKeyBase64(): String

    fun setDiagnosticsPrivateKeyBase64(value: String)

    fun checkForUpdatesIntervalMillis(): Long {
        return UpdateCheckFrequency.toMillis(
            checkForUpdatesIntervalUnit.value,
            checkForUpdatesIntervalAmount.value
        )
    }
}

expect fun createAppSettings(): AppSettings
