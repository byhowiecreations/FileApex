package com.fileapex.data.settings

import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import kotlinx.coroutines.flow.StateFlow

interface AppSettings {
    /** Critical Notifications: the setting, the explanation flag and what was fetched. */
    val noticeSettings: com.fileapex.notice.NoticeSettingsStore

    /** How far this device has pulled the bulletin from [deviceId], on that peer's clock; 0 when never. */
    fun bulletinDeltaCursor(deviceId: String): Long

    /** When this device last looked at the Bulletin Board; 0 until the first start. Local only, never sent to other devices. */
    val bulletinSeenAtEpochMs: StateFlow<Long>
    fun setBulletinSeenAtEpochMs(epochMs: Long)
    fun setBulletinDeltaCursor(deviceId: String, cursor: Long)

    val googleAccountLinkEnabled: StateFlow<Boolean>
    val googleAccountEmail: StateFlow<String>
    /** Firestore path users/{uid}/devices. Empty when unlinked. */
    val googleAccountUid: StateFlow<String>
    val googleRestorePending: StateFlow<Boolean>
    val googleBackupEmailHint: StateFlow<String>
    val multiCopyIntroAcknowledged: StateFlow<Boolean>
    val clipboardSharingEnabled: StateFlow<Boolean>
    val clipboardDisclosureAcknowledged: StateFlow<Boolean>
    val accessibilityDisclosureAcknowledged: StateFlow<Boolean>
    val installPackagesDisclosureAcknowledged: StateFlow<Boolean>
    val clipboardShareMode: StateFlow<ClipboardShareMode>
    val clipboardTargetDeviceIds: StateFlow<Set<String>>
    val clipboardTargetConfigured: StateFlow<Boolean>
    val clipboardOptInPromptShown: StateFlow<Boolean>
    val clipboardViaCellularEnabled: StateFlow<Boolean>
    val clipboardAccessibilityEnabled: StateFlow<Boolean>
    val clipboardSendNotificationEnabled: StateFlow<Boolean>
    val clipboardShizukuEnabled: StateFlow<Boolean>
    val clipboardAutoSendEnabled: StateFlow<Boolean>
    val appLanguageTag: StateFlow<String>
    val fileTransferNotificationsEnabled: StateFlow<Boolean>
    val driveRelayNotificationsEnabled: StateFlow<Boolean>
    val pinRequiredEnabled: StateFlow<Boolean>
    val devicePin: StateFlow<String>
    val pinIdleTimeout: StateFlow<PinIdleTimeout>
    val checkForUpdatesEnabled: StateFlow<Boolean>
    /** True once the first-run "check for updates?" question has been answered. */
    val updateCheckPromptShown: StateFlow<Boolean>
    val checkForUpdatesIntervalUnit: StateFlow<UpdateCheckUnit>
    val checkForUpdatesIntervalAmount: StateFlow<Int>
    val lastUpdateCheckEpochMs: StateFlow<Long>
    /** Suppresses repeat prompts until a newer GitHub tag appears. */
    val skippedUpdateVersion: StateFlow<String>
    /** AlarmManager may restart the Android share-server FGS after OEM kills. */
    val enableServiceWatchdog: StateFlow<Boolean>
    val autoLaunchOnReboot: StateFlow<Boolean>

    /** Windows and Linux: start FileApex (minimized) when the user signs in. Off until the user turns it on. */
    val desktopAutostart: StateFlow<Boolean>
    fun setDesktopAutostart(enabled: Boolean)
    val deviceOrderIds: StateFlow<String>
    val deviceOrderUpdatedAtEpochMs: StateFlow<Long>
    /** [com.fileapex.domain.peer.ClusterClock] stamp of this device's latest pairing; 0 until it pairs on 0.14.3a+. */
    val clusterMembershipVersion: StateFlow<Long>
    val membershipProtocolSinceEpochMs: StateFlow<Long>
    /** Pending LAN subnet scan waits, so a restart does not scan every peer again. Empty when none. */
    var lanScanBackoffState: String
    val desktopLayoutMode: StateFlow<DesktopLayoutMode>
    val desktopSplitFraction: StateFlow<Float>
    val explorerSplitFraction: StateFlow<Float>
    /** Absolute paths of files and folders the user starred in the file manager. */
    val explorerFavorites: StateFlow<List<String>>
    val backupConfig: StateFlow<com.fileapex.domain.backup.BackupConfig>
    val explorerSplitEnabled: StateFlow<Boolean>
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
    val driveRelayEncryptionEnabled: StateFlow<Boolean>
    val cellularSendPromptAcknowledged: StateFlow<Boolean>
    val cellularReceivePromptAcknowledged: StateFlow<Boolean>
    val driveRelayOptInPromptShown: StateFlow<Boolean>

