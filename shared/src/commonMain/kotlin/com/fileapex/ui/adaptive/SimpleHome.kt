package com.fileapex.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.device.PhoneLocator
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.presentation.ExplorerSplitSession
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.SettingsDeepLink
import com.fileapex.ui.adaptive.simple.SimpleCompactBottom
import com.fileapex.ui.adaptive.simple.SimpleDropBox
import com.fileapex.ui.adaptive.simple.SimpleInfo
import com.fileapex.ui.adaptive.simple.SimpleManagementItem
import com.fileapex.ui.adaptive.simple.SimpleNoDeviceState
import com.fileapex.ui.adaptive.simple.SimpleOverview
import com.fileapex.ui.adaptive.simple.SimplePopoverHost
import com.fileapex.ui.adaptive.simple.SimpleRail
import com.fileapex.ui.adaptive.simple.SimpleTopBar
import com.fileapex.ui.dialogs.ClipboardTargetConfigDialog
import com.fileapex.ui.DevicesSnackbars
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
    var splitAutoSelectedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(explorerViewMode, active?.deviceId, active?.online, destination) {
        val device = active
        if (explorerViewMode != ExplorerViewMode.Split || destination != SimpleDestination.LocalFiles) {
            splitAutoSelectedFor = null
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
                if (next != SimpleDestination.Settings) onSettingsDeepLinkChange(null)
                onDestinationChange(next)
            }
        }
    }
    // Registered before SettingsScreen's own handler, so sub-pages still step back first.
    FileApexBackHandler(enabled = destination == SimpleDestination.Settings) { goTo(SimpleDestination.Home) }
    val sendClipboard: () -> Unit = devicesViewModel::sendClipboardNow

    val onRefreshExplorer = if (explorerVisible) explorerRefresh else null
    val topBar: @Composable () -> Unit = {
        SimpleTopBar(
            onOpenNotes = if (destination == SimpleDestination.Home) onOpenNotes else null,
            onHome = { goTo(SimpleDestination.Home) },
            onExit = onExitApp,
            onOpenTransferQueue = onOpenTransferQueue,
            explorerCommands = if (explorerVisible) explorerCommands else null,
            splitAvailable = destination == SimpleDestination.LocalFiles,
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
                onAddDevice = onGenerateQr,
                onOpenPairedDevice = { id ->
                    settings.setSimpleActiveDeviceId(id)
                },
                onFilesDropped = { paths ->
                    val device = active?.takeIf { it.online }
                    if (device == null) {
                        scope.launch { snackbarHostState.showSnackbar(noDeviceMessage) }
                    } else {
                        devicesViewModel.sendDroppedLocalFiles(device.deviceId, paths)
                    }
                }
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
                if (isWide) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        SimpleRail(
                            destination = destination,
                            popover = popover,
                            onSelect = goTo,
                            onSendClipboard = sendClipboard,
                            onTogglePopover = { kind -> popover = if (popover == kind) null else kind },
                            onMenuAnchor = { menuAnchor = it },
                            onHiddenChanged = { hiddenEntries = it }
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().fadeBottomEdge()) { content() }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().fadeBottomEdge()) { content() }
                    SimpleCompactBottom(
                        destination = destination,
                        popoverOpen = popover == SimplePopover.DeviceManagement,
                        onSelect = goTo,
                        onSendClipboard = sendClipboard,
                        onToggleMenu = { popover = if (popover == SimplePopover.DeviceManagement) null else SimplePopover.DeviceManagement },
                        onMenuAnchor = { menuAnchor = it }
                    )
                }
            }
            SimplePopoverHost(
                visible = popover != null,
                anchor = menuAnchor,
                overlayOrigin = overlayOrigin,
                anchoredBelow = isWide,
                onDismiss = { popover = null }
            ) {
                when (popover ?: lastPopover) {
                    SimplePopover.DeviceManagement -> {
                        if (active != null) SimpleManagementItem(Icons.Filled.Info, stringRes("info")) { popover = null; goTo(SimpleDestination.Info) }
                        if (active != null && active.online && PeerPlatform.isAndroid(active.os, active.platform)) {
                            SimpleManagementItem(Icons.Filled.LocationOn, stringRes("locate_phone")) {
                                popover = null
                                scope.launch {
                                    val ok = PhoneLocator.start(active.deviceId, active.deviceName)
                                    snackbarHostState.showSnackbar(AppI18n.t(if (ok) "locate_phone_sent" else "locate_phone_failed", active.deviceName))
                                }
                            }
                        }
                        if (rows.size >= 2) SimpleManagementItem(Icons.Filled.SwapHoriz, stringRes("simple_switch_device")) { popover = null; showSwitchDialog = true }
                        SimpleManagementItem(Icons.Filled.Add, stringRes("add_new_device")) { popover = null; onGenerateQr() }
                        SimpleManagementItem(Icons.Filled.Link, stringRes("join_device")) { popover = null; onJoinDevice() }
                        if (active != null) SimpleManagementItem(Icons.Filled.Delete, stringRes("remove"), danger = true) { popover = null; confirmRemove = true }
                    }
                    SimplePopover.More -> {
                        if (SimpleRailEntry.LocalFiles in hiddenEntries) SimpleManagementItem(Icons.Filled.Folder, stringRes("local_files")) { popover = null; goTo(SimpleDestination.LocalFiles) }
                        if (SimpleRailEntry.Browse in hiddenEntries) SimpleManagementItem(Icons.Filled.Devices, stringRes("browse")) { popover = null; goTo(SimpleDestination.Browse) }
                        if (SimpleRailEntry.Clipboard in hiddenEntries) SimpleManagementItem(Icons.Filled.ContentPaste, stringRes("send_clipboard")) { popover = null; sendClipboard() }
                        if (SimpleRailEntry.DeviceManagement in hiddenEntries) SimpleManagementItem(Icons.Filled.Link, stringRes("simple_device_management")) { popover = SimplePopover.DeviceManagement }
                        if (SimpleRailEntry.Settings in hiddenEntries) SimpleManagementItem(Icons.Filled.Settings, stringRes("settings")) { popover = null; goTo(SimpleDestination.Settings) }
                    }
                }
            }
        }
    }

    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchDialog = false },
            title = { Text(stringRes("simple_choose_device")) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { row ->
                        ListItem(
                            modifier = Modifier.clickable {
                                settings.setSimpleActiveDeviceId(row.deviceId)
                                showSwitchDialog = false
                            },
                            headlineContent = {
                                Text(
                                    row.deviceName,
                                    fontWeight = if (row.deviceId == active?.deviceId) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            supportingContent = { Text(row.subtitle) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSwitchDialog = false }) { Text(stringRes("close")) } }
        )
    }
    val removeTarget = active
    if (confirmRemove && removeTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringRes("remove_device")) },
            text = { Text(stringRes("remove_device_body", removeTarget.deviceName)) },
            confirmButton = {
                TextButton(onClick = {
                    devicesViewModel.removeDevice(removeTarget.deviceId)
                    confirmRemove = false
                    goTo(SimpleDestination.Home)
                }) { Text(stringRes("remove")) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringRes("cancel")) } }
        )
    }
    if (showOtherThemesHint) {
        AlertDialog(
            onDismissRequest = { showOtherThemesHint = false },
            title = { Text(stringRes("simple_other_themes_title")) },
            text = { Text(stringRes("simple_other_themes_body")) },
            confirmButton = { TextButton(onClick = { showOtherThemesHint = false }) { Text(stringRes("simple_got_it")) } }
        )
    }
    if (state.showClipboardConfigDialog) {
        val currentMode by settings.clipboardShareMode.collectAsState()
        val currentTargets by settings.clipboardTargetDeviceIds.collectAsState()
        ClipboardTargetConfigDialog(
            initialMode = currentMode,
            initialTargetIds = currentTargets,
            peers = state.clipboardConfigPeers,
            onConfirm = { mode, targets -> devicesViewModel.confirmClipboardConfig(mode, targets) },
            onDismiss = devicesViewModel::dismissClipboardConfigDialog
        )
    }
}

/** The panes are see-through; fading their bottom edge keeps them from ending in a hard line on the background. */
private fun Modifier.fadeBottomEdge(height: Dp = 28.dp): Modifier = this

    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)

    .drawWithContent {
        drawContent()
        val fade = height.toPx().coerceAtMost(size.height)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - fade),
            size = androidx.compose.ui.geometry.Size(size.width, fade),
            blendMode = BlendMode.DstIn
        )
    }
