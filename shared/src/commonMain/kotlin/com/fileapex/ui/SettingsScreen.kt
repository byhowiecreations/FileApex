package com.fileapex.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.platform.Diagnostics
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.platform.supportsDeviceNameSettingsPage
import com.fileapex.platform.supportsWindowsFluentDesign
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.SettingsUiState
import com.fileapex.presentation.SettingsViewModel
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.ui.dialogs.ClipboardTargetConfigDialog
import com.fileapex.ui.settings.SettingsAdvancedAndAboutRows
import com.fileapex.ui.settings.rememberAdvancedSettingsInUse
import com.fileapex.ui.settings.LocalSettingsExitAction
import com.fileapex.ui.settings.LocalSettingsHomeAction
import com.fileapex.ui.settings.SettingsPageShell
import com.fileapex.ui.settings.clipboardShareTargetsSubtitle
import com.fileapex.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appVersionName: String,
    onBack: () -> Unit,
    /**
     * When false (wide NavigationRail layout), root Settings has no up/back affordance;
     * leave via the rail. Sub-pages still show back to Settings root. Compact stays true.
     */
    showRootBackNavigation: Boolean = true,
    layoutMode: SettingsScreenLayoutMode = SettingsScreenLayoutMode.FullScreen,
    backgroundPersistence: BackgroundPersistenceUiState = BackgroundPersistenceUiState(),
    onRequestBatteryUnrestricted: () -> Unit = {},
    onOpenBackgroundPersistenceSettings: () -> Unit = {},
    onOpenUnusedAppRestrictionsSettings: () -> Unit = {},
    onOpenAppBatteryUsageSettings: () -> Unit = {},
    exactAlarmWarningActive: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {},
    /** Android: gate cellular opt-in behind READ_PHONE_STATE; other platforms invoke [onProceed] immediately. */
    onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit = { it() },
    onOpenTransferQueue: () -> Unit = {},
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
    deniedOnboardingStepIds: Set<String> = emptySet(),
    onGrantOnboardingStep: (String) -> Unit = {},
    onExitApp: (() -> Unit)? = null,
    deepLink: SettingsDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    val updateStatus by viewModel.updateStatusMessage.collectAsState()
    val googleLinkStatus by viewModel.googleLinkStatus.collectAsState()
    val navigation = viewModel { SettingsNavigationState() }
    var page by navigation.page
    LaunchedEffect(deepLink) {
        val target = deepLink ?: return@LaunchedEffect
        page = settingsPageFor(target)
        onDeepLinkConsumed()
    }
    LaunchedEffect(page) {
        if (page == SettingsPage.Themes) FileApexServices.settings.setOtherThemesHintShown(true)
    }

    val currentTheme = LocalAppTheme.current
    val isFreestyleCompact = currentTheme.traits.canvasHome && layoutMode == SettingsScreenLayoutMode.CompactShell
    val allowRootBack = showRootBackNavigation || isFreestyleCompact

    val goUp: () -> Unit = { page = page.parent ?: SettingsPage.Root }
    val leavePage: () -> Unit = {
        if (page == SettingsPage.Root) {
            if (allowRootBack) onBack()
        } else {
            goUp()
        }
    }

    FileApexBackHandler(
        enabled = page != SettingsPage.Root || allowRootBack,
        onBack = leavePage
    )

    val handleGoHome: () -> Unit = {
        page = SettingsPage.Root
        onBack()
    }

    val settingsPages = SettingsPageScope(
        state = state,
        updateStatus = updateStatus,
        googleLinkStatus = googleLinkStatus,
        viewModel = viewModel,
        pageState = navigation.page,
        layoutMode = layoutMode,
        onBack = onBack,
        goUp = goUp,
        appVersionName = appVersionName,
        showRootBackNavigation = showRootBackNavigation,
        backgroundPersistence = backgroundPersistence,
        exactAlarmWarningActive = exactAlarmWarningActive,
        onOpenTransferQueue = onOpenTransferQueue,
        onboardingSteps = onboardingSteps,
        deniedOnboardingStepIds = deniedOnboardingStepIds,
        onGrantOnboardingStep = onGrantOnboardingStep,
        onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
        onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings,
        onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
        onOpenAppBatteryUsageSettings = onOpenAppBatteryUsageSettings,
        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
        onOpenAppDetailsSettings = onOpenAppDetailsSettings,
        onBeforeAllowOverCellularEnabled = onBeforeAllowOverCellularEnabled
    )

    CompositionLocalProvider(
        LocalSettingsHomeAction provides handleGoHome,
        LocalSettingsExitAction provides onExitApp
    ) {
        settingsPages.PagesGeneral()
        settingsPages.PagesClipboard()
        settingsPages.PagesDevice()
    }

    if (state.showClipboardConfigDialog) {
        ClipboardTargetConfigDialog(
            initialMode = state.clipboardShareMode,
            initialTargetIds = state.clipboardTargetDeviceIds,
            peers = state.clipboardConfigPeers,
            onConfirm = { mode, targets ->
                viewModel.confirmClipboardConfig(mode, targets)
            },
            onDismiss = viewModel::dismissClipboardConfigDialog
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRootPage(
    appVersionName: String,
    state: SettingsUiState,
    onBack: () -> Unit,
    showBackNavigation: Boolean,
    layoutMode: SettingsScreenLayoutMode,
    onOpenCheckForUpdates: () -> Unit,
    onOpenPinRequired: () -> Unit,
    onOpenBackgroundPersistence: () -> Unit,
    onOpenAutoLaunchOnReboot: () -> Unit,
    onOpenDeviceName: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenBulletinBoard: () -> Unit,
    onOpenBackupSync: () -> Unit = {},
    onOpenClipboard: () -> Unit,
    onOpenDeviceDetails: () -> Unit,
    onOpenGoogleAccount: () -> Unit,
    onOpenDesktopLayout: () -> Unit,
    onOpenWindowsDesign: () -> Unit,
    onOpenLeaveClusterWipe: () -> Unit = {},
    onOpenTailscale: () -> Unit = {},
    onOpenIroh: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onToggleGeneralGroup: () -> Unit,
    onShowAdvancedChange: (Boolean) -> Unit,
    onToggleSystemPerformanceGroup: () -> Unit,
    onToggleAppearanceBehaviorGroup: () -> Unit,
    onToggleSecurityAccountGroup: () -> Unit,
    onToggleRemoteAccessGroup: () -> Unit,
    onVersionNumberEasterEgg: () -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean,
    onOpenTransferQueue: () -> Unit = {},
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
    onOpenOnboardingPermissions: () -> Unit = {}
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapEpochMs by remember { mutableLongStateOf(0L) }
    val versionTapInteraction = remember { MutableInteractionSource() }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    val inputs = SettingsRootInputs(
        state = state,
        backgroundPersistence = backgroundPersistence,
        exactAlarmWarningActive = exactAlarmWarningActive,
        onOpenCheckForUpdates = onOpenCheckForUpdates,
        onOpenPinRequired = onOpenPinRequired,
        onOpenBackgroundPersistence = onOpenBackgroundPersistence,
        onOpenAutoLaunchOnReboot = onOpenAutoLaunchOnReboot,
        onOpenDeviceName = onOpenDeviceName,
        onOpenLanguage = onOpenLanguage,
        onOpenNotifications = onOpenNotifications,
        onOpenThemes = onOpenThemes,
        onOpenBulletinBoard = onOpenBulletinBoard,
        onOpenBackupSync = onOpenBackupSync,
        onOpenClipboard = onOpenClipboard,
        onOpenDeviceDetails = onOpenDeviceDetails,
        onOpenGoogleAccount = onOpenGoogleAccount,
        onOpenDesktopLayout = onOpenDesktopLayout,
        onOpenWindowsDesign = onOpenWindowsDesign,
        onOpenLeaveClusterWipe = onOpenLeaveClusterWipe,
        onOpenTailscale = onOpenTailscale,
        onOpenIroh = onOpenIroh,
        onOpenGroups = onOpenGroups
    )

    SettingsPageShell(
        title = stringRes("settings"),
        layoutMode = layoutMode,
        onBack = onBack.takeIf { showBackNavigation },
        onOpenTransferQueue = onOpenTransferQueue
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 48.dp)
            ) {
                val ungrantedSteps = onboardingSteps.filter { !it.granted }
                if (ungrantedSteps.isNotEmpty()) {
                    OnboardingPermissionsCard(onOpenOnboardingPermissions)
                }
                val tailscale by TailscaleNodeRuntime.state.collectAsState()
                val menuEntries = settingsMenuEntries(
                    playStoreBuild = com.fileapex.di.FileApexServices.managedByStore,
                    googleCloud = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    tailscale = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    iroh = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    groups = com.fileapex.friends.FriendsHub.available,
                    desktopFileSelection = usesDesktopFileSelection(),
                    deviceNamePage = supportsDeviceNameSettingsPage(),
                    windowsFluent = supportsWindowsFluentDesign(),
                    backupSync = com.fileapex.platform.backupSupported(),
                    autoLaunchOnDesktop = com.fileapex.platform.BootLaunchPreference.usesDesktopAutostart(),
                    showAdvanced = state.showAdvancedSettings,
                    inUseAdvancedIds = rememberAdvancedSettingsInUse(state)
                )
                SettingsMenuSection.entries.forEach { section ->
                    val rows = menuEntries.filter { it.section == section }
                    if (rows.isEmpty()) return@forEach
                    val expanded = when (section) {
                        SettingsMenuSection.General -> state.generalExpanded
                        SettingsMenuSection.SystemPerformance -> state.systemPerformanceExpanded
                        SettingsMenuSection.Appearance -> state.appearanceBehaviorExpanded
                        SettingsMenuSection.Security -> state.securityAccountExpanded
                        SettingsMenuSection.RemoteAccess -> state.remoteAccessExpanded
                    }
                    val onToggle = when (section) {
                        SettingsMenuSection.General -> onToggleGeneralGroup
                        SettingsMenuSection.SystemPerformance -> onToggleSystemPerformanceGroup
                        SettingsMenuSection.Appearance -> onToggleAppearanceBehaviorGroup
                        SettingsMenuSection.Security -> onToggleSecurityAccountGroup
                        SettingsMenuSection.RemoteAccess -> onToggleRemoteAccessGroup
                    }
                    SettingsCategoryGroup(
                        title = stringRes(section.titleKey()),
                        expanded = expanded,
                        onToggle = onToggle
                    ) {
                        rows.forEach { entry ->
                            inputs.MenuRow(entry, tailscale) { showFeedbackDialog = true }
                        }
                    }
                }
                SettingsAdvancedAndAboutRows(state.showAdvancedSettings, onShowAdvancedChange, onOpenAbout)
            }
            val cleanVersion = appVersionName.removePrefix("v").removePrefix("V")
            val isAndroidGitHub = !usesDesktopFileSelection() && !com.fileapex.di.FileApexServices.managedByStore
            val versionDisplayText = if (isAndroidGitHub) {
                "FileApex v$cleanVersion (GitHub)"
            } else if (com.fileapex.di.FileApexServices.isFdroidBuild) {
                "FileApex v$cleanVersion (F-Droid)"
            } else {
                "FileApex v$cleanVersion"
            }
            Text(
                text = versionDisplayText,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .clickable(
                        interactionSource = versionTapInteraction,
                        indication = null,
                        onClick = {
                            if (!TimeUtils.isWithinWindow(
                                    lastVersionTapEpochMs,
                                    VERSION_EASTER_EGG_TAP_WINDOW_MS
                                )
                            ) {
                                versionTapCount = 0
                            }
                            lastVersionTapEpochMs = TimeUtils.now()
                            versionTapCount += 1
                            if (versionTapCount >= VERSION_EASTER_EGG_TAP_COUNT) {
                                versionTapCount = 0
                                onVersionNumberEasterEgg()
                            }
                        }
                    ),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        }

        if (showFeedbackDialog) FeedbackDialog { showFeedbackDialog = false }
    }
}

