package com.fileapex.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.di.FileApexServices
import com.fileapex.domain.device.PhoneLocator
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.presentation.ExplorerSplitSession
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.DevicesSnackbars
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.SettingsDeepLink
import com.fileapex.ui.adaptive.simple.SimpleBackNavigationPolicy
import com.fileapex.ui.adaptive.simple.SimpleCompactBottom
import com.fileapex.ui.adaptive.simple.SimpleDropBox
import com.fileapex.ui.adaptive.simple.SimpleHomeDialogs
import com.fileapex.ui.adaptive.simple.SimpleHomePopoverLayer
import com.fileapex.ui.adaptive.simple.SimpleHomeSplitState
import com.fileapex.ui.adaptive.simple.SimpleInfo
import com.fileapex.ui.adaptive.simple.SimpleNavPlacementPolicy
import com.fileapex.ui.adaptive.simple.SimpleNoDeviceState
import com.fileapex.ui.adaptive.simple.SimpleOverview
import com.fileapex.ui.adaptive.simple.SimpleOverviewCardPolicy
import com.fileapex.ui.adaptive.simple.SimpleRail
import com.fileapex.ui.adaptive.simple.SimpleSplitViewPolicy
import com.fileapex.ui.adaptive.simple.SimpleTopBar
import com.fileapex.ui.adaptive.simple.fadeBottomEdge
import kotlinx.coroutines.launch

enum class SimpleDestination { Home, LocalFiles, Browse, Info, Settings }

private const val SIMPLE_COMFORTABLE_DEVICE_COUNT = 3