    val notesNotificationsEnabled: StateFlow<Boolean>
    val notesNotificationPromptShown: StateFlow<Boolean>
    val bulletinRemoteFilePurgePreference: StateFlow<BulletinRemoteFilePurgePreference>

    val liveTransferCapsuleEnabled: StateFlow<Boolean>
    val liveTransferShowQueueEnabled: StateFlow<Boolean>
    val appTheme: StateFlow<AppTheme>

    /** The theme drawn while a stored Carousel is unavailable (not a Mac, fewer than three paired devices). */
    val carouselFallbackTheme: StateFlow<AppTheme>

    /** The device starred as the Carousel's starting card; blank when none. */
    val carouselFavoriteDevice: StateFlow<String>
    fun setCarouselFavoriteDevice(deviceId: String)
    val themeIconStyle: StateFlow<ThemeIconStyle>
    val kineticStyle: StateFlow<KineticStyle>
    val bulletinBoardStyle: StateFlow<BulletinBoardStyle>

    val kineticSphereCleanMode: StateFlow<Boolean>
    val kineticSphereConnectedLinesEnabled: StateFlow<Boolean>
    val kineticSphereOrbitalRingsEnabled: StateFlow<Boolean>
    val kineticSpherePersistentWallpaperEnabled: StateFlow<Boolean>

    val freestyleCardOptionsPosX: StateFlow<Float?>
    val freestyleCardOptionsPosY: StateFlow<Float?>
    val freestyleCardVerticalOptionsPosX: StateFlow<Float?>
    val freestyleCardVerticalOptionsPosY: StateFlow<Float?>
    val freestyleTileOptionsPosX: StateFlow<Float?>
    val freestyleTileOptionsPosY: StateFlow<Float?>
    val freestyleLayoutMode: StateFlow<FreestyleLayoutMode>
    val freestyleEditTutorialShown: StateFlow<Boolean>
    val freestyleCardNodeOffsets: StateFlow<Map<String, Pair<Float, Float>>>
    val freestyleCardVerticalNodeOffsets: StateFlow<Map<String, Pair<Float, Float>>>
    val freestyleTileNodeOffsets: StateFlow<Map<String, Pair<Float, Float>>>
    val freestyleCardMenuOrders: StateFlow<Map<String, String>>
    val freestyleCardVerticalMenuOrders: StateFlow<Map<String, String>>
    val freestyleTileMenuOrders: StateFlow<Map<String, String>>
    val freestyleOptionsMenuOrder: StateFlow<String>
    val freestyleCardPinnedActions: StateFlow<Map<String, Pair<Float, Float>>>
    val freestyleCardVerticalPinnedActions: StateFlow<Map<String, Pair<Float, Float>>>
    val freestyleTilePinnedActions: StateFlow<Map<String, Pair<Float, Float>>>

    val settingsGroupGeneralExpanded: StateFlow<Boolean>
    val settingsGroupSystemPerformanceExpanded: StateFlow<Boolean>
    val settingsGroupAppearanceBehaviorExpanded: StateFlow<Boolean>
    val settingsGroupSecurityAccountExpanded: StateFlow<Boolean>
    val settingsGroupRemoteAccessExpanded: StateFlow<Boolean>
    /** Shows the advanced settings rows (remote access, security extras, backup). Off for new installs. */
    val showAdvancedSettings: StateFlow<Boolean>
    val tailscaleSetupExpanded: StateFlow<Boolean>
    val tailscaleEnabled: StateFlow<Boolean>
    val irohEnabled: StateFlow<Boolean>

