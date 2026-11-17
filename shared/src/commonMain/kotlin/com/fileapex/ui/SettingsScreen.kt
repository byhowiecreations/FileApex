package com.fileapex.ui

import com.fileapex.ui.motion.FileApexMotion
import com.fileapex.ui.motion.LocalReducedMotion
import com.fileapex.ui.motion.MotionKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackgroundPersistenceUiState
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
import com.fileapex.ui.settings.SettingsSearchEngine
import com.fileapex.ui.settings.SettingsSearchOptions
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
        searchQueryState = navigation.searchQuery,
        rootScroll = navigation.rootScroll,
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
    onOpenPage: (SettingsPage) -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onToggleSection: (SettingsMenuSection) -> Unit,
    onShowAdvancedChange: (Boolean) -> Unit,
    onVersionNumberEasterEgg: () -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean,
    onOpenTransferQueue: () -> Unit = {},
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapEpochMs by remember { mutableLongStateOf(0L) }
    val versionTapInteraction = remember { MutableInteractionSource() }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    val inputs = SettingsRootInputs(
        state = state,
        backgroundPersistence = backgroundPersistence,
        exactAlarmWarningActive = exactAlarmWarningActive,
        onOpenPage = onOpenPage
    )

    SettingsPageShell(
        title = stringRes("settings"),
        layoutMode = layoutMode,
        onBack = onBack.takeIf { showBackNavigation },
        onOpenTransferQueue = onOpenTransferQueue,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        searchPlaceholder = stringRes("search_settings_hint")
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(bottom = 48.dp)
            ) {
                val tailscale by TailscaleNodeRuntime.state.collectAsState()
                val searchOptions = SettingsSearchOptions(
                    playStoreBuild = com.fileapex.di.FileApexServices.managedByStore,
                    googleCloud = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    tailscale = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    iroh = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    groups = com.fileapex.friends.FriendsHub.available,
                    shareLink = com.fileapex.drop.GuestDropHub.available,
                    desktopFileSelection = usesDesktopFileSelection(),
                    deviceNamePage = supportsDeviceNameSettingsPage(),
                    windowsFluent = supportsWindowsFluentDesign(),
                    backupSync = com.fileapex.platform.backupSupported(),
                    autoLaunchOnDesktop = com.fileapex.platform.BootLaunchPreference.usesDesktopAutostart(),
                    fullDiskAccess = com.fileapex.platform.fullDiskAccessSupported(),
                    showAdvancedSettings = state.showAdvancedSettings,
                    inUseAdvancedIds = rememberAdvancedSettingsInUse(state),
                    navPosition = com.fileapex.data.settings.DesktopNavPositionPolicy.supportsTheme(LocalAppTheme.current)
                )
                if (searchQuery.isNotBlank()) {
                    val matches = remember(searchQuery, state, searchOptions) {
                        SettingsSearchEngine.search(searchQuery, state, searchOptions)
                    }
                    if (matches.isEmpty()) {
                        Text(
                            text = stringRes("settings_search_empty"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                        )
                    } else {
                        matches.forEach { result ->
                            inputs.SearchResultRow(result, tailscale) { showFeedbackDialog = true }
                        }
                    }
                } else {
                    if (com.fileapex.ui.settings.shouldShowOnboardingSetupCard(onboardingSteps)) {
                        OnboardingPermissionsCard { onOpenPage(SettingsPage.OnboardingPermissions) }
                    }
                    val menuEntries = settingsMenuEntries(
                        playStoreBuild = searchOptions.playStoreBuild,
                        googleCloud = searchOptions.googleCloud,
                        tailscale = searchOptions.tailscale,
                        iroh = searchOptions.iroh,
                        groups = searchOptions.groups,
                        shareLink = searchOptions.shareLink,
                        desktopFileSelection = searchOptions.desktopFileSelection,
                        deviceNamePage = searchOptions.deviceNamePage,
                        windowsFluent = searchOptions.windowsFluent,
                        backupSync = searchOptions.backupSync,
                        autoLaunchOnDesktop = searchOptions.autoLaunchOnDesktop,
                        fullDiskAccess = searchOptions.fullDiskAccess,
                        showAdvanced = searchOptions.showAdvancedSettings,
                        inUseAdvancedIds = searchOptions.inUseAdvancedIds,
                        navPosition = searchOptions.navPosition
                    )
                    SettingsMenuSection.entries.filter { it.collapsible }.forEach { section ->
                        val rows = menuEntries.filter { it.section == section }
                        if (rows.isEmpty()) return@forEach
                        SettingsCategoryGroup(
                            title = stringRes(section.titleKey()),
                            expanded = section.id !in state.collapsedSections,
                            onToggle = { onToggleSection(section) }
                        ) {
                            rows.forEach { entry ->
                                inputs.MenuRow(entry, tailscale) { showFeedbackDialog = true }
                            }
                        }
                    }
                    SettingsAdvancedAndAboutRows(
                        showAdvanced = state.showAdvancedSettings,
                        onShowAdvancedChange = onShowAdvancedChange,
                        onOpenAbout = { onOpenPage(SettingsPage.About) }
                    ) {
                        menuEntries.filter { it.section == SettingsMenuSection.Utility }.forEach { entry ->
                            inputs.MenuRow(entry, tailscale) { showFeedbackDialog = true }
                        }
                    }
                }
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
        animationSpec = FileApexMotion.enter(MotionKind.Move, LocalReducedMotion.current),
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
            enter = FileApexMotion.expandIn(LocalReducedMotion.current),
            exit = FileApexMotion.shrinkOut(LocalReducedMotion.current)
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