@Composable
fun SimpleHome(
    destination: SimpleDestination,
    onDestinationChange: (SimpleDestination) -> Unit,
    isWide: Boolean,
    devicesViewModel: DevicesViewModel,
    hasStoragePermission: Boolean,
    onRequestStoragePermission: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit,
    onExitApp: () -> Unit,
    onOpenTransferQueue: () -> Unit,
    onOpenNotes: () -> Unit,
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
    settingsDeepLink: SettingsDeepLink?,
    onSettingsDeepLinkChange: (SettingsDeepLink?) -> Unit,
    settingsContent: @Composable (deepLink: SettingsDeepLink?, onDeepLinkConsumed: () -> Unit) -> Unit
) {
    val settings = FileApexServices.settings
    val state by devicesViewModel.uiState.collectAsState()
    val rows by devicesViewModel.deviceRows.collectAsState()
    val storedActiveId by settings.simpleActiveDeviceId.collectAsState()
    val hintShown by settings.otherThemesHintShown.collectAsState()
    val active = pickActiveDevice(rows, storedActiveId, selfIsPhone = currentPlatformLabel() == "Android")

    // The computer listens to notifications from the phone shown here, and only while this screen is up.
    val activeCompanionId = active?.takeIf {
        currentPlatformLabel() != "Android" && PeerPlatform.isAndroid(it.os, it.platform)
    }?.deviceId.orEmpty()
    LaunchedEffect(activeCompanionId) { NotificationCompanion.setDesired(activeCompanionId) }
    DisposableEffect(Unit) {
        onDispose { NotificationCompanion.setDesired("") }
    }

    var showSwitchDialog by remember { mutableStateOf(false) }
    var popover by remember { mutableStateOf<SimplePopover?>(null) }
    var hiddenEntries by remember { mutableStateOf(emptySet<SimpleRailEntry>()) }
    var lastPopover by remember { mutableStateOf(SimplePopover.DeviceManagement) }
    LaunchedEffect(popover) { popover?.let { lastPopover = it } }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    // The shared view model also feeds other themes' Device Details dialog, so nothing may be left pending.
    DisposableEffect(Unit) {
        onDispose { devicesViewModel.dismissDeviceDetails() }
    }
    var confirmRemove by remember { mutableStateOf(false) }
    var showOtherThemesHint by remember { mutableStateOf(false) }
    var browseTarget by remember { mutableStateOf<BrowseTarget?>(null) }
    var explorerRefresh by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explorerRefreshing by remember { mutableStateOf(false) }
    var explorerCommands by remember { mutableStateOf<ExplorerHeaderCommands?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val splitSession: ExplorerSplitSession = viewModel { ExplorerSplitSession() }
    val secondaryTarget by splitSession.secondaryTarget.collectAsState()
    val explorerViewMode by settings.explorerViewMode.collectAsState()
    val homeSplitState = remember { SimpleHomeSplitState() }
    LaunchedEffect(explorerViewMode) {
        homeSplitState.onViewModeChanged(explorerViewMode)
    }
    DisposableEffect(Unit) {
        onDispose {
            homeSplitState.onNavigateAway(settings.explorerViewMode.value, settings::setExplorerViewMode)
        }
    }
    var splitAutoSelectedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(explorerViewMode, active?.deviceId, active?.online, destination) {
        val device = active
        if (explorerViewMode != ExplorerViewMode.Split || !SimpleSplitViewPolicy.isSplitSupported(destination)) {
            splitAutoSelectedFor = null
            return@LaunchedEffect
        }
        if (destination == SimpleDestination.Browse) {
            if (secondaryTarget != null && splitAutoSelectedFor != "local:${device?.deviceId}") {
                splitAutoSelectedFor = "local:${device?.deviceId}"
                splitSession.selectLocal()
            }
            return@LaunchedEffect
        }
        // The right pane starts on the connected device; picking Local in its menu is left alone.
        if (device != null && device.online && secondaryTarget == null && splitAutoSelectedFor != device.deviceId) {
            splitAutoSelectedFor = device.deviceId
            devicesViewModel.openDeviceOrExplain(device.deviceId) { splitSession.select(it) }
        }
    }
    val scope = rememberCoroutineScope()
    val noDeviceMessage = stringRes("simple_no_device_title")

    val updateOffered by com.fileapex.update.AppUpdateCoordinator.pendingUpdate.collectAsState()
    LaunchedEffect(rows.size, hintShown, updateOffered) {
        if (rows.size > SIMPLE_COMFORTABLE_DEVICE_COUNT && !hintShown && updateOffered == null) {
            showOtherThemesHint = true
            settings.setOtherThemesHintShown(true)
        }
    }
    // The top bar already shows transfer progress.
    DevicesSnackbars(state, snackbarHostState, progressShownElsewhere = true, onShown = devicesViewModel::dismissMessages)
    val explorerVisible = destination == SimpleDestination.LocalFiles || destination == SimpleDestination.Browse
    LaunchedEffect(explorerVisible) {
        if (!explorerVisible) {
            explorerRefresh = null
            explorerRefreshing = false
            explorerCommands = null
        }
    }
    LaunchedEffect(destination, active?.deviceId, active?.online) {
        if (destination != SimpleDestination.Browse) return@LaunchedEffect
        val device = active
        if (device == null || !device.online) {
            browseTarget = null
            return@LaunchedEffect
        }
        if (browseTarget?.deviceId != device.deviceId) {
            browseTarget = null
            devicesViewModel.openDeviceOrExplain(device.deviceId) { browseTarget = it }
        }
    }

    val goTo: (SimpleDestination) -> Unit = { next ->
        when {
            next == SimpleDestination.LocalFiles && !hasStoragePermission -> onRequestStoragePermission()
            else -> {
                if (destination != SimpleDestination.Home && next != destination) {
                    homeSplitState.onNavigateAway(explorerViewMode, settings::setExplorerViewMode)
                }
                if (next != SimpleDestination.Settings) onSettingsDeepLinkChange(null)
                onDestinationChange(next)
            }
        }
    }
    // Registered before SettingsScreen's own handler, so sub-pages still step back first.
    FileApexBackHandler(enabled = SimpleBackNavigationPolicy.shouldInterceptSystemBack(destination, browseTarget != null, popover != null)) { if (popover != null) popover = null else goTo(SimpleDestination.Home) }
    val sendClipboard: () -> Unit = devicesViewModel::sendClipboardNow

    val navPositionPref by settings.desktopNavPosition.collectAsState()
    val navPosition = SimpleNavPlacementPolicy.resolve(
        isDesktop = usesDesktopFileSelection(),
        windowIsWide = isWide,
        desktopPosition = navPositionPref
    )
    val bulletinTracker by FileApexServices.bulletinUnread.collectAsState()
    val bulletinUnread by remember(bulletinTracker) {
        bulletinTracker?.unread ?: kotlinx.coroutines.flow.MutableStateFlow(0)
    }.collectAsState()

    val onRefreshExplorer = if (explorerVisible) explorerRefresh else null
    val topBar: @Composable () -> Unit = {
        SimpleTopBar(
            homeSelected = destination == SimpleDestination.Home,
            onOpenNotes = if (destination == SimpleDestination.Home) onOpenNotes else null,
            onHome = { goTo(SimpleDestination.Home) },
            onExit = onExitApp,
            onOpenTransferQueue = onOpenTransferQueue,
            explorerCommands = if (explorerVisible) explorerCommands else null,
            splitAvailable = SimpleSplitViewPolicy.isSplitSupported(destination),
            onRefreshExplorer = onRefreshExplorer,
            explorerRefreshing = explorerRefreshing
        )
    }

    val content: @Composable () -> Unit = {
        when (destination) {
            SimpleDestination.Home -> Box(modifier = Modifier.fillMaxSize()) {
            SimpleOverview(
                devicesViewModel = devicesViewModel,
                rows = rows,
                active = active,
                onboardingSteps = onboardingSteps,
                onAddDevice = onGenerateQr,
                onJoinDevice = onJoinDevice,
                onBrowseDevice = { goTo(SimpleDestination.Browse) },
                onOpenSplitView = {
                    val splitDest = homeSplitState.launchFromHome(explorerViewMode, settings::setExplorerViewMode)
                    goTo(splitDest)
                },
                onOpenBulletinBoard = onOpenNotes,
                onOpenDeviceDetails = { goTo(SimpleDestination.Info) },
                onLocateDevice = {
                    val target = active?.takeIf { it.online }
                    if (target == null) {
                        scope.launch { snackbarHostState.showSnackbar(noDeviceMessage) }
                    } else {
                        scope.launch {
                            val ok = PhoneLocator.start(target.deviceId, target.deviceName)
                            snackbarHostState.showSnackbar(
                                AppI18n.t(
                                    if (ok) "locate_phone_sent" else "locate_phone_failed",
                                    target.deviceName
                                )
                            )
                        }
                    }
                },
                onOpenBackgroundSetup = {
                    onSettingsDeepLinkChange(SettingsDeepLink.OnboardingPermissions)
                    goTo(SimpleDestination.Settings)
                },
                onFilesDropped = { paths ->
                    val device = active?.takeIf { it.online }
                    if (device == null) {
                        scope.launch { snackbarHostState.showSnackbar(noDeviceMessage) }
                    } else {
                        devicesViewModel.sendDroppedLocalFiles(device.deviceId, paths)
                    }
                },
                showDevicesCard = SimpleOverviewCardPolicy.showDevicesCard(navPosition),
                onDevicesAnchor = { menuAnchor = it },
                onOpenDevices = { popover = SimplePopover.DeviceManagement }
            )
            SimpleLocateCard(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
            }
            SimpleDestination.LocalFiles -> FileExplorerScreen(
                target = devicesViewModel.thisDeviceTarget(),
                onBack = { goTo(SimpleDestination.Home) },
                titleOverride = stringRes("local_files"),
                layoutExpanded = isWide,
                onRegisterRefresh = { refreshing, doRefresh ->
                    explorerRefreshing = refreshing
                    explorerRefresh = doRefresh
                },
                onRegisterHeaderCommands = { explorerCommands = it },
                showTopBar = false,
                onDropBoxFiles = { paths ->
                    val device = active
                    if (device == null) {
                        scope.launch { snackbarHostState.showSnackbar(noDeviceMessage) }
                    } else {
                        devicesViewModel.sendDroppedLocalFiles(device.deviceId, paths)
                    }
                },
                secondaryTarget = secondaryTarget,
                readyDevices = rows.filter { it.online },
                onSelectSecondaryLocal = splitSession::selectLocal,
                onSelectSecondaryDevice = { deviceId ->
                    devicesViewModel.openDeviceOrExplain(deviceId) { splitSession.select(it) }
                },
                filterBarLeading = run {
                    { dropModifier, compact ->
                        SimpleDropBox(
                            label = stringRes("simple_drop_send_to", active?.deviceName.orEmpty()),
                            enabled = active != null,
                            compact = compact,
                            modifier = dropModifier,
                            onFiles = { paths ->
                                val device = active
                                if (device == null) {
                                    scope.launch { snackbarHostState.showSnackbar(noDeviceMessage) }
                                } else {
                                    devicesViewModel.sendDroppedLocalFiles(device.deviceId, paths)
                                }
                            }
                        )
                    }
                }
            )
            SimpleDestination.Browse -> {
                val target = browseTarget
                if (target == null) {
                    SimpleNoDeviceState(
                        connecting = state.connectingDeviceId != null,
                        hasPairedDevice = active != null,
                        onAddDevice = onGenerateQr,
                        onRetry = {
                            val device = active
                            if (device != null) {
                                devicesViewModel.openDeviceOrExplain(device.deviceId) { browseTarget = it }
                            }
                        }
                    )
                } else {
                    FileExplorerScreen(
                        target = target,
                        onBack = { goTo(SimpleDestination.Home) },
                        layoutExpanded = isWide,
                        onRegisterRefresh = { refreshing, doRefresh ->
                            explorerRefreshing = refreshing
                            explorerRefresh = doRefresh
                        },
                        onRegisterHeaderCommands = { explorerCommands = it },
                        showTopBar = false,
                        secondaryTarget = secondaryTarget,
                        readyDevices = rows.filter { it.online },
                        onSelectSecondaryLocal = splitSession::selectLocal,
                        onSelectSecondaryDevice = { deviceId ->
                            devicesViewModel.openDeviceOrExplain(deviceId) { splitSession.select(it) }
                        },
                        onDropBoxFiles = { paths ->
                            devicesViewModel.sendDroppedLocalFiles(LocalIdentity.LOCAL_DEVICE_ID, paths)
                        },
                        filterBarLeading = run {
                            { dropModifier, compact ->
                                SimpleDropBox(
                                    label = stringRes("simple_drop_save_here"),
                                    enabled = true,
                                    compact = compact,
                                    modifier = dropModifier,
                                    onFiles = { paths ->
                                        devicesViewModel.sendDroppedLocalFiles(LocalIdentity.LOCAL_DEVICE_ID, paths)
                                    }
                                )
                            }
                        }
                    )
                }
            }
            SimpleDestination.Info -> SimpleInfo(devicesViewModel = devicesViewModel, active = active)
            SimpleDestination.Settings -> settingsContent(settingsDeepLink) {}
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { overlayOrigin = it.positionInWindow() }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                topBar()
                SimpleHomeNavContainer(
                    navPosition = navPosition,
                    destination = destination,
                    popover = popover,
                    bulletinUnreadCount = bulletinUnread,
                    onSelect = goTo,
                    onOpenNotes = onOpenNotes,
                    onTogglePopover = { kind -> popover = if (popover == kind) null else kind },
                    onMenuAnchor = { menuAnchor = it },
                    onHiddenChanged = { hiddenEntries = it },
                    content = content
                )
            }
            SimpleHomePopoverLayer(
                popover = popover,
                lastPopover = lastPopover,
                menuAnchor = menuAnchor,
                overlayOrigin = overlayOrigin,
                anchoredBelow = navPosition != DesktopNavPosition.Bottom,
                rows = rows,
                active = active,
                hiddenEntries = hiddenEntries,
                onChangePopover = { popover = it },
                onNavigate = goTo,
                onLocateActive = { target ->
                    scope.launch {
                        val ok = PhoneLocator.start(target.deviceId, target.deviceName)
                        snackbarHostState.showSnackbar(AppI18n.t(if (ok) "locate_phone_sent" else "locate_phone_failed", target.deviceName))
                    }
                },
                onSwitchDevice = { showSwitchDialog = true },
                onGenerateQr = onGenerateQr,
                onJoinDevice = onJoinDevice,
                onConfirmRemove = { confirmRemove = true },
                onSendClipboard = sendClipboard,
                onOpenNotes = onOpenNotes
            )
        }
    }

    SimpleHomeDialogs(
        devicesViewModel = devicesViewModel,
        rows = rows,
        active = active,
        showSwitchDialog = showSwitchDialog,
        onDismissSwitch = { showSwitchDialog = false },
        confirmRemove = confirmRemove,
        onDismissRemove = { confirmRemove = false },
        onAfterRemove = { goTo(SimpleDestination.Home) },
        showOtherThemesHint = showOtherThemesHint,
        onDismissOtherThemesHint = { showOtherThemesHint = false }
    )
}

@Composable
private fun ColumnScope.SimpleHomeNavContainer(
    navPosition: DesktopNavPosition,
    destination: SimpleDestination,
    popover: SimplePopover?,
    bulletinUnreadCount: Int,
    onSelect: (SimpleDestination) -> Unit,
    onOpenNotes: () -> Unit,
    onTogglePopover: (SimplePopover) -> Unit,
    onMenuAnchor: (Rect) -> Unit,
    onHiddenChanged: (Set<SimpleRailEntry>) -> Unit,
    content: @Composable () -> Unit
) {
    when (navPosition) {
        DesktopNavPosition.Left -> Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SimpleRail(
                destination = destination,
                popover = popover,
                bulletinUnreadCount = bulletinUnreadCount,
                onSelect = onSelect,
                onOpenNotes = onOpenNotes,
                onTogglePopover = onTogglePopover,
                onMenuAnchor = onMenuAnchor,
                onHiddenChanged = onHiddenChanged
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight().fadeBottomEdge()) { content() }
        }
        DesktopNavPosition.Right -> Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().fadeBottomEdge()) { content() }
            SimpleRail(
                destination = destination,
                popover = popover,
                bulletinUnreadCount = bulletinUnreadCount,
                onSelect = onSelect,
                onOpenNotes = onOpenNotes,
                onTogglePopover = onTogglePopover,
                onMenuAnchor = onMenuAnchor,
                onHiddenChanged = onHiddenChanged
            )
        }
        DesktopNavPosition.Bottom -> {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().fadeBottomEdge()) { content() }
            SimpleCompactBottom(
                destination = destination,
                bulletinUnreadCount = bulletinUnreadCount,
                onSelect = onSelect,
                onOpenNotes = onOpenNotes
            )
        }
    }
}