    /** Self-hosted relay: on only after a valid https host name was saved. */
    val irohRelayEnabled: StateFlow<Boolean>
    val irohRelayUrl: StateFlow<String>

    /** Shows the Iroh log and per-device technical details on the Iroh page. */
    val irohAdvancedDebug: StateFlow<Boolean>

    /** Off by default: with it on, the Iroh endpoint may close while every paired device is reachable another way. */
    val irohRestWhenIdle: StateFlow<Boolean>
    val tailscaleAuthKey: StateFlow<String>
    val tailscaleAuthKeyFingerprint: StateFlow<String>

    fun setGoogleAccountLinkEnabled(enabled: Boolean)
    fun setGoogleAccountEmail(email: String)
    fun setGoogleAccountUid(uid: String)
    fun setGoogleRestorePending(pending: Boolean)
    fun setGoogleBackupEmailHint(email: String)
    fun setMultiCopyIntroAcknowledged(acknowledged: Boolean)
    fun setClipboardSharingEnabled(enabled: Boolean)
    fun setClipboardDisclosureAcknowledged(acknowledged: Boolean)
    fun setAccessibilityDisclosureAcknowledged(acknowledged: Boolean)
    fun setInstallPackagesDisclosureAcknowledged(acknowledged: Boolean)
    fun setClipboardShareMode(mode: ClipboardShareMode)
    fun setClipboardTargetDeviceIds(deviceIds: Set<String>)
    fun setClipboardTargetDevice(deviceId: String, selected: Boolean)
    fun setClipboardTargetConfigured(configured: Boolean)
    fun setClipboardOptInPromptShown(shown: Boolean)
    fun setClipboardViaCellularEnabled(enabled: Boolean)
    fun setClipboardAccessibilityEnabled(enabled: Boolean)
    fun setClipboardSendNotificationEnabled(enabled: Boolean)
    fun setClipboardShizukuEnabled(enabled: Boolean)
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
    fun themeIconStyleFor(theme: AppTheme): ThemeIconStyle
    fun setThemeIconStyle(theme: AppTheme, style: ThemeIconStyle)
    fun setKineticStyle(style: KineticStyle)
    fun setBulletinBoardStyle(style: BulletinBoardStyle)
    fun setKineticSphereCleanMode(enabled: Boolean)
    fun setKineticSphereConnectedLinesEnabled(enabled: Boolean)
    fun setKineticSphereOrbitalRingsEnabled(enabled: Boolean)
    fun setKineticSpherePersistentWallpaperEnabled(enabled: Boolean)
    fun setSettingsGroupGeneralExpanded(expanded: Boolean)
    fun setSettingsGroupSystemPerformanceExpanded(expanded: Boolean)
    fun setSettingsGroupAppearanceBehaviorExpanded(expanded: Boolean)
    fun setSettingsGroupSecurityAccountExpanded(expanded: Boolean)
    fun setSettingsGroupRemoteAccessExpanded(expanded: Boolean)
    fun setShowAdvancedSettings(show: Boolean)
    fun setTailscaleSetupExpanded(expanded: Boolean)
    fun setTailscaleEnabled(enabled: Boolean)
    fun setIrohEnabled(enabled: Boolean)
    fun setIrohAdvancedDebug(enabled: Boolean)
    fun setIrohRestWhenIdle(enabled: Boolean)

    /** Access token for the self-hosted relay, empty when it has none. */
    fun irohRelayToken(): String

    /**
     * Saves the relay. A blank or invalid [url] saves nothing and turns the relay off; returns whether it is on.
     * Turning it off keeps the saved address and token for next time.
     */
    fun saveIrohRelay(enabled: Boolean, url: String, token: String): Boolean

    /** Base64 of the 32-byte Iroh secret key, or empty before the first start. */
    fun irohSecretKeyBase64(): String
    fun setIrohSecretKeyBase64(value: String)

