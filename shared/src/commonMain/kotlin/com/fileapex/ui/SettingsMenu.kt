package com.fileapex.ui

enum class SettingsMenuSection(val id: String, val collapsible: Boolean = true) {
    Appearance("appearance"),
    SyncNotifications("sync_notifications"),
    DevicesConnectivity("devices_connectivity"),
    Security("security_privacy"),
    SystemApp("system_app"),
    /** Plain rows under the advanced switch; not a folding section and without a title. */
    Utility("utility", collapsible = false);

    fun titleKey(): String = if (collapsible) id else ""
}

object SettingsMenuIds {
    const val DEVICE_NAME = "device_name"
    const val LANGUAGE = "language"
    const val CHECK_FOR_UPDATES = "check_for_updates"
    const val REPORT_ISSUE = "report_issue"
    const val BACKGROUND_PERSISTENCE = "background_persistence"
    const val AUTO_LAUNCH = "auto_launch"
    const val TAILSCALE = "tailscale"
    const val IROH = "iroh"
    const val DRIVE_RELAY = "drive_relay"
    const val THEMES = "themes"
    const val BULLETIN_BOARD = "bulletin_board"
    const val BACKUP_SYNC = "backup_sync"
    const val NOTIFICATIONS = "notifications"
    const val CLIPBOARD = "clipboard"
    const val GROUPS = "groups"
    const val DEVICE_DETAILS = "device_details"
    const val DESKTOP_LAYOUT = "desktop_layout"
    const val WINDOWS_DESIGN = "windows_design"
    const val PIN_REQUIRED = "pin_required"
    const val GOOGLE_ACCOUNT = "google_account"
    const val SHARE_LINK = "share_link"
    const val FULL_DISK_ACCESS = "full_disk_access"
    const val LEAVE_CLUSTER = "leave_cluster"
}

data class SettingsMenuEntry(
    val section: SettingsMenuSection,
    val id: String
)

fun settingsMenuEntries(
    playStoreBuild: Boolean,
    desktopFileSelection: Boolean,
    deviceNamePage: Boolean,
    windowsFluent: Boolean,
    backupSync: Boolean = false,
    googleCloud: Boolean = true,
    tailscale: Boolean = true,
    iroh: Boolean = false,
    /** Friends and other groups; false where the friends engine is not built in. */
    groups: Boolean = false,
    /** Link sharing is in this build; its page is where the owner accepts or declines the relay. */
    shareLink: Boolean = false,
    /** Windows and Linux can start the app at sign-in; the desktop menu otherwise hides the row. */
    autoLaunchOnDesktop: Boolean = false,
    /** The Mac asks for Full Disk Access in System Settings; no other platform has it. */
    fullDiskAccess: Boolean = false,
    /** When false, advanced rows are left out unless they are in use (see [inUseAdvancedIds]). */
    showAdvanced: Boolean = true,
    inUseAdvancedIds: Set<String> = emptySet(),
    /** True when the active theme renders a movable desktop navigation bar (rail or bottom bar). */
    navPosition: Boolean = true
): List<SettingsMenuEntry> {
    val entries = mutableListOf<SettingsMenuEntry>()
    fun add(section: SettingsMenuSection, id: String) {
        entries += SettingsMenuEntry(section, id)
    }

    add(SettingsMenuSection.Appearance, SettingsMenuIds.THEMES)
    if (desktopFileSelection && navPosition) add(SettingsMenuSection.Appearance, SettingsMenuIds.DESKTOP_LAYOUT)
    if (windowsFluent) add(SettingsMenuSection.Appearance, SettingsMenuIds.WINDOWS_DESIGN)

    if (backupSync) add(SettingsMenuSection.SyncNotifications, SettingsMenuIds.BACKUP_SYNC)
    add(SettingsMenuSection.SyncNotifications, SettingsMenuIds.CLIPBOARD)
    add(SettingsMenuSection.SyncNotifications, SettingsMenuIds.BULLETIN_BOARD)
    if (groups) add(SettingsMenuSection.SyncNotifications, SettingsMenuIds.GROUPS)
    add(SettingsMenuSection.SyncNotifications, SettingsMenuIds.NOTIFICATIONS)

    if (deviceNamePage) add(SettingsMenuSection.DevicesConnectivity, SettingsMenuIds.DEVICE_NAME)
    add(SettingsMenuSection.DevicesConnectivity, SettingsMenuIds.DEVICE_DETAILS)
    if (tailscale) add(SettingsMenuSection.DevicesConnectivity, SettingsMenuIds.TAILSCALE)
    if (iroh) add(SettingsMenuSection.DevicesConnectivity, SettingsMenuIds.IROH)
    if (googleCloud) add(SettingsMenuSection.DevicesConnectivity, SettingsMenuIds.DRIVE_RELAY)

    add(SettingsMenuSection.Security, SettingsMenuIds.PIN_REQUIRED)
    if (shareLink) add(SettingsMenuSection.Security, SettingsMenuIds.SHARE_LINK)
    if (googleCloud) add(SettingsMenuSection.Security, SettingsMenuIds.GOOGLE_ACCOUNT)
    if (fullDiskAccess) add(SettingsMenuSection.Security, SettingsMenuIds.FULL_DISK_ACCESS)
    add(SettingsMenuSection.Security, SettingsMenuIds.LEAVE_CLUSTER)

    add(SettingsMenuSection.SystemApp, SettingsMenuIds.LANGUAGE)
    add(SettingsMenuSection.SystemApp, SettingsMenuIds.BACKGROUND_PERSISTENCE)
    if (!desktopFileSelection || autoLaunchOnDesktop) add(SettingsMenuSection.SystemApp, SettingsMenuIds.AUTO_LAUNCH)
    if (!playStoreBuild) add(SettingsMenuSection.SystemApp, SettingsMenuIds.CHECK_FOR_UPDATES)

    add(SettingsMenuSection.Utility, SettingsMenuIds.REPORT_ISSUE)
    return entries.filter { showAdvanced || it.id !in ADVANCED_IDS || it.id in inUseAdvancedIds }
}

/**
 * Rows kept out of the menu until "Show advanced settings" is on. A row that is in use (Tailscale signed in, Iroh on,
 * a PIN set, Drive Relay or backup running) stays visible so nothing that works disappears.
 */
val ADVANCED_IDS: Set<String> = setOf(
    SettingsMenuIds.TAILSCALE,
    SettingsMenuIds.IROH,
    SettingsMenuIds.DRIVE_RELAY,
    SettingsMenuIds.PIN_REQUIRED,
    SettingsMenuIds.GOOGLE_ACCOUNT,
    SettingsMenuIds.LEAVE_CLUSTER
)
