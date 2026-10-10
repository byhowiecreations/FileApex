package com.fileapex.ui

enum class SettingsMenuSection {
    General,
    SystemPerformance,
    Appearance,
    Security;

    fun titleKey(): String = when (this) {
        General -> "settings_general"
        SystemPerformance -> "system_app_performance"
        Appearance -> "appearance_behavior"
        Security -> "security_account"
    }
}

object SettingsMenuIds {
    const val DEVICE_NAME = "device_name"
    const val LANGUAGE = "language"
    const val CHECK_FOR_UPDATES = "check_for_updates"
    const val REPORT_ISSUE = "report_issue"
    const val BACKGROUND_PERSISTENCE = "background_persistence"
    const val AUTO_LAUNCH = "auto_launch"
    const val TAILSCALE = "tailscale"
    const val THEMES = "themes"
    const val BULLETIN_BOARD = "bulletin_board"
    const val BACKUP_SYNC = "backup_sync"
    const val NOTIFICATIONS = "notifications"
    const val CLIPBOARD = "clipboard"
    const val DEVICE_DETAILS = "device_details"
    const val DESKTOP_LAYOUT = "desktop_layout"
    const val WINDOWS_DESIGN = "windows_design"
    const val PIN_REQUIRED = "pin_required"
    const val GOOGLE_ACCOUNT = "google_account"
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
    backupSync: Boolean = false
): List<SettingsMenuEntry> {
    val entries = mutableListOf<SettingsMenuEntry>()
    fun add(section: SettingsMenuSection, id: String) {
        entries += SettingsMenuEntry(section, id)
    }

    if (deviceNamePage) add(SettingsMenuSection.General, SettingsMenuIds.DEVICE_NAME)
    add(SettingsMenuSection.General, SettingsMenuIds.LANGUAGE)
    if (!playStoreBuild) add(SettingsMenuSection.General, SettingsMenuIds.CHECK_FOR_UPDATES)
    if (backupSync) add(SettingsMenuSection.General, SettingsMenuIds.BACKUP_SYNC)
    add(SettingsMenuSection.General, SettingsMenuIds.REPORT_ISSUE)

    add(SettingsMenuSection.SystemPerformance, SettingsMenuIds.BACKGROUND_PERSISTENCE)
    if (!desktopFileSelection) add(SettingsMenuSection.SystemPerformance, SettingsMenuIds.AUTO_LAUNCH)
    add(SettingsMenuSection.SystemPerformance, SettingsMenuIds.TAILSCALE)

    add(SettingsMenuSection.Appearance, SettingsMenuIds.THEMES)
    add(SettingsMenuSection.Appearance, SettingsMenuIds.BULLETIN_BOARD)
    add(SettingsMenuSection.Appearance, SettingsMenuIds.NOTIFICATIONS)
    add(SettingsMenuSection.Appearance, SettingsMenuIds.CLIPBOARD)
    add(SettingsMenuSection.Appearance, SettingsMenuIds.DEVICE_DETAILS)
    if (desktopFileSelection) add(SettingsMenuSection.Appearance, SettingsMenuIds.DESKTOP_LAYOUT)
    if (windowsFluent) add(SettingsMenuSection.Appearance, SettingsMenuIds.WINDOWS_DESIGN)

    add(SettingsMenuSection.Security, SettingsMenuIds.PIN_REQUIRED)
    add(SettingsMenuSection.Security, SettingsMenuIds.GOOGLE_ACCOUNT)
    add(SettingsMenuSection.Security, SettingsMenuIds.LEAVE_CLUSTER)
    return entries
}