@Composable
private fun FeedbackDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 440.dp),
        title = {
            Text(
                text = stringRes("report_issue_feedback"),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeedbackOptionCard(
                    icon = Icons.Default.Email,
                    title = stringRes("feedback_option_email"),
                    subtitle = stringRes("feedback_option_email_desc"),
                    onClick = {
                        onDismiss()
                        Diagnostics.sendFeedbackEmail()
                    }
                )
                FeedbackOptionCard(
                    icon = Icons.Default.Feedback,
                    title = stringRes("feedback_option_form"),
                    subtitle = stringRes("feedback_option_form_desc"),
                    onClick = {
                        onDismiss()
                        Diagnostics.openAnonymousFeedbackForm()
                    }
                )
                FeedbackOptionCard(
                    icon = Icons.Default.OpenInBrowser,
                    title = stringRes("feedback_option_github"),
                    subtitle = stringRes("feedback_option_github_desc"),
                    onClick = {
                        onDismiss()
                        Diagnostics.openGitHubIssuesTracker()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringRes("cancel"))
            }
        }
    )
}

@Composable
private fun FeedbackOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun backgroundPersistenceSubtitle(
    watchdogEnabled: Boolean,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean
): String {
    val status = if (watchdogEnabled) AppI18n.t("on") else AppI18n.t("off")
    val warnings = buildList {
        if (backgroundPersistence.backgroundRestricted) add(AppI18n.t("warn_background_restricted"))
        if (backgroundPersistence.batteryOptimizationRestricted) add(AppI18n.t("warn_battery_optimized"))
        if (backgroundPersistence.unusedAppRestrictionsActive) add(AppI18n.t("warn_hibernation"))
        if (!com.fileapex.di.FileApexServices.isPlayStoreBuild && exactAlarmWarningActive) add(AppI18n.t("warn_alarms_off"))
        if (backgroundPersistence.showOemSetup &&
            !backgroundPersistence.persistenceRestricted &&
            !backgroundPersistence.unusedAppRestrictionsActive
        ) {
            add(AppI18n.t("warn_oem_app_launch"))
        }
    }
    return if (warnings.isEmpty()) {
        status
    } else {
        AppI18n.t("status_on_warnings", status, warnings.joinToString(", "))
    }
}

