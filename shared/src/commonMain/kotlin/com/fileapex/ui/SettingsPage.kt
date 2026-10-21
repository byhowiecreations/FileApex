package com.fileapex.ui

import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.mutableStateOf

/**
 * Which settings page is open. The activity's view model holds it, not the composable, because the screen is hosted
 * in two places (compact route and wide layout) and rotating or folding moves it from one to the other.
 */
internal class SettingsNavigationState : androidx.lifecycle.ViewModel() {
    val page = mutableStateOf(SettingsPage.Root)
}

internal enum class SettingsPage(val parent: SettingsPage?) {
    Root(null),
    OnboardingPermissions(Root),
    CheckForUpdates(Root),
    PinRequired(Root),
    BackgroundPersistence(Root),
    AutoLaunchOnReboot(Root),
    Notifications(Root),
    BroadcastNotifications(Notifications),
    FileTransferNotifications(Notifications),
    Themes(Root),
    BulletinBoard(Root),
    BulletinBoardStyles(BulletinBoard),
    Clipboard(Root),
    ClipboardAccessibility(Clipboard),
    ClipboardShareTargets(Clipboard),
    ClipboardDiagnostics(Clipboard),
    DeviceDetails(Root),
    GoogleAccount(Root),
    RemoteFileDeletion(BulletinBoard),
    BackupSync(Root),
    DesktopLayout(Root),
    WindowsDesign(Root),
    Language(Root),
    DeviceName(Root),
    Tailscale(Root),
    Iroh(Root),
    LeaveClusterWipe(Root),
    About(Root)
}

/** Settings pages other screens can open directly. */
enum class SettingsDeepLink { Clipboard, Tailscale, Notifications, BroadcastNotifications }

internal fun settingsPageFor(link: SettingsDeepLink): SettingsPage = when (link) {
    SettingsDeepLink.Clipboard -> SettingsPage.Clipboard
    SettingsDeepLink.Tailscale -> SettingsPage.Tailscale
    SettingsDeepLink.Notifications -> SettingsPage.Notifications
    SettingsDeepLink.BroadcastNotifications -> SettingsPage.BroadcastNotifications
}

enum class SettingsScreenLayoutMode {
    /** Phone / compact: teal top bar scaffold. */
    FullScreen,
    /** Wide navigation rail: white pane header matching Devices list pane. */
    ListPane,
    /** Compact primary shell: pane title band on root, section header on sub-pages. */
    CompactShell
}
