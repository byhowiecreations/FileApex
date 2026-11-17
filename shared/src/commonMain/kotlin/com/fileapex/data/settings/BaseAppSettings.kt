package com.fileapex.data.settings

import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.iroh.IrohRelaySetting
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
    fun clear()
    fun keys(prefix: String): List<String>
    fun remove(key: String)
}

/**
 * Shared AppSettings logic, Android/Desktop only supply a [SettingsKvStore].
 */
class BaseAppSettings(
    private val store: SettingsKvStore,
    /** True when this install ran an earlier version (platform checks for its database file). */
    existingInstall: Boolean = false
) : AppSettings {
    private val googleAccount = GoogleAccountStore(store)
    private val clipboard = ClipboardSettingsStore(store)
    private val appLanguageTagFlow = MutableStateFlow(store.getString(KEY_APP_LANGUAGE, ""))
    private val clipboardPrivateKeyBase64Stored = MutableStateFlow(
        store.getString(KEY_CLIPBOARD_PRIVATE_KEY, "")
    )
    private val notifications = NotificationSettingsStore(store)
    private val bulletinRemoteFilePurgePreferenceFlow =
        MutableStateFlow(
            BulletinRemoteFilePurgePreference.fromStorage(
                store.getString(KEY_BULLETIN_REMOTE_FILE_PURGE, "")
            )
        )
    private val liveTransferCapsuleFlow =
        MutableStateFlow(store.getBoolean(KEY_LIVE_TRANSFER_CAPSULE, false))
    private val liveTransferShowQueueFlow =
        MutableStateFlow(store.getBoolean(KEY_LIVE_TRANSFER_SHOW_QUEUE, false))
    private val pinRequired = MutableStateFlow(store.getBoolean(KEY_PIN_REQUIRED, false))

    private val pin = MutableStateFlow(store.getString(KEY_DEVICE_PIN, ""))
    private val pinIdle = MutableStateFlow(
        PinIdleTimeout.fromStorage(store.getString(KEY_PIN_IDLE_TIMEOUT, PinIdleTimeout.DEFAULT.name))
    )
    private val updateCheck = UpdateCheckStore(store)
    override val updateCheckPromptShown: StateFlow<Boolean> = updateCheck.promptShown
    private val postPairSetupDismissedFlow = MutableStateFlow(store.getBoolean(KEY_POST_PAIR_SETUP_DISMISSED, false))
    override val postPairSetupBannerDismissed: StateFlow<Boolean> = postPairSetupDismissedFlow.asStateFlow()
    private val serviceWatchdog = MutableStateFlow(store.getBoolean(KEY_SERVICE_WATCHDOG, true))
    private val autoLaunchOnRebootFlow = MutableStateFlow(store.getBoolean(KEY_AUTO_LAUNCH_ON_REBOOT, true))
    private val desktopAutostartFlow = MutableStateFlow(store.getBoolean(KEY_DESKTOP_AUTOSTART, false))
    private val deviceOrderIdsFlow = MutableStateFlow(store.getString(KEY_DEVICE_ORDER, ""))
    private val deviceOrderUpdatedAt = MutableStateFlow(store.getLong(KEY_DEVICE_ORDER_UPDATED_AT, 0L))
    private val clusterMembershipVersionFlow = MutableStateFlow(store.getLong(KEY_CLUSTER_MEMBERSHIP_VERSION, 0L))
    private val membershipProtocolSinceFlow = MutableStateFlow(store.getLong(KEY_MEMBERSHIP_PROTOCOL_SINCE, 0L))
    private val desktopLayout = MutableStateFlow(
        DesktopLayoutMode.fromStorage(store.getString(KEY_DESKTOP_LAYOUT, DesktopLayoutMode.DEFAULT.name))
    )
    private val desktopNavPositionFlow = MutableStateFlow(
        DesktopNavPosition.fromStorage(store.getString(KEY_DESKTOP_NAV_POSITION, DesktopNavPosition.DEFAULT.name))
    )
    private val desktopSplitFractionFlow = MutableStateFlow(
        (store.getString(KEY_DESKTOP_SPLIT_FRACTION, "").toFloatOrNull() ?: 0.35f).coerceIn(0.20f, 0.65f)
    )
    override val desktopSplitFraction: StateFlow<Float> = desktopSplitFractionFlow.asStateFlow()
    private val explorerSplitFractionFlow = MutableStateFlow(
        (store.getString(KEY_EXPLORER_SPLIT_FRACTION, "").toFloatOrNull() ?: 0.38f).coerceIn(0.20f, 0.70f)
    )
    override val explorerSplitFraction: StateFlow<Float> = explorerSplitFractionFlow.asStateFlow()
    private val explorerFavoritesStore = ExplorerFavoritesStore(store)
    override val explorerFavorites: StateFlow<List<String>> = explorerFavoritesStore.favorites
    private val backupConfigFlow = MutableStateFlow(
        com.fileapex.domain.backup.BackupConfig.decode(store.getString(KEY_BACKUP_CONFIG, ""))
    )
    override val backupConfig: StateFlow<com.fileapex.domain.backup.BackupConfig> = backupConfigFlow.asStateFlow()
    private val explorerSplitEnabledFlow = MutableStateFlow(
        store.getString(KEY_EXPLORER_SPLIT_ENABLED, "true") != "false"
    )
    override val explorerSplitEnabled: StateFlow<Boolean> = explorerSplitEnabledFlow.asStateFlow()
    private val desktopUiStyleFlow = MutableStateFlow(
        DesktopUiStyle.fromStorage(store.getString(KEY_DESKTOP_UI_STYLE, DesktopUiStyle.DEFAULT.name))
    )
    private val explorerViewModeFlow = MutableStateFlow(
        ExplorerViewMode.fromStorage(store.getString(KEY_EXPLORER_VIEW_MODE, ExplorerViewMode.List.name))
    )
    private val devicesViewModeFlow = MutableStateFlow(
        ExplorerViewMode.fromStorage(store.getString(KEY_DEVICES_VIEW_MODE, ExplorerViewMode.List.name))
    )
    private val kineticPositions = DevicePositionTable(
        store, "kno:", listOf(KEY_KINETIC_NODE_OFFSETS), listOf(KEY_KINETIC_NODE_OFFSETS)
    )
    private val deviceDetailsDisplayPreferencesFlow = MutableStateFlow(
        DeviceDetailsDisplayPreferences.decode(
            store.getString(KEY_DEVICE_DETAILS_DISPLAY, "")
        )
    )
    private val deviceDetailsAllowOverCellularFlow = MutableStateFlow(
        store.getBoolean(KEY_DEVICE_DETAILS_ALLOW_CELLULAR, false)
    )
    private val cellularEnabledFlow = MutableStateFlow(
        store.getBoolean(KEY_CELLULAR_ENABLED, false)
    )
    private val googleDriveRelayEnabledFlow = MutableStateFlow(
        store.getBoolean(KEY_GOOGLE_DRIVE_RELAY, false)
    )
    private val driveRelayMaxMbFlow = MutableStateFlow(
        DriveRelayMaxMb.fromStorage(store.getInt(KEY_DRIVE_RELAY_MAX_MB, DriveRelayMaxMb.DEFAULT.megabytes))
    )
    private val drivePurgeAfter72HoursFlow = MutableStateFlow(
        store.getBoolean(KEY_DRIVE_PURGE_AFTER_72H, true)
    )
    private val driveRelayEncryptionFlow = MutableStateFlow(
        store.getBoolean(KEY_DRIVE_RELAY_ENCRYPTION, false)
    )
    private val cellularSendPromptAcknowledgedFlow = MutableStateFlow(
        store.getBoolean(KEY_CELLULAR_SEND_PROMPT_ACK, false)
    )
    private val cellularReceivePromptAcknowledgedFlow = MutableStateFlow(
        store.getBoolean(KEY_CELLULAR_RECEIVE_PROMPT_ACK, false)
    )
    private val driveRelayOptInPromptShownFlow = MutableStateFlow(
        store.getBoolean(KEY_DRIVE_RELAY_OPT_IN_PROMPT_SHOWN, false)
    )
    private val themeStyles = ThemeStyleStore(store)
    init {
        themeStyles.decideDefaultTheme(existingInstall)
    }

    private val otherThemesHintShownFlow = MutableStateFlow(store.getBoolean(KEY_OTHER_THEMES_HINT_SHOWN, false))
    override val otherThemesHintShown: StateFlow<Boolean> = otherThemesHintShownFlow.asStateFlow()
    private val welcomeTutorialShownFlow = MutableStateFlow(store.getBoolean(KEY_WELCOME_TUTORIAL_SHOWN, false))
    override val welcomeTutorialShown: StateFlow<Boolean> = welcomeTutorialShownFlow.asStateFlow()
    override val notificationBroadcastEnabled: StateFlow<Boolean> = notifications.broadcastEnabled
    override val notificationBroadcastTargetDeviceId: StateFlow<String> = notifications.broadcastTargetDeviceId
    override val notificationBroadcastVerificationCodes: StateFlow<Boolean> = notifications.broadcastVerificationCodes
    override val notificationDisclosureAccepted: StateFlow<Boolean> = notifications.disclosureAccepted
    override val notificationBroadcastSyncDismissal: StateFlow<Boolean> = notifications.broadcastSyncDismissal
    override val deviceNotificationPopups: StateFlow<Boolean> = notifications.devicePopups
    override val notificationBroadcastApps: StateFlow<Set<String>> = notifications.broadcastApps
    private val simpleActiveDeviceIdFlow = MutableStateFlow(store.getString(KEY_SIMPLE_ACTIVE_DEVICE_ID, ""))
    override val simpleActiveDeviceId: StateFlow<String> = simpleActiveDeviceIdFlow.asStateFlow()
    override val pairLink = PairLinkSettings(store)
    override val motion = MotionSettings(store)
    private val appThemeFlow = MutableStateFlow(
        AppTheme.fromStorage(store.getString(KEY_APP_THEME, AppTheme.DEFAULT.name))
    )
    override val noticeSettings = com.fileapex.notice.NoticeSettingsStore(store)
    override val carouselFallbackTheme: StateFlow<AppTheme> = themeStyles.fallbackTheme
    override val carouselFavoriteDevice: StateFlow<String> = themeStyles.favoriteDevice
    override fun setCarouselFavoriteDevice(deviceId: String) = themeStyles.setFavoriteDevice(deviceId)
    private val themeIconStyleFlow = MutableStateFlow(themeStyles.iconStyleFor(appThemeFlow.value))
    override val themeIconStyle: StateFlow<ThemeIconStyle> = themeIconStyleFlow.asStateFlow()

    private val kineticStyleFlow = MutableStateFlow(
        KineticStyle.fromStorage(store.getString(KEY_KINETIC_STYLE, KineticStyle.DEFAULT.name))
    )
    override val kineticStyle: StateFlow<KineticStyle> = kineticStyleFlow.asStateFlow()

    override fun themeIconStyleFor(theme: AppTheme): ThemeIconStyle = themeStyles.iconStyleFor(theme)

    override fun setThemeIconStyle(theme: AppTheme, style: ThemeIconStyle) {
        themeStyles.setIconStyle(theme, style)
        themeIconStyleFlow.value = themeStyles.iconStyleFor(appThemeFlow.value)
    }

    override fun setKineticStyle(style: KineticStyle) {
        store.putString(KEY_KINETIC_STYLE, style.name)
        kineticStyleFlow.value = style
    }

    private val bulletinBoardStyleFlow = MutableStateFlow(
        BulletinBoardStyle.fromStorage(store.getString(KEY_BULLETIN_BOARD_STYLE, BulletinBoardStyle.DEFAULT_STYLE.storageKey))
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
    private val kineticSpherePersistentWallpaperFlow = MutableStateFlow(
        store.getBoolean(KEY_KINETIC_SPHERE_PERSISTENT_WALLPAPER, false)
    )
    private val freestyle = FreestyleLayoutStore(store)
    override val settingsGroups = SettingsGroupStore(store)
    private val showAdvancedSettingsFlow = MutableStateFlow(store.getBoolean(KEY_SHOW_ADVANCED_SETTINGS, false))
    private val tailscaleSetupExpandedFlow = MutableStateFlow(
        store.getBoolean(KEY_TAILSCALE_SETUP_EXPANDED, false)
    )
    private val tailscaleEnabledFlow = MutableStateFlow(
        store.getBoolean(KEY_TAILSCALE_ENABLED, false)
    )
    private val irohEnabledFlow = MutableStateFlow(
        store.getBoolean(KEY_IROH_ENABLED, false)
    )
    private val irohRelay = IrohRelaySettingsStore(store)
    private val irohRestWhenIdleFlow = MutableStateFlow(
        store.getBoolean(KEY_IROH_REST_WHEN_IDLE, true)
    )
    private val irohAdvancedDebugFlow = MutableStateFlow(
        store.getBoolean(KEY_IROH_ADVANCED_DEBUG, false)
    )
    private val tailscaleAuthKeyFlow = MutableStateFlow(
        store.getString(KEY_TAILSCALE_AUTH_KEY, "")
    )
    private val tailscaleAuthKeyFingerprintFlow = MutableStateFlow(
        store.getString(KEY_TAILSCALE_AUTH_KEY_FINGERPRINT, "")
    )
    private val diagnosticsPrivateKeyBase64Stored = MutableStateFlow(
        store.getString(KEY_DIAGNOSTICS_PRIVATE_KEY, "")
    )


    override val googleAccountLinkEnabled: StateFlow<Boolean> = googleAccount.linked
    override val googleAccountEmail: StateFlow<String> = googleAccount.email
    override val googleAccountUid: StateFlow<String> = googleAccount.uid
    override val googleRestorePending: StateFlow<Boolean> = googleAccount.restorePending
    override val googleBackupEmailHint: StateFlow<String> = googleAccount.backupEmailHint
    override val multiCopyIntroAcknowledged: StateFlow<Boolean> = clipboard.multiCopyIntroAcknowledged
    override val clipboardSharingEnabled: StateFlow<Boolean> = clipboard.sharingEnabled
    override val clipboardDisclosureAcknowledged: StateFlow<Boolean> = clipboard.disclosureAcknowledged
    override val accessibilityDisclosureAcknowledged: StateFlow<Boolean> = clipboard.accessibilityDisclosureAcknowledged
    override val installPackagesDisclosureAcknowledged: StateFlow<Boolean> = clipboard.installPackagesDisclosureAcknowledged
    override val clipboardShareMode: StateFlow<ClipboardShareMode> = clipboard.shareMode
    override val clipboardTargetDeviceIds: StateFlow<Set<String>> = clipboard.targetDeviceIds
    override val clipboardTargetConfigured: StateFlow<Boolean> = clipboard.targetConfigured
    override val clipboardOptInPromptShown: StateFlow<Boolean> = clipboard.optInPromptShown
    override val clipboardViaCellularEnabled: StateFlow<Boolean> = clipboard.viaCellularEnabled
    override val clipboardAccessibilityEnabled: StateFlow<Boolean> = clipboard.accessibilityEnabled
    override val clipboardSendNotificationEnabled: StateFlow<Boolean> = clipboard.sendNotificationEnabled
    override val clipboardShizukuEnabled: StateFlow<Boolean> = clipboard.shizukuEnabled
    override val clipboardAutoSendEnabled: StateFlow<Boolean> = clipboard.autoSendEnabled
    override val clipboardImagesEnabled: StateFlow<Boolean> = clipboard.imagesEnabled
    override val appLanguageTag: StateFlow<String> = appLanguageTagFlow.asStateFlow()
    override val fileTransferNotificationsEnabled: StateFlow<Boolean> = notifications.fileTransferEnabled
    override val driveRelayNotificationsEnabled: StateFlow<Boolean> = notifications.driveRelayEnabled
    override val notesNotificationsEnabled: StateFlow<Boolean> = notifications.notesEnabled
    override val notesNotificationPromptShown: StateFlow<Boolean> = notifications.notesPromptShownFlow
    override val bulletinRemoteFilePurgePreference: StateFlow<BulletinRemoteFilePurgePreference> =
        bulletinRemoteFilePurgePreferenceFlow.asStateFlow()
    override val liveTransferCapsuleEnabled: StateFlow<Boolean> =
        liveTransferCapsuleFlow.asStateFlow()
    override val liveTransferShowQueueEnabled: StateFlow<Boolean> =
        liveTransferShowQueueFlow.asStateFlow()
    override val appTheme: StateFlow<AppTheme> = appThemeFlow.asStateFlow()
    override val bulletinBoardStyle: StateFlow<BulletinBoardStyle> = bulletinBoardStyleFlow.asStateFlow()
    override val kineticSphereCleanMode: StateFlow<Boolean> = kineticSphereCleanModeFlow.asStateFlow()
    override val kineticSphereConnectedLinesEnabled: StateFlow<Boolean> = kineticSphereConnectedLinesFlow.asStateFlow()
    override val kineticSphereOrbitalRingsEnabled: StateFlow<Boolean> = kineticSphereOrbitalRingsFlow.asStateFlow()
    override val kineticSpherePersistentWallpaperEnabled: StateFlow<Boolean> = kineticSpherePersistentWallpaperFlow.asStateFlow()
    override val freestyleCardOptionsPosX: StateFlow<Float?> = freestyle.optionsPosX(FreestyleLayoutMode.CARDS_HORIZONTAL)
    override val freestyleCardOptionsPosY: StateFlow<Float?> = freestyle.optionsPosY(FreestyleLayoutMode.CARDS_HORIZONTAL)
    override val freestyleCardVerticalOptionsPosX: StateFlow<Float?> = freestyle.optionsPosX(FreestyleLayoutMode.CARDS_VERTICAL)
    override val freestyleCardVerticalOptionsPosY: StateFlow<Float?> = freestyle.optionsPosY(FreestyleLayoutMode.CARDS_VERTICAL)
    override val freestyleTileOptionsPosX: StateFlow<Float?> = freestyle.optionsPosX(FreestyleLayoutMode.TILES)
    override val freestyleTileOptionsPosY: StateFlow<Float?> = freestyle.optionsPosY(FreestyleLayoutMode.TILES)
    override val freestyleLayoutMode: StateFlow<FreestyleLayoutMode> = freestyle.layoutModeFlow
    override val freestyleEditTutorialShown: StateFlow<Boolean> = freestyle.editTutorialShownFlow
    override val freestyleCardNodeOffsets: StateFlow<FreestyleOffsets> = freestyle.nodeOffsets(FreestyleLayoutMode.CARDS_HORIZONTAL)
    override val freestyleCardVerticalNodeOffsets: StateFlow<FreestyleOffsets> = freestyle.nodeOffsets(FreestyleLayoutMode.CARDS_VERTICAL)
    override val freestyleTileNodeOffsets: StateFlow<FreestyleOffsets> = freestyle.nodeOffsets(FreestyleLayoutMode.TILES)
    override val freestyleCardMenuOrders: StateFlow<Map<String, String>> = freestyle.menuOrders(FreestyleLayoutMode.CARDS_HORIZONTAL)
    override val freestyleCardVerticalMenuOrders: StateFlow<Map<String, String>> = freestyle.menuOrders(FreestyleLayoutMode.CARDS_VERTICAL)
    override val freestyleTileMenuOrders: StateFlow<Map<String, String>> = freestyle.menuOrders(FreestyleLayoutMode.TILES)
    override val freestyleOptionsMenuOrder: StateFlow<String> = freestyle.optionsMenuOrderFlow
    override val freestyleCardPinnedActions: StateFlow<FreestyleOffsets> = freestyle.pinnedActions(FreestyleLayoutMode.CARDS_HORIZONTAL)
    override val freestyleCardVerticalPinnedActions: StateFlow<FreestyleOffsets> = freestyle.pinnedActions(FreestyleLayoutMode.CARDS_VERTICAL)
    override val freestyleTilePinnedActions: StateFlow<FreestyleOffsets> = freestyle.pinnedActions(FreestyleLayoutMode.TILES)
    override val showAdvancedSettings: StateFlow<Boolean> = showAdvancedSettingsFlow.asStateFlow()
    override val tailscaleSetupExpanded: StateFlow<Boolean> = tailscaleSetupExpandedFlow.asStateFlow()
    override val tailscaleEnabled: StateFlow<Boolean> = tailscaleEnabledFlow.asStateFlow()
    override val irohEnabled: StateFlow<Boolean> = irohEnabledFlow.asStateFlow()
    override val irohRelayEnabled: StateFlow<Boolean> = irohRelay.enabled
    override val irohRelayUrl: StateFlow<String> = irohRelay.url
    override val irohAdvancedDebug: StateFlow<Boolean> = irohAdvancedDebugFlow.asStateFlow()
    override val irohRestWhenIdle: StateFlow<Boolean> = irohRestWhenIdleFlow.asStateFlow()
    override val tailscaleAuthKey: StateFlow<String> = tailscaleAuthKeyFlow.asStateFlow()
    override val tailscaleAuthKeyFingerprint: StateFlow<String> = tailscaleAuthKeyFingerprintFlow.asStateFlow()

    override val pinRequiredEnabled: StateFlow<Boolean> = pinRequired.asStateFlow()
    override val devicePin: StateFlow<String> = pin.asStateFlow()
    override val pinIdleTimeout: StateFlow<PinIdleTimeout> = pinIdle.asStateFlow()
    override val checkForUpdatesEnabled: StateFlow<Boolean> = updateCheck.enabled
    override val checkForUpdatesIntervalUnit: StateFlow<UpdateCheckUnit> = updateCheck.intervalUnit
    override val checkForUpdatesIntervalAmount: StateFlow<Int> = updateCheck.intervalAmount
    override val lastUpdateCheckEpochMs: StateFlow<Long> = updateCheck.lastCheckEpochMs
    override val skippedUpdateVersion: StateFlow<String> = updateCheck.skippedVersion
    override val enableServiceWatchdog: StateFlow<Boolean> = serviceWatchdog.asStateFlow()
    override val autoLaunchOnReboot: StateFlow<Boolean> = autoLaunchOnRebootFlow.asStateFlow()
    override val desktopAutostart: StateFlow<Boolean> = desktopAutostartFlow.asStateFlow()
    override val deviceOrderIds: StateFlow<String> = deviceOrderIdsFlow.asStateFlow()
    override val deviceOrderUpdatedAtEpochMs: StateFlow<Long> = deviceOrderUpdatedAt.asStateFlow()
    override val clusterMembershipVersion: StateFlow<Long> = clusterMembershipVersionFlow.asStateFlow()
    override val membershipProtocolSinceEpochMs: StateFlow<Long> = membershipProtocolSinceFlow.asStateFlow()
    override val desktopLayoutMode: StateFlow<DesktopLayoutMode> = desktopLayout.asStateFlow()
    override val desktopNavPosition: StateFlow<DesktopNavPosition> = desktopNavPositionFlow.asStateFlow()
    override val desktopUiStyle: StateFlow<DesktopUiStyle> = desktopUiStyleFlow.asStateFlow()
    override val explorerViewMode: StateFlow<ExplorerViewMode> = explorerViewModeFlow.asStateFlow()
    override val devicesViewMode: StateFlow<ExplorerViewMode> = devicesViewModeFlow.asStateFlow()
    override val kineticNodeOffsets: StateFlow<Map<String, Pair<Float, Float>>> =
        kineticPositions.flow
    override val deviceDetailsDisplayPreferences: StateFlow<DeviceDetailsDisplayPreferences> =
        deviceDetailsDisplayPreferencesFlow.asStateFlow()
    override val deviceDetailsAllowOverCellular: StateFlow<Boolean> =
        deviceDetailsAllowOverCellularFlow.asStateFlow()
    override val cellularEnabled: StateFlow<Boolean> = cellularEnabledFlow.asStateFlow()
    override val googleDriveRelayEnabled: StateFlow<Boolean> =
        googleDriveRelayEnabledFlow.asStateFlow()
    override val driveRelayMaxMb: StateFlow<DriveRelayMaxMb> =
        driveRelayMaxMbFlow.asStateFlow()
    override val drivePurgeAfter72Hours: StateFlow<Boolean> =
        drivePurgeAfter72HoursFlow.asStateFlow()
    override val driveRelayEncryptionEnabled: StateFlow<Boolean> =
        driveRelayEncryptionFlow.asStateFlow()
    override val cellularSendPromptAcknowledged: StateFlow<Boolean> =
        cellularSendPromptAcknowledgedFlow.asStateFlow()
    override val cellularReceivePromptAcknowledged: StateFlow<Boolean> =
        cellularReceivePromptAcknowledgedFlow.asStateFlow()
    override val driveRelayOptInPromptShown: StateFlow<Boolean> =
        driveRelayOptInPromptShownFlow.asStateFlow()


    override fun setGoogleAccountLinkEnabled(enabled: Boolean) = googleAccount.setLinked(enabled)

    override fun setGoogleAccountEmail(email: String) = googleAccount.setEmail(email)

    override fun setGoogleAccountUid(uid: String) = googleAccount.setUid(uid)

    override fun setGoogleRestorePending(pending: Boolean) = googleAccount.setRestorePending(pending)

    override fun setGoogleBackupEmailHint(email: String) = googleAccount.setBackupEmailHint(email)

    override fun setMultiCopyIntroAcknowledged(acknowledged: Boolean) = clipboard.setMultiCopyIntroAcknowledged(acknowledged)

    override fun setClipboardSharingEnabled(enabled: Boolean) {
        clipboard.setSharingEnabled(enabled)
        com.fileapex.platform.ClipboardAccessibilityHealth.refresh()
        com.fileapex.platform.ClipboardShareChrome.fire()
    }

    override fun setClipboardDisclosureAcknowledged(acknowledged: Boolean) = clipboard.setDisclosureAcknowledged(acknowledged)

    override fun setAccessibilityDisclosureAcknowledged(acknowledged: Boolean) =
        clipboard.setAccessibilityDisclosureAcknowledged(acknowledged)

    override fun setInstallPackagesDisclosureAcknowledged(acknowledged: Boolean) =
        clipboard.setInstallPackagesDisclosureAcknowledged(acknowledged)

    override fun setClipboardShareMode(mode: ClipboardShareMode) = clipboard.setShareMode(mode)

    private val bulletin = BulletinSettings(store)
    override fun bulletinDeltaCursor(deviceId: String): Long = bulletin.deltaCursor(deviceId)
    override fun setBulletinDeltaCursor(deviceId: String, cursor: Long) = bulletin.setDeltaCursor(deviceId, cursor)
    override fun clearBulletinDeltaCursors() = bulletin.clearDeltaCursors()
    override val bulletinSeenAtEpochMs: StateFlow<Long> = bulletin.seenAtEpochMs
    override fun setBulletinSeenAtEpochMs(epochMs: Long) = bulletin.setSeenAt(epochMs)

    override fun setClipboardTargetDeviceIds(deviceIds: Set<String>) = clipboard.setTargetDeviceIds(deviceIds)

    override fun setClipboardTargetDevice(deviceId: String, selected: Boolean) = clipboard.setTargetDevice(deviceId, selected)

    override fun setClipboardTargetConfigured(configured: Boolean) = clipboard.setTargetConfigured(configured)

    override fun setClipboardOptInPromptShown(shown: Boolean) = clipboard.setOptInPromptShown(shown)

    override fun setClipboardViaCellularEnabled(enabled: Boolean) = clipboard.setViaCellularEnabled(enabled)

    override fun setClipboardAccessibilityEnabled(enabled: Boolean) {
        clipboard.setAccessibilityEnabled(enabled)
        com.fileapex.platform.ClipboardAccessibilityHealth.refresh()
        com.fileapex.platform.ClipboardShareChrome.fire()
    }

    override fun setClipboardSendNotificationEnabled(enabled: Boolean) {
        clipboard.setSendNotificationEnabled(enabled)
        com.fileapex.platform.ClipboardShareChrome.fire()
    }

    override fun setClipboardShizukuEnabled(enabled: Boolean) {
        clipboard.setShizukuEnabled(enabled)
        com.fileapex.platform.ClipboardChangeMonitor.onShizukuOptInChanged()
    }

    override fun setClipboardAutoSendEnabled(enabled: Boolean) = clipboard.setAutoSendEnabled(enabled)

    override fun setClipboardImagesEnabled(enabled: Boolean) = clipboard.setImagesEnabled(enabled)

    override fun setAppLanguageTag(tag: String) {
        store.putString(KEY_APP_LANGUAGE, tag)
        appLanguageTagFlow.value = tag
    }

    override fun clipboardPrivateKeyBase64(): String = clipboardPrivateKeyBase64Stored.value

    override fun setClipboardPrivateKeyBase64(value: String) {
        val cleaned = value.trim()
        store.putString(KEY_CLIPBOARD_PRIVATE_KEY, cleaned)
        clipboardPrivateKeyBase64Stored.value = cleaned
    }

    override fun setFileTransferNotificationsEnabled(enabled: Boolean) = notifications.setFileTransferEnabled(enabled)

    override fun setDriveRelayNotificationsEnabled(enabled: Boolean) = notifications.setDriveRelayEnabled(enabled)

    override fun setNotesNotificationsEnabled(enabled: Boolean) = notifications.setNotesEnabled(enabled)

    override fun setNotesNotificationPromptShown(shown: Boolean) = notifications.setNotesPromptShown(shown)

    override fun setBulletinRemoteFilePurgePreference(preference: BulletinRemoteFilePurgePreference) {
        store.putString(KEY_BULLETIN_REMOTE_FILE_PURGE, preference.name)
        bulletinRemoteFilePurgePreferenceFlow.value = preference
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
        themeStyles.themeChosen(appThemeFlow.value, theme)
        store.putString(KEY_APP_THEME, theme.name)
        appThemeFlow.value = theme
        themeIconStyleFlow.value = themeStyles.iconStyleFor(theme)
    }

    override fun setBulletinBoardStyle(style: BulletinBoardStyle) {
        store.putString(KEY_BULLETIN_BOARD_STYLE, style.storageKey)
        bulletinBoardStyleFlow.value = style
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

    override fun setKineticSpherePersistentWallpaperEnabled(enabled: Boolean) {
        store.putBoolean(KEY_KINETIC_SPHERE_PERSISTENT_WALLPAPER, enabled)
        kineticSpherePersistentWallpaperFlow.value = enabled
    }

    override fun setShowAdvancedSettings(show: Boolean) {
        store.putBoolean(KEY_SHOW_ADVANCED_SETTINGS, show)
        showAdvancedSettingsFlow.value = show
    }

    override fun setTailscaleSetupExpanded(expanded: Boolean) {
        store.putBoolean(KEY_TAILSCALE_SETUP_EXPANDED, expanded)
        tailscaleSetupExpandedFlow.value = expanded
    }

    override fun setTailscaleEnabled(enabled: Boolean) {
        store.putBoolean(KEY_TAILSCALE_ENABLED, enabled)
        tailscaleEnabledFlow.value = enabled
    }

    override fun setIrohEnabled(enabled: Boolean) {
        store.putBoolean(KEY_IROH_ENABLED, enabled)
        irohEnabledFlow.value = enabled
    }

    override fun setIrohRestWhenIdle(enabled: Boolean) {
        store.putBoolean(KEY_IROH_REST_WHEN_IDLE, enabled)
        irohRestWhenIdleFlow.value = enabled
    }

    override fun setIrohAdvancedDebug(enabled: Boolean) {
        store.putBoolean(KEY_IROH_ADVANCED_DEBUG, enabled)
        irohAdvancedDebugFlow.value = enabled
    }

    override fun irohRelayToken(): String = irohRelay.token()

    override fun saveIrohRelay(enabled: Boolean, url: String, token: String): Boolean = irohRelay.save(enabled, url, token)

    override fun irohRelaySetting(): IrohRelaySetting = irohRelay.current()

    override fun applyClusterIrohRelay(setting: IrohRelaySetting) = irohRelay.apply(setting)

    override fun irohSecretKeyBase64(): String = store.getString(KEY_IROH_SECRET_KEY, "")

    override fun setIrohSecretKeyBase64(value: String) {
        store.putString(KEY_IROH_SECRET_KEY, value.trim())
    }

    override fun friendsSecretKeyBase64(): String = store.getString(KEY_FRIENDS_SECRET_KEY, "")

    override fun dbFieldKeyBase64(): String = store.getString(KEY_DB_FIELD_KEY, "")

    override fun setDbFieldKeyBase64(value: String) = store.putString(KEY_DB_FIELD_KEY, value.trim())

    override fun clusterRecordKeyJson(): String = store.getString(KEY_CLUSTER_RECORD_KEY, "")
    override fun setClusterRecordKeyJson(value: String) = store.putString(KEY_CLUSTER_RECORD_KEY, value.trim())
    override fun kickHandledVersion(): Long = store.getLong(KEY_KICK_HANDLED_VERSION, 0L)
    override fun setKickHandledVersion(value: Long) = store.putLong(KEY_KICK_HANDLED_VERSION, value)

    override fun dbFieldKeyIsStored(): Boolean = (store as? SealingKvStore)?.isPendingSeal(KEY_DB_FIELD_KEY) != true

    override fun setFriendsSecretKeyBase64(value: String) {
        store.putString(KEY_FRIENDS_SECRET_KEY, value.trim())
    }

    private val guestLinkConsentFlow = MutableStateFlow(store.getInt(KEY_GUEST_LINK_CONSENT, 0))
    override val guestLinkConsent: StateFlow<Int> = guestLinkConsentFlow.asStateFlow()

    override fun setGuestLinkConsent(value: Int) {
        store.putInt(KEY_GUEST_LINK_CONSENT, value)
        guestLinkConsentFlow.value = value
    }

    override fun friendsHideIp(): Boolean = store.getBoolean(KEY_FRIENDS_HIDE_IP, false)

    override fun setFriendsHideIp(value: Boolean) {
        store.putBoolean(KEY_FRIENDS_HIDE_IP, value)
    }

    override fun friendsPinnedKeys(): String = store.getString(KEY_FRIENDS_PINNED, "")
    override fun setFriendsPinnedKeys(value: String) = store.putString(KEY_FRIENDS_PINNED, value)
    override fun friendsMaxFileMb(): Int = store.getInt(KEY_FRIENDS_MAX_FILE_MB, 100)
    override fun setFriendsMaxFileMb(value: Int) = store.putInt(KEY_FRIENDS_MAX_FILE_MB, value.coerceAtLeast(0))

    override fun friendsIntroSeen(): Boolean = store.getBoolean(KEY_FRIENDS_INTRO_SEEN, false)

    override fun setFriendsIntroSeen(value: Boolean) {
        store.putBoolean(KEY_FRIENDS_INTRO_SEEN, value)
    }

    override fun irohPeersJson(): String = store.getString(KEY_IROH_PEERS, "")

    override fun setIrohPeersJson(value: String) {
        store.putString(KEY_IROH_PEERS, value)
    }

    override fun setTailscaleAuthKey(authKey: String) {
        store.putString(KEY_TAILSCALE_AUTH_KEY, authKey)
        tailscaleAuthKeyFlow.value = authKey
    }

    override fun setTailscaleAuthKeyFingerprint(fingerprint: String) {
        store.putString(KEY_TAILSCALE_AUTH_KEY_FINGERPRINT, fingerprint)
        tailscaleAuthKeyFingerprintFlow.value = fingerprint
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

    override fun setCheckForUpdatesEnabled(enabled: Boolean) = updateCheck.setEnabled(enabled)

    override fun setCheckForUpdatesInterval(unit: UpdateCheckUnit, amount: Int) = updateCheck.setInterval(unit, amount)

    override fun setLastUpdateCheckEpochMs(epochMs: Long) {
        TimestampDiagnostics.logMutation("AppSettings.lastUpdateCheckEpochMs", epochMs)
        updateCheck.setLastCheck(epochMs)
    }

    override fun setSkippedUpdateVersion(version: String) = updateCheck.setSkippedVersion(version)

    override fun setEnableServiceWatchdog(enabled: Boolean) {
        store.putBoolean(KEY_SERVICE_WATCHDOG, enabled)
        serviceWatchdog.value = enabled
    }

    override fun setDesktopAutostart(enabled: Boolean) {
        store.putBoolean(KEY_DESKTOP_AUTOSTART, enabled)
        desktopAutostartFlow.value = enabled
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

    override fun setClusterMembershipVersion(version: Long) {
        TimestampDiagnostics.logMutation("AppSettings.clusterMembershipVersion", version)
        store.putLong(KEY_CLUSTER_MEMBERSHIP_VERSION, version)
        clusterMembershipVersionFlow.value = version
    }

    override var lanScanBackoffState: String
        get() = store.getString(KEY_LAN_SCAN_BACKOFF, "")
        set(value) = store.putString(KEY_LAN_SCAN_BACKOFF, value)

    override fun setMembershipProtocolSinceEpochMs(epochMs: Long) {
        TimestampDiagnostics.logMutation("AppSettings.membershipProtocolSince", epochMs)
        store.putLong(KEY_MEMBERSHIP_PROTOCOL_SINCE, epochMs)
        membershipProtocolSinceFlow.value = epochMs
    }

    override fun setDesktopLayoutMode(mode: DesktopLayoutMode) {
        store.putString(KEY_DESKTOP_LAYOUT, mode.name)
        desktopLayout.value = mode
    }

    override fun setDesktopNavPosition(position: DesktopNavPosition) {
        store.putString(KEY_DESKTOP_NAV_POSITION, position.name)
        desktopNavPositionFlow.value = position
    }

    override fun setDesktopSplitFraction(fraction: Float, persist: Boolean) {
        val clamped = fraction.coerceIn(0.20f, 0.65f)
        desktopSplitFractionFlow.value = clamped
        if (persist) store.putString(KEY_DESKTOP_SPLIT_FRACTION, clamped.toString())
    }

    override fun setExplorerSplitFraction(fraction: Float, persist: Boolean) {
        val clamped = fraction.coerceIn(0.20f, 0.70f)
        explorerSplitFractionFlow.value = clamped
        if (persist) store.putString(KEY_EXPLORER_SPLIT_FRACTION, clamped.toString())
    }

    override fun updateBackupConfig(
        transform: (com.fileapex.domain.backup.BackupConfig) -> com.fileapex.domain.backup.BackupConfig
    ) {
        val next = transform(backupConfigFlow.value)
        backupConfigFlow.value = next
        store.putString(KEY_BACKUP_CONFIG, next.encode())
        com.fileapex.platform.BackupScheduler.apply(next)
    }

    override fun setNotificationBroadcastEnabled(enabled: Boolean) = notifications.setBroadcastEnabled(enabled)

    override fun setNotificationBroadcastTargetDeviceId(deviceId: String) = notifications.setBroadcastTargetDeviceId(deviceId)

    override fun setNotificationDisclosureAccepted(accepted: Boolean) = notifications.setDisclosureAccepted(accepted)

    override fun setNotificationBroadcastSyncDismissal(enabled: Boolean) = notifications.setBroadcastSyncDismissal(enabled)

    override fun setDeviceNotificationPopups(enabled: Boolean) = notifications.setDevicePopups(enabled)

    override fun setNotificationBroadcastVerificationCodes(enabled: Boolean) =
        notifications.setBroadcastVerificationCodes(enabled)

    override fun setNotificationBroadcastApps(packages: Set<String>) = notifications.setBroadcastApps(packages)

    override fun markThemeDefaultDecided() = themeStyles.markDefaultDecided()

    override fun setSimpleActiveDeviceId(deviceId: String) {
        pairLink.markChosen(deviceId, com.fileapex.util.TimeUtils.now())
        simpleActiveDeviceIdFlow.value = deviceId
        store.putString(KEY_SIMPLE_ACTIVE_DEVICE_ID, deviceId)
    }

    override fun setOtherThemesHintShown(shown: Boolean) {
        otherThemesHintShownFlow.value = shown
        store.putBoolean(KEY_OTHER_THEMES_HINT_SHOWN, shown)
    }

    override fun setWelcomeTutorialShown(shown: Boolean) {
        welcomeTutorialShownFlow.value = shown
        store.putBoolean(KEY_WELCOME_TUTORIAL_SHOWN, shown)
    }

    override fun setUpdateCheckPromptShown(shown: Boolean) = updateCheck.setPromptShown(shown)

    override fun setPostPairSetupBannerDismissed(dismissed: Boolean) {
        postPairSetupDismissedFlow.value = dismissed
        store.putBoolean(KEY_POST_PAIR_SETUP_DISMISSED, dismissed)
    }

    override fun toggleExplorerFavorite(absolutePath: String) = explorerFavoritesStore.toggle(absolutePath)

    override fun setDesktopUiStyle(style: DesktopUiStyle) {
        store.putString(KEY_DESKTOP_UI_STYLE, style.name)
        desktopUiStyleFlow.value = style
    }

    override fun setExplorerSplitEnabled(enabled: Boolean) {
        explorerSplitEnabledFlow.value = enabled
        store.putString(KEY_EXPLORER_SPLIT_ENABLED, enabled.toString())
    }

    override fun setExplorerViewMode(mode: ExplorerViewMode) {
        store.putString(KEY_EXPLORER_VIEW_MODE, mode.name)
        explorerViewModeFlow.value = mode
    }

    override fun setDevicesViewMode(mode: ExplorerViewMode) {
        store.putString(KEY_DEVICES_VIEW_MODE, mode.name)
        devicesViewModeFlow.value = mode
    }

    override fun setKineticNodeOffset(deviceId: String, dx: Float, dy: Float, persist: Boolean) {
        kineticPositions.put(mapOf(deviceId to Pair(dx, dy)), persist)
    }

    override fun setKineticNodeOffsets(offsets: Map<String, Pair<Float, Float>>, persist: Boolean) {
        kineticPositions.put(offsets, persist)
    }

    override fun resetKineticNodeOffsets() {
        kineticPositions.reset()
    }

    override fun setFreestyleOptionsPosition(mode: FreestyleLayoutMode, x: Float?, y: Float?) =
        freestyle.setOptionsPosition(mode, x, y)

    override fun setFreestyleOptionsPosition(isCard: Boolean, x: Float?, y: Float?) =
        freestyle.setOptionsPosition(if (isCard) FreestyleLayoutMode.CARDS_HORIZONTAL else FreestyleLayoutMode.TILES, x, y)

    override fun setFreestyleLayoutMode(mode: FreestyleLayoutMode) = freestyle.setLayoutMode(mode)

    override fun setFreestyleEditTutorialShown(shown: Boolean) = freestyle.setEditTutorialShown(shown)

    override fun setFreestyleNodeOffset(mode: FreestyleLayoutMode, deviceId: String, x: Float, y: Float) =
        freestyle.setNodeOffset(mode, deviceId, x, y)

    override fun setFreestyleCardNodeOffset(deviceId: String, x: Float, y: Float) =
        freestyle.setNodeOffset(FreestyleLayoutMode.CARDS_HORIZONTAL, deviceId, x, y)

    override fun setFreestyleCardVerticalNodeOffset(deviceId: String, x: Float, y: Float) =
        freestyle.setNodeOffset(FreestyleLayoutMode.CARDS_VERTICAL, deviceId, x, y)

    override fun setFreestyleTileNodeOffset(deviceId: String, x: Float, y: Float) =
        freestyle.setNodeOffset(FreestyleLayoutMode.TILES, deviceId, x, y)

    override fun setFreestyleMenuOrder(mode: FreestyleLayoutMode, deviceId: String, order: String) =
        freestyle.setMenuOrder(mode, deviceId, order)

    override fun setFreestyleCardMenuOrder(deviceId: String, order: String) =
        freestyle.setMenuOrder(FreestyleLayoutMode.CARDS_HORIZONTAL, deviceId, order)

    override fun setFreestyleCardVerticalMenuOrder(deviceId: String, order: String) =
        freestyle.setMenuOrder(FreestyleLayoutMode.CARDS_VERTICAL, deviceId, order)

    override fun setFreestyleTileMenuOrder(deviceId: String, order: String) =
        freestyle.setMenuOrder(FreestyleLayoutMode.TILES, deviceId, order)

    override fun setFreestyleOptionsMenuOrder(order: String) = freestyle.setOptionsMenuOrder(order)

    override fun setFreestylePinnedActionOffset(mode: FreestyleLayoutMode, actionKey: String, x: Float, y: Float) =
        freestyle.setPinnedActionOffset(mode, actionKey, x, y)

    override fun removeFreestylePinnedAction(mode: FreestyleLayoutMode, actionKey: String) =
        freestyle.removePinnedAction(mode, actionKey)

    override fun resetFreestylePinnedActions(mode: FreestyleLayoutMode) = freestyle.resetPinnedActions(mode)

    override fun setDeviceDetailsDisplayPreferences(preferences: DeviceDetailsDisplayPreferences) {
        val normalized = preferences.normalized()
        store.putString(KEY_DEVICE_DETAILS_DISPLAY, DeviceDetailsDisplayPreferences.encode(normalized))
        deviceDetailsDisplayPreferencesFlow.value = normalized
    }

    override fun setDeviceDetailsAllowOverCellular(enabled: Boolean) {
        store.putBoolean(KEY_DEVICE_DETAILS_ALLOW_CELLULAR, enabled)
        deviceDetailsAllowOverCellularFlow.value = enabled
    }

    override fun setCellularEnabled(enabled: Boolean) {
        store.putBoolean(KEY_CELLULAR_ENABLED, enabled)
        cellularEnabledFlow.value = enabled
    }

    override fun setGoogleDriveRelayEnabled(enabled: Boolean) {
        store.putBoolean(KEY_GOOGLE_DRIVE_RELAY, enabled)
        googleDriveRelayEnabledFlow.value = enabled
    }

    override fun setDriveRelayMaxMb(limit: DriveRelayMaxMb) {
        store.putInt(KEY_DRIVE_RELAY_MAX_MB, limit.megabytes)
        driveRelayMaxMbFlow.value = limit
    }

    override fun setDriveRelayEncryptionEnabled(enabled: Boolean) {
        store.putBoolean(KEY_DRIVE_RELAY_ENCRYPTION, enabled)
        driveRelayEncryptionFlow.value = enabled
    }

    override fun setDrivePurgeAfter72Hours(enabled: Boolean) {
        store.putBoolean(KEY_DRIVE_PURGE_AFTER_72H, enabled)
        drivePurgeAfter72HoursFlow.value = enabled
    }

    override fun setCellularSendPromptAcknowledged(acknowledged: Boolean) {
        store.putBoolean(KEY_CELLULAR_SEND_PROMPT_ACK, acknowledged)
        cellularSendPromptAcknowledgedFlow.value = acknowledged
    }

    override fun setCellularReceivePromptAcknowledged(acknowledged: Boolean) {
        store.putBoolean(KEY_CELLULAR_RECEIVE_PROMPT_ACK, acknowledged)
        cellularReceivePromptAcknowledgedFlow.value = acknowledged
    }

    override fun setDriveRelayOptInPromptShown(shown: Boolean) {
        store.putBoolean(KEY_DRIVE_RELAY_OPT_IN_PROMPT_SHOWN, shown)
        driveRelayOptInPromptShownFlow.value = shown
    }

    override fun diagnosticsPrivateKeyBase64(): String = diagnosticsPrivateKeyBase64Stored.value

    override fun setDiagnosticsPrivateKeyBase64(value: String) {
        val cleaned = value.trim()
        store.putString(KEY_DIAGNOSTICS_PRIVATE_KEY, cleaned)
        diagnosticsPrivateKeyBase64Stored.value = cleaned
    }

    override fun resetToDefaults() {
        val keepWelcomeTutorial = welcomeTutorialShownFlow.value || store.getBoolean(KEY_WELCOME_TUTORIAL_SHOWN, false)
        store.clear()
        if (keepWelcomeTutorial) {
            store.putBoolean(KEY_WELCOME_TUTORIAL_SHOWN, true)
        }
        noticeSettings.reload()
        googleAccount.forgetInMemory()
        clipboard.forgetInMemory()
        appLanguageTagFlow.value = ""
        clipboardPrivateKeyBase64Stored.value = ""
        notifications.forgetInMemory()
        bulletinRemoteFilePurgePreferenceFlow.value = BulletinRemoteFilePurgePreference.UNCONFIGURED
        liveTransferCapsuleFlow.value = false
        liveTransferShowQueueFlow.value = false
        pinRequired.value = false
        pin.value = ""
        pinIdle.value = PinIdleTimeout.DEFAULT
        updateCheck.forgetInMemory()
        serviceWatchdog.value = true
        autoLaunchOnRebootFlow.value = true
        deviceOrderIdsFlow.value = ""
        deviceOrderUpdatedAt.value = 0L
        clusterMembershipVersionFlow.value = 0L
        membershipProtocolSinceFlow.value = 0L
        desktopLayout.value = DesktopLayoutMode.DEFAULT
        desktopNavPositionFlow.value = DesktopNavPosition.DEFAULT
        desktopSplitFractionFlow.value = 0.35f
        explorerSplitFractionFlow.value = 0.38f
        explorerFavoritesStore.forgetInMemory()
        postPairSetupDismissedFlow.value = false
        otherThemesHintShownFlow.value = false
        welcomeTutorialShownFlow.value = keepWelcomeTutorial
        simpleActiveDeviceIdFlow.value = ""
        pairLink.clear()
        backupConfigFlow.value = com.fileapex.domain.backup.BackupConfig()
        com.fileapex.platform.BackupScheduler.apply(backupConfigFlow.value)
        explorerSplitEnabledFlow.value = true
        desktopUiStyleFlow.value = DesktopUiStyle.DEFAULT
        explorerViewModeFlow.value = ExplorerViewMode.List
        devicesViewModeFlow.value = ExplorerViewMode.List
        kineticPositions.forgetInMemory()
        deviceDetailsDisplayPreferencesFlow.value = DeviceDetailsDisplayPreferences.defaults()
        deviceDetailsAllowOverCellularFlow.value = false
        cellularEnabledFlow.value = false
        googleDriveRelayEnabledFlow.value = false
        driveRelayMaxMbFlow.value = DriveRelayMaxMb.DEFAULT
        drivePurgeAfter72HoursFlow.value = true
        driveRelayEncryptionFlow.value = false
        driveRelayOptInPromptShownFlow.value = false
        cellularSendPromptAcknowledgedFlow.value = false
        cellularReceivePromptAcknowledgedFlow.value = false
        appThemeFlow.value = AppTheme.DEFAULT
        themeStyles.reset()
        themeIconStyleFlow.value = themeStyles.iconStyleFor(AppTheme.DEFAULT)
        kineticStyleFlow.value = KineticStyle.DEFAULT
        bulletinBoardStyleFlow.value = BulletinBoardStyle.DEFAULT
        kineticSphereCleanModeFlow.value = false
        kineticSphereConnectedLinesFlow.value = true
        kineticSphereOrbitalRingsFlow.value = true
        kineticSpherePersistentWallpaperFlow.value = false
        freestyle.forgetInMemory()
        settingsGroups.reset()
        tailscaleSetupExpandedFlow.value = false
        tailscaleEnabledFlow.value = false
        irohEnabledFlow.value = false
        irohRelay.reload()
        irohAdvancedDebugFlow.value = false
        irohRestWhenIdleFlow.value = true
        tailscaleAuthKeyFlow.value = ""
        tailscaleAuthKeyFingerprintFlow.value = ""
        diagnosticsPrivateKeyBase64Stored.value = ""
    }

    companion object {
        /** Never kept in clear text: [SealingKvStore] seals these with the platform's secret store. */
        val SECRET_KEYS: Set<String> = setOf(
            KEY_CLIPBOARD_PRIVATE_KEY, KEY_DIAGNOSTICS_PRIVATE_KEY, KEY_IROH_SECRET_KEY,
            KEY_DEVICE_PIN, KEY_TAILSCALE_AUTH_KEY, KEY_IROH_RELAY_TOKEN, KEY_FRIENDS_SECRET_KEY, KEY_DB_FIELD_KEY, KEY_CLUSTER_RECORD_KEY
        )

        const val KEY_GOOGLE = "google_account_link"
        const val KEY_GOOGLE_EMAIL = "google_account_email"
        const val KEY_GOOGLE_UID = "google_account_uid"
        const val KEY_GOOGLE_RESTORE_PENDING = "google_restore_pending"
        const val KEY_GOOGLE_BACKUP_EMAIL_HINT = "google_backup_email_hint"
        const val KEY_MULTI_COPY_INTRO = "multi_copy_intro_ack"
        const val KEY_CLIPBOARD_SHARING = "clipboard_sharing_enabled"
        const val KEY_CLIPBOARD_DISCLOSURE_ACK = "clipboard_disclosure_acknowledged"
        const val KEY_ACCESSIBILITY_DISCLOSURE_ACK = "accessibility_disclosure_acknowledged"
        const val KEY_INSTALL_PACKAGES_DISCLOSURE_ACK = "install_packages_disclosure_acknowledged"
        const val KEY_CLIPBOARD_SHARE_MODE = "clipboard_share_mode"
        const val KEY_CLIPBOARD_TARGET_DEVICES = "clipboard_target_device_ids"
        const val KEY_CLIPBOARD_TARGET_CONFIGURED = "clipboard_target_configured"
        const val KEY_CLIPBOARD_OPT_IN_PROMPT_SHOWN = "clipboard_opt_in_prompt_shown"
        const val KEY_CLIPBOARD_VIA_CELLULAR = "clipboard_via_cellular"
        const val KEY_CLIPBOARD_ACCESSIBILITY = "clipboard_accessibility"
        const val KEY_CLIPBOARD_SEND_NOTIFICATION = "clipboard_send_notification"
        const val KEY_CLIPBOARD_SHIZUKU = "clipboard_shizuku"
        const val KEY_CLIPBOARD_AUTO_SEND = "clipboard_auto_send"
        const val KEY_CLIPBOARD_IMAGES = "clipboard_images_enabled"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_CLIPBOARD_PRIVATE_KEY = "clipboard_private_key_b64"
        const val KEY_TRANSFER_NOTIFICATIONS = "file_transfer_notifications"
        const val KEY_DRIVE_RELAY_NOTIFICATIONS = "drive_relay_notifications_enabled"
        const val KEY_NOTES_NOTIFICATIONS = "notes_notifications_enabled"
        const val KEY_NOTES_NOTIFICATION_PROMPT_SHOWN = "notes_notification_prompt_shown"
        const val KEY_BULLETIN_REMOTE_FILE_PURGE = "bulletin_remote_file_purge_preference"
        const val KEY_LIVE_TRANSFER_CAPSULE = "live_transfer_capsule_enabled"
        const val KEY_LIVE_TRANSFER_SHOW_QUEUE = "live_transfer_show_queue_enabled"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_NOTIFICATION_BROADCAST_ENABLED = "notification_broadcast_enabled"
        const val KEY_NOTIFICATION_BROADCAST_TARGET = "notification_broadcast_target"
        const val KEY_NOTIFICATION_BROADCAST_CODES = "notification_broadcast_codes"
        const val KEY_NOTIFICATION_BROADCAST_DISMISSAL = "notification_broadcast_dismissal"
        const val KEY_NOTIFICATION_DISCLOSURE_ACCEPTED = "notification_disclosure_accepted"
        const val KEY_DEVICE_NOTIFICATION_POPUPS = "device_notification_popups"
        const val KEY_NOTIFICATION_BROADCAST_APPS = "notification_broadcast_apps"
        const val KEY_SIMPLE_ACTIVE_DEVICE_ID = "simple_active_device_id"
        const val KEY_OTHER_THEMES_HINT_SHOWN = "other_themes_hint_shown"
        const val KEY_WELCOME_TUTORIAL_SHOWN = "welcome_tutorial_shown"
        const val KEY_THEME_DEFAULT_MIGRATED = "theme_default_migrated"
        const val KEY_KINETIC_STYLE = "kinetic_style"
        const val KEY_BULLETIN_BOARD_STYLE = "bulletin_board_style"
        const val KEY_KINETIC_SPHERE_CLEAN_MODE = "kinetic_sphere_clean_mode"
        const val KEY_KINETIC_SPHERE_CONNECTED_LINES = "kinetic_sphere_connected_lines"
        const val KEY_KINETIC_SPHERE_ORBITAL_RINGS = "kinetic_sphere_orbital_rings"
        const val KEY_KINETIC_SPHERE_PERSISTENT_WALLPAPER = "kinetic_sphere_persistent_wallpaper"
        const val KEY_FREESTYLE_CARD_OPTIONS_X = "freestyle_card_options_x"
        const val KEY_FREESTYLE_CARD_OPTIONS_Y = "freestyle_card_options_y"
        const val KEY_FREESTYLE_CARD_VERTICAL_OPTIONS_X = "freestyle_card_vert_options_x"
        const val KEY_FREESTYLE_CARD_VERTICAL_OPTIONS_Y = "freestyle_card_vert_options_y"
        const val KEY_FREESTYLE_TILE_OPTIONS_X = "freestyle_tile_options_x"
        const val KEY_FREESTYLE_TILE_OPTIONS_Y = "freestyle_tile_options_y"
        const val KEY_FREESTYLE_LAYOUT_MODE = "freestyle_layout_mode"
        const val KEY_FREESTYLE_EDIT_TUTORIAL_SHOWN = "freestyle_edit_tutorial_shown"
        const val KEY_FREESTYLE_CARD_MENU_ORDERS = "freestyle_card_menu_orders"
        const val KEY_FREESTYLE_CARD_VERTICAL_MENU_ORDERS = "freestyle_card_vert_menu_orders"
        const val KEY_FREESTYLE_TILE_MENU_ORDERS = "freestyle_tile_menu_orders"
        const val KEY_FREESTYLE_OPTIONS_MENU_ORDER = "freestyle_options_menu_order"
        const val KEY_FREESTYLE_CARD_PINNED_ACTIONS = "freestyle_card_pinned_actions"
        const val KEY_FREESTYLE_CARD_VERTICAL_PINNED_ACTIONS = "freestyle_card_vert_pinned_actions"
        const val KEY_FREESTYLE_TILE_PINNED_ACTIONS = "freestyle_tile_pinned_actions"


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
        const val KEY_DESKTOP_AUTOSTART = "desktop_autostart"
        const val KEY_DEVICE_ORDER = "device_order_ids"
        const val KEY_DEVICE_ORDER_UPDATED_AT = "device_order_updated_at_epoch_ms"
        const val KEY_CLUSTER_MEMBERSHIP_VERSION = "cluster_membership_version"
        const val KEY_MEMBERSHIP_PROTOCOL_SINCE = "membership_protocol_since_epoch_ms"
        const val KEY_LAN_SCAN_BACKOFF = "lan_scan_backoff"
        const val KEY_DESKTOP_LAYOUT = "desktop_layout_mode"
        const val KEY_DESKTOP_NAV_POSITION = "desktop_nav_position"
        const val KEY_DESKTOP_SPLIT_FRACTION = "desktop_split_fraction"
        const val KEY_EXPLORER_SPLIT_FRACTION = "explorer_split_fraction"
        const val KEY_EXPLORER_FAVORITES = "explorer_favorites"
        const val KEY_UPDATE_CHECK_PROMPT_SHOWN = "update_check_prompt_shown"
        const val KEY_POST_PAIR_SETUP_DISMISSED = "post_pair_setup_dismissed"
        const val KEY_BACKUP_CONFIG = "backup_config"
        const val KEY_EXPLORER_SPLIT_ENABLED = "explorer_split_enabled"
        const val KEY_DESKTOP_UI_STYLE = "desktop_ui_style"
        const val KEY_EXPLORER_VIEW_MODE = "explorer_view_mode"
        const val KEY_DEVICES_VIEW_MODE = "devices_view_mode"
        const val KEY_DEVICE_DETAILS_DISPLAY = "device_details_display"
        const val KEY_DEVICE_DETAILS_ALLOW_CELLULAR = "device_details_allow_cellular"
        const val KEY_CELLULAR_ENABLED = "cellular_enabled"
        const val KEY_GOOGLE_DRIVE_RELAY = "google_drive_relay_enabled"
        const val KEY_DRIVE_RELAY_MAX_MB = "drive_relay_max_mb"
        const val KEY_DRIVE_RELAY_OPT_IN_PROMPT_SHOWN = "drive_relay_opt_in_prompt_shown"
        const val KEY_DRIVE_PURGE_AFTER_72H = "drive_purge_after_72h"
        const val KEY_DRIVE_RELAY_ENCRYPTION = "drive_relay_encryption"
        const val KEY_CELLULAR_SEND_PROMPT_ACK = "cellular_send_prompt_ack"
        const val KEY_CELLULAR_RECEIVE_PROMPT_ACK = "cellular_receive_prompt_ack"
        const val KEY_DIAGNOSTICS_PRIVATE_KEY = "diagnostics_private_key_b64"
        const val KEY_KINETIC_NODE_OFFSETS = "kinetic_node_offsets"
        const val KEY_TAILSCALE_SETUP_EXPANDED = "tailscale_setup_expanded"
        const val KEY_TAILSCALE_ENABLED = "tailscale_enabled"
        const val KEY_IROH_ENABLED = "iroh_enabled"
        const val KEY_IROH_ADVANCED_DEBUG = "iroh_advanced_debug"
        const val KEY_IROH_REST_WHEN_IDLE = "iroh_rest_when_idle"
        const val KEY_IROH_RELAY_TOKEN = "iroh_relay_token"
        const val KEY_IROH_SECRET_KEY = "iroh_secret_key_b64"
        const val KEY_FRIENDS_SECRET_KEY = "friends_secret_key_b64"
        const val KEY_DB_FIELD_KEY = "db_field_key_b64"
        const val KEY_CLUSTER_RECORD_KEY = "cluster_record_key_json"
        const val KEY_KICK_HANDLED_VERSION = "kick_handled_version"
        const val KEY_FRIENDS_HIDE_IP = "friends_hide_ip"
        const val KEY_GUEST_LINK_CONSENT = "guest_link_consent"
        const val KEY_FRIENDS_MAX_FILE_MB = "friends_max_file_mb"
        const val KEY_FRIENDS_PINNED = "friends_pinned_keys"
        const val KEY_FRIENDS_INTRO_SEEN = "friends_intro_seen"
        const val KEY_IROH_PEERS = "iroh_peers_json"
        const val KEY_TAILSCALE_AUTH_KEY = "tailscale_auth_key"
        const val KEY_TAILSCALE_AUTH_KEY_FINGERPRINT = "tailscale_auth_key_fingerprint"
        const val KEY_SHOW_ADVANCED_SETTINGS = "show_advanced_settings"
    }
}