    /** Base64 of the Friends Iroh key (never the main one), or empty before friends are first used. */
    fun friendsSecretKeyBase64(): String
    fun setFriendsSecretKeyBase64(value: String)
    /** Key that seals the sensitive database columns; sealed by the platform vault like the other secret keys. */
    fun dbFieldKeyBase64(): String
    fun setDbFieldKeyBase64(value: String)
    /** False while a new key exists only in memory because the platform vault could not seal it yet. */
    fun dbFieldKeyIsStored(): Boolean
    /** The key that seals this user's cloud device records, as JSON; sealed by the platform vault. */
    fun clusterRecordKeyJson(): String
    fun setClusterRecordKeyJson(value: String)
    /** The removal stamp this device already signed out of Google for, so a later sign-in on purpose is left alone. */
    fun kickHandledVersion(): Long
    fun setKickHandledVersion(value: Long)

    /** Friends traffic goes through the relay only, so a friend never sees this device's IP address. */
    fun friendsHideIp(): Boolean
    fun setFriendsHideIp(value: Boolean)

    /** Friend keys pinned to the home Devices page, comma separated. */
    fun friendsPinnedKeys(): String
    fun setFriendsPinnedKeys(value: String)

    /** Largest file accepted from a friend, in MB; 0 means no limit. */
    fun friendsMaxFileMb(): Int
    fun setFriendsMaxFileMb(value: Int)

    /** The "How friends work" explanation is shown once; true after the user continued past it. */
    fun friendsIntroSeen(): Boolean
    fun setFriendsIntroSeen(value: Boolean)

    /** JSON from [com.fileapex.iroh.IrohPeerBook]. */
    fun irohPeersJson(): String
    fun setIrohPeersJson(value: String)
    fun setTailscaleAuthKey(authKey: String)
    fun setTailscaleAuthKeyFingerprint(fingerprint: String)


    fun setPinRequiredEnabled(enabled: Boolean)
    fun setDevicePin(pinValue: String)
    fun setPinIdleTimeout(timeout: PinIdleTimeout)
    fun setCheckForUpdatesEnabled(enabled: Boolean)
    fun setUpdateCheckPromptShown(shown: Boolean)

    /** True once the one-time "check out the other themes" hint was shown or is no longer needed. */
    val otherThemesHintShown: StateFlow<Boolean>

    /** Broadcast Notifications (phone side): all off until the user opts in. */
    val notificationBroadcastEnabled: StateFlow<Boolean>
    fun setNotificationBroadcastEnabled(enabled: Boolean)
    /** The one paired device notifications go to; empty means the only paired device. */
    val notificationBroadcastTargetDeviceId: StateFlow<String>
    fun setNotificationBroadcastTargetDeviceId(deviceId: String)
    /** When on, reading a notification on the other device clears it on this one. Clearing the other way is always on. */
    /** The user agreed to the notification access disclosure; the Broadcast Notifications page stays behind it until then. */
    val notificationDisclosureAccepted: StateFlow<Boolean>
    fun setNotificationDisclosureAccepted(accepted: Boolean)
    val notificationBroadcastSyncDismissal: StateFlow<Boolean>
    fun setNotificationBroadcastSyncDismissal(enabled: Boolean)
    /** Windows only: pop up notifications received from the paired phone. */
    val deviceNotificationPopups: StateFlow<Boolean>
    fun setDeviceNotificationPopups(enabled: Boolean)
    val notificationBroadcastVerificationCodes: StateFlow<Boolean>
    fun setNotificationBroadcastVerificationCodes(enabled: Boolean)
    /** Package names allowed to broadcast. Empty by default. */
    val notificationBroadcastApps: StateFlow<Set<String>>
    fun setNotificationBroadcastApps(packages: Set<String>)
    /** After a wipe the install is new again: a leftover database file must not read as an upgrade. */
    fun markThemeDefaultDecided()
    /** Device the Simple theme browses, shows on Home and sends the clipboard to; empty means first available. */
    val simpleActiveDeviceId: StateFlow<String>
    fun setSimpleActiveDeviceId(deviceId: String)
    /** Phone only, hidden: JSON of the last notification companion grant a paired computer sent; empty when none. */
    val notificationCompanionGrant: StateFlow<String>
    fun setNotificationCompanionGrant(json: String)
    fun setOtherThemesHintShown(shown: Boolean)
    fun setCheckForUpdatesInterval(unit: UpdateCheckUnit, amount: Int)
    fun setLastUpdateCheckEpochMs(epochMs: Long)