internal fun clipboardSettingsSubtitle(state: SettingsUiState): String {
    if (!state.clipboardSharingEnabled) return AppI18n.t("off")
    val mode = clipboardShareTargetsSubtitle(state)
    val extras = buildList {
        if (currentPlatformLabel() == "Android" && state.clipboardAccessibilityEnabled) add(AppI18n.t("accessibility"))
        if (currentPlatformLabel() == "Android" && !com.fileapex.di.FileApexServices.isPlayStoreBuild && state.clipboardShizukuEnabled) add(AppI18n.t("diag_shizuku_active"))
        if (currentPlatformLabel() != "Android" && state.clipboardAutoSendEnabled) add(AppI18n.t("auto_send"))
        if (state.clipboardViaCellularEnabled && currentPlatformLabel() == "Android") add(AppI18n.t("cellular"))
    }
    return if (extras.isEmpty()) {
        AppI18n.t("clipboard_on_mode", mode)
    } else {
        AppI18n.t("clipboard_on_mode_extras", mode, extras.joinToString(" · "))
    }
}

@Composable
internal fun localizedDesktopLayout(mode: DesktopLayoutMode): String = when (mode) {
    DesktopLayoutMode.Compact -> stringRes("layout_compact")
    DesktopLayoutMode.Expanded -> stringRes("layout_expanded")
}

@Composable
internal fun localizedDesktopUiStyle(style: DesktopUiStyle): String = when (style) {
    DesktopUiStyle.Standard -> stringRes("windows_standard")
    DesktopUiStyle.WindowsFluent -> stringRes("windows_fluent")
}

private const val VERSION_EASTER_EGG_TAP_COUNT = 5

private const val VERSION_EASTER_EGG_TAP_WINDOW_MS = 2_000L

@Composable
private fun CollapsibleCategoryHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFluxGlass = LocalAppTheme.current.traits.fluxSurfaces
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "ChevronRotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 13.sp,
                letterSpacing = 0.8.sp
            ),
            fontWeight = FontWeight.Bold,
            color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) {
                stringRes("collapse_section", title)
            } else {
                stringRes("expand_section", title)
            },
            modifier = Modifier.rotate(rotationAngle),
            tint = if (isFluxGlass) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsCategoryGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CollapsibleCategoryHeader(
            title = title,
            expanded = expanded,
            onToggle = onToggle
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}