    fun setSkippedUpdateVersion(version: String)

    fun setEnableServiceWatchdog(enabled: Boolean)

    fun setAutoLaunchOnReboot(enabled: Boolean)

    fun setDeviceOrderIds(encodedOrder: String)

    fun setDeviceOrderUpdatedAtEpochMs(epochMs: Long)
    fun setClusterMembershipVersion(version: Long)
    fun setMembershipProtocolSinceEpochMs(epochMs: Long)

    fun setDesktopLayoutMode(mode: DesktopLayoutMode)

    /** [persist] false updates the flow only; drag gestures persist once on drag end. */
    fun setDesktopSplitFraction(fraction: Float, persist: Boolean = true)
    fun setExplorerSplitFraction(fraction: Float, persist: Boolean = true)
    fun toggleExplorerFavorite(absolutePath: String)
    fun updateBackupConfig(transform: (com.fileapex.domain.backup.BackupConfig) -> com.fileapex.domain.backup.BackupConfig)
    fun setExplorerSplitEnabled(enabled: Boolean)

    fun setDesktopUiStyle(style: DesktopUiStyle)

    fun setExplorerViewMode(mode: ExplorerViewMode)

    fun setDevicesViewMode(mode: ExplorerViewMode)

    fun setKineticNodeOffset(deviceId: String, dx: Float, dy: Float, persist: Boolean = true)

    fun setKineticNodeOffsets(offsets: Map<String, Pair<Float, Float>>, persist: Boolean = true)
    fun resetKineticNodeOffsets()

    fun setFreestyleOptionsPosition(mode: FreestyleLayoutMode, x: Float?, y: Float?)
    fun setFreestyleOptionsPosition(isCard: Boolean, x: Float?, y: Float?)
    fun setFreestyleLayoutMode(mode: FreestyleLayoutMode)
    fun setFreestyleEditTutorialShown(shown: Boolean)
    fun setFreestyleNodeOffset(mode: FreestyleLayoutMode, deviceId: String, x: Float, y: Float)
    fun setFreestyleCardNodeOffset(deviceId: String, x: Float, y: Float)
    fun setFreestyleCardVerticalNodeOffset(deviceId: String, x: Float, y: Float)
    fun setFreestyleTileNodeOffset(deviceId: String, x: Float, y: Float)
    fun setFreestyleMenuOrder(mode: FreestyleLayoutMode, deviceId: String, order: String)
    fun setFreestyleCardMenuOrder(deviceId: String, order: String)
    fun setFreestyleCardVerticalMenuOrder(deviceId: String, order: String)
    fun setFreestyleTileMenuOrder(deviceId: String, order: String)
    fun setFreestyleOptionsMenuOrder(order: String)
    fun setFreestylePinnedActionOffset(mode: FreestyleLayoutMode, actionKey: String, x: Float, y: Float)
    fun removeFreestylePinnedAction(mode: FreestyleLayoutMode, actionKey: String)
    fun resetFreestylePinnedActions(mode: FreestyleLayoutMode)

    fun setDeviceDetailsDisplayPreferences(preferences: DeviceDetailsDisplayPreferences)

    fun setDeviceDetailsAllowOverCellular(enabled: Boolean)

    fun setCellularEnabled(enabled: Boolean)

    fun setGoogleDriveRelayEnabled(enabled: Boolean)

    fun setDriveRelayMaxMb(limit: DriveRelayMaxMb)

    fun setDrivePurgeAfter72Hours(enabled: Boolean)

    fun setDriveRelayEncryptionEnabled(enabled: Boolean)

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

    fun resetToDefaults()
}

expect fun createAppSettings(): AppSettings
