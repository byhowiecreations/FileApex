package com.fileapex.ui.adaptive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.ui.shouldShowStatusSnackbar
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.DeviceDiagnosticsFormatter
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.notifications.NotificationBroadcaster
import com.fileapex.domain.notifications.PhoneNotificationState
import com.fileapex.domain.notifications.NotificationCounts
import com.fileapex.domain.notifications.NotificationInbox
import com.fileapex.domain.notifications.NotificationThreads
import com.fileapex.domain.notifications.NotificationThread
import com.fileapex.domain.notifications.NotificationKind
import com.fileapex.domain.notifications.NotificationSyncFeature
import com.fileapex.domain.notifications.NotificationSyncStatus
import com.fileapex.domain.notifications.PhoneSettingsPage
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.domain.presence.isTailscaleEnabled
import com.fileapex.domain.presence.resolvePeerEndpoint
import com.fileapex.util.cancellableCatching
import com.fileapex.i18n.formatLocalizedDateTime
import com.fileapex.i18n.stringRes
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.dnd.EXPLORER_DROP_BOX_DEST
import com.fileapex.ui.dnd.EXPLORER_DROP_BOX_KEY
import com.fileapex.ui.dnd.LocalExplorerDropHighlight
import com.fileapex.ui.dnd.reportDropSpot
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.presentation.ExplorerSplitSession
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.platform.isDesktopHost
import com.fileapex.ui.DesktopLayoutToggle
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.NoteIconKind
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.SettingsDeepLink
import com.fileapex.ui.dialogs.ClipboardTargetConfigDialog
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexFloatingChrome
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.fileApexNavigationBarItemColors
import com.fileapex.ui.theme.fileApexNavSelectedTextColor
import com.fileapex.ui.theme.fileApexNavUnselectedTextColor
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.delay
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

    LaunchedEffect(rows.size, hintShown) {
        if (rows.size > SIMPLE_COMFORTABLE_DEVICE_COUNT && !hintShown) {
            showOtherThemesHint = true
            settings.setOtherThemesHintShown(true)
        }
    }
    LaunchedEffect(state.statusMessage, state.errorMessage) {
        state.statusMessage?.let { message ->
            // The top bar already shows transfer progress.
            if (shouldShowStatusSnackbar(progressShownElsewhere = true)) snackbarHostState.showSnackbar(message)
            devicesViewModel.dismissMessages()
        }
        state.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            devicesViewModel.dismissMessages()
        }
    }
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
    val sendClipboard: () -> Unit = devicesViewModel::sendClipboardNow
    val make = active?.let { makeLabel(it) }

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
            SimpleDestination.Home -> SimpleOverview(
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
                            make = make,
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
                        make = make,
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

@Composable
private fun SimpleDropBox(
    label: String,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onFiles: (List<String>) -> Unit
) {
    var desktopHovered by remember { mutableStateOf(false) }
    val spotHovered = LocalExplorerDropHighlight.current.path == EXPLORER_DROP_BOX_DEST
    val hovered = if (usesDesktopFileSelection()) desktopHovered else spotHovered
    val accent = MaterialTheme.colorScheme.primary
    val dashColor = if (hovered) accent else MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (hovered) accent.copy(alpha = 0.12f) else Color.Transparent)
            .drawBehind {
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(size.height / 2f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                )
            }
            .then(
                if (usesDesktopFileSelection()) {
                    Modifier.deviceFileDropTarget(
                        enabled = enabled,
                        onHoverChange = { desktopHovered = it },
                        onFilesDropped = onFiles
                    )
                } else if (enabled) {
                    // Touch drags are resolved by position in the explorer, like folders in split view.
                    Modifier.reportDropSpot(EXPLORER_DROP_BOX_KEY, EXPLORER_DROP_BOX_DEST)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = if (compact) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.FileUpload,
            contentDescription = if (compact) label else null,
            tint = dashColor,
            modifier = Modifier.size(20.dp)
        )
        if (!compact) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun makeLabel(row: DeviceListRow): String = titleCase(row.deviceMake.trim().ifBlank { row.deviceName.trim() })

private fun titleCase(text: String): String =
    text.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/** Many phones report the maker inside the model name already ("motorola razr fold"). */
private fun makeAndModel(make: String, model: String): String {
    val cleanMake = make.trim()
    val cleanModel = model.trim()
    val joined = when {
        cleanModel.isEmpty() -> cleanMake
        cleanMake.isEmpty() || cleanModel.startsWith(cleanMake, ignoreCase = true) -> cleanModel
        else -> "$cleanMake $cleanModel"
    }
    return titleCase(joined)
}

@Composable
private fun SimpleTopBar(
    onOpenNotes: (() -> Unit)?,
    onHome: () -> Unit,
    onExit: () -> Unit,
    onOpenTransferQueue: () -> Unit,
    explorerCommands: ExplorerHeaderCommands?,
    splitAvailable: Boolean,
    onRefreshExplorer: (() -> Unit)?,
    explorerRefreshing: Boolean
) {
    val explorerViewMode by FileApexServices.settings.explorerViewMode.collectAsState()
    val tint = fileApexHeaderActionTint(onChromeBar = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fileApexChromeBottomEdge()
            .then(
                if (isFileApexCleanCurved()) {
                    Modifier.fileApexFloatingChrome(
                        fileApexChromeContainerColor(),
                        outer = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    )
                } else {
                    Modifier.background(fileApexChromeContainerColor())
                }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FileApex",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = fileApexChromeContentColor()
        )
        IconButton(onClick = onHome, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = stringRes("home"),
                tint = tint
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val liveStats by TransferActivityGuard.statsFlow.collectAsState()
        if (liveStats.isActive) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onOpenTransferQueue)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { liveStats.progress },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                val rateText = buildList {
                    if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                    if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                    add("${(liveStats.progress * 100).toInt().coerceIn(0, 100)}%")
                }.joinToString(" • ")
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = fileApexChromeContentColor()
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        QueuedFilesButton(onClick = onOpenTransferQueue, iconTint = tint)
        if (onOpenNotes != null) {
            val noteIconKind = if (LocalAppTheme.current.traits.glassChrome) NoteIconKind.GREEN else NoteIconKind.WHITE
            NoteHeaderButton(onOpenNotes = onOpenNotes, iconKind = noteIconKind, modifier = Modifier.size(40.dp))
        }
        explorerCommands?.let { commands ->
            TextButton(onClick = commands.onSelect, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringRes("select"), color = fileApexChromeContentColor(), fontWeight = FontWeight.SemiBold)
            }
            commands.onPaste?.let { onPaste ->
                TextButton(onClick = onPaste, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringRes("paste"), color = fileApexChromeContentColor(), fontWeight = FontWeight.SemiBold)
                }
            }
            ExplorerViewModeToggle(
                viewMode = explorerViewMode,
                onToggle = {
                    FileApexServices.settings.setExplorerViewMode(
                        if (splitAvailable) explorerViewMode.cycled() else explorerViewMode.toggled()
                    )
                },
                includeSplit = splitAvailable,
                iconTint = tint,
                modifier = Modifier.size(40.dp)
            )
        }
        if (isDesktopHost()) {
            DesktopLayoutToggle(modifier = Modifier.size(40.dp), iconTint = tint)
        }
        if (onRefreshExplorer != null) {
            IconButton(onClick = onRefreshExplorer, enabled = !explorerRefreshing, modifier = Modifier.size(40.dp)) {
                if (explorerRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = explorerIcon(ExplorerIcon.Refresh),
                        contentDescription = stringRes("refresh"),
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        FileApexPowerButton(onClick = onExit)
    }
}

@Composable
private fun SimpleRail(
    destination: SimpleDestination,
    make: String?,
    popover: SimplePopover?,
    onSelect: (SimpleDestination) -> Unit,
    onSendClipboard: () -> Unit,
    onTogglePopover: (SimplePopover) -> Unit,
    onMenuAnchor: (Rect) -> Unit,
    onHiddenChanged: (Set<SimpleRailEntry>) -> Unit
) {
    // The label gets the width it needs up to a cap; past the cap the short form is used.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium
    fun neededWidth(label: String): Dp = with(density) {
        label.split(' ').maxOf { measurer.measure(it, labelStyle).size.width.toDp() } + 8.dp
    }
    val fullLabel = stringRes("simple_device_management")
    val useShort = neededWidth(fullLabel) > RAIL_LABEL_WIDTH_CAP
    val managementLabel = if (useShort) stringRes("simple_device_management_short") else fullLabel
    val managementWidth = neededWidth(managementLabel).coerceAtLeast(RAIL_LABEL_MIN_WIDTH)
    val localFilesLabel = stringRes("local_files")
    val browseLabel = stringRes("browse")
    val clipboardLabel = stringRes("clipboard")
    val settingsLabel = stringRes("settings")
    val moreLabel = stringRes("simple_more")

    NavigationRail(
        modifier = Modifier
            .widthIn(min = 92.dp)
            .padding(horizontal = 4.dp)
            .fillMaxHeight()
            .then(
                if (isFileApexCleanCurved()) {
                    Modifier.padding(top = 8.dp, bottom = 14.dp).clip(RoundedCornerShape(28.dp))
                } else {
                    Modifier
                }
            ),
        containerColor = fileApexChromeContainerColor(),
        contentColor = fileApexChromeContentColor()
    ) {
        AdaptiveRailColumn(
            slots = listOf(
                RailSlot(SimpleRailEntry.LocalFiles) {
                    RailItem(
                        selected = destination == SimpleDestination.LocalFiles,
                        onClick = { onSelect(SimpleDestination.LocalFiles) },
                        icon = Icons.Filled.Folder,
                        label = localFilesLabel
                    )
                },
                RailSlot(SimpleRailEntry.Browse) {
                    RailItem(
                        selected = destination == SimpleDestination.Browse,
                        onClick = { onSelect(SimpleDestination.Browse) },
                        icon = Icons.Filled.Devices,
                        label = browseLabel,
                        caption = make
                    )
                },
                RailSlot(SimpleRailEntry.Clipboard) {
                    RailItem(
                        selected = false,
                        onClick = onSendClipboard,
                        icon = Icons.Filled.ContentPaste,
                        label = clipboardLabel
                    )
                },
                RailSlot(SimpleRailEntry.DeviceManagement) {
                    Box(modifier = Modifier.onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }) {
                        RailItem(
                            selected = popover == SimplePopover.DeviceManagement || destination == SimpleDestination.Info,
                            onClick = { onTogglePopover(SimplePopover.DeviceManagement) },
                            icon = Icons.Filled.Link,
                            label = managementLabel,
                            labelMaxWidth = managementWidth
                        )
                    }
                },
                RailSlot(SimpleRailEntry.Settings) {
                    RailItem(
                        selected = destination == SimpleDestination.Settings,
                        onClick = { onSelect(SimpleDestination.Settings) },
                        icon = Icons.Filled.Settings,
                        label = settingsLabel
                    )
                }
            ),
            more = {
                Box(modifier = Modifier.onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }) {
                    RailItem(
                        selected = popover == SimplePopover.More,
                        onClick = { onTogglePopover(SimplePopover.More) },
                        icon = Icons.Filled.MoreHoriz,
                        label = moreLabel
                    )
                }
            },
            onHiddenChanged = onHiddenChanged,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
    }
}

private val RAIL_LABEL_WIDTH_CAP = 104.dp
private val RAIL_LABEL_MIN_WIDTH = 72.dp

@Composable
private fun SimpleCompactBottom(
    destination: SimpleDestination,
    make: String?,
    popoverOpen: Boolean,
    onSelect: (SimpleDestination) -> Unit,
    onSendClipboard: () -> Unit,
    onToggleMenu: () -> Unit,
    onMenuAnchor: (Rect) -> Unit
) {
    Column(modifier = Modifier.navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SimplePill(
                icon = Icons.Filled.ContentPaste,
                label = stringRes("send_clipboard"),
                onClick = onSendClipboard,
                modifier = Modifier.weight(1f)
            )
            SimplePill(
                icon = Icons.Filled.Link,
                label = stringRes("simple_device_management_short"),
                selected = popoverOpen || destination == SimpleDestination.Info,
                onClick = onToggleMenu,
                modifier = Modifier.weight(1f).onGloballyPositioned { onMenuAnchor(it.boundsInWindow()) }
            )
        }
        NavigationBar(
            modifier = Modifier
                .then(
                    if (isFileApexCleanCurved()) {
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).height(72.dp).clip(RoundedCornerShape(28.dp))
                    } else {
                        Modifier
                    }
                ),
            containerColor = fileApexChromeContainerColor(),
            contentColor = fileApexNavSelectedTextColor(),
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0.dp)
        ) {
            SimpleBarItem(
                selected = destination == SimpleDestination.LocalFiles,
                onClick = { onSelect(SimpleDestination.LocalFiles) },
                icon = Icons.Filled.Folder,
                label = stringRes("local_files")
            )
            SimpleBarItem(
                selected = destination == SimpleDestination.Browse,
                onClick = { onSelect(SimpleDestination.Browse) },
                icon = Icons.Filled.Devices,
                label = stringRes("browse"),
                caption = make
            )
            SimpleBarItem(
                selected = destination == SimpleDestination.Settings,
                onClick = { onSelect(SimpleDestination.Settings) },
                icon = Icons.Filled.Settings,
                label = stringRes("settings")
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SimpleBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    caption: String? = null
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp)) },
        label = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) fileApexNavSelectedTextColor() else fileApexNavUnselectedTextColor()
                )
                if (!caption.isNullOrBlank()) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = fileApexNavUnselectedTextColor()
                    )
                }
            }
        },
        colors = fileApexNavigationBarItemColors()
    )
}

@Composable
private fun SimplePill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    Surface(
        modifier = modifier.height(44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = fileApexChromeContainerColor(),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = fileApexChromeContentColor())
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = fileApexChromeContentColor()
            )
        }
    }
}

@Composable
private fun SimpleNoDeviceState(
    connecting: Boolean,
    hasPairedDevice: Boolean,
    onAddDevice: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (connecting) {
            CircularProgressIndicator()
        } else {
            Text(stringRes("simple_no_device_title"), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringRes("simple_no_device_body"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (hasPairedDevice) {
                Button(onClick = onRetry) { Text(stringRes("refresh")) }
            } else {
                Button(onClick = onAddDevice) { Text(stringRes("add_new_device")) }
            }
        }
    }
}

@Composable
private fun SimpleOverview(
    devicesViewModel: DevicesViewModel,
    rows: List<DeviceListRow>,
    active: DeviceListRow?,
    onAddDevice: () -> Unit,
    onOpenPairedDevice: (String) -> Unit,
    onFilesDropped: (List<String>) -> Unit
) {
    val state by devicesViewModel.uiState.collectAsState()
    var dropHover by remember { mutableStateOf(false) }
    val connected = active?.takeIf { it.online }
    LaunchedEffect(connected?.deviceId) {
        if (connected != null) devicesViewModel.requestDeviceDetails(connected.deviceId, summaryOnly = true)
    }
    val details = state.deviceDetails?.takeIf { it.deviceId == connected?.deviceId }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .deviceFileDropTarget(onHoverChange = { dropHover = it }, onFilesDropped = onFilesDropped)
            .then(
                if (dropHover) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
    ) {
    val showDropHint = currentPlatformLabel() != "Android"
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = if (showDropHint) 48.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringRes("simple_overview"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (connected == null) {
            Text(
                stringRes("simple_paired_devices_count", rows.size.toString()),
                style = MaterialTheme.typography.titleMedium
            )
            rows.forEach { row ->
                ListItem(
                    modifier = Modifier.clickable { onOpenPairedDevice(row.deviceId) },
                    headlineContent = { Text(row.deviceName) },
                    supportingContent = { Text(row.subtitle) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            if (rows.isEmpty()) {
                Button(onClick = onAddDevice) { Text(stringRes("add_new_device")) }
            }
        } else {
            Text(
                stringRes("simple_connected_with", connected.deviceName),
                style = MaterialTheme.typography.titleLarge
            )
            val snapshot = details?.snapshot
            SimpleInfoLine(
                stringRes("simple_make_model"),
                makeAndModel(
                    make = snapshot?.device?.make?.takeIf { it.isNotBlank() } ?: connected.deviceMake,
                    model = snapshot?.device?.model?.takeIf { it.isNotBlank() } ?: connected.deviceModel
                ).ifBlank { "—" }
            )
            if (details?.loading == true && snapshot == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (snapshot != null) SimpleSnapshotLines(snapshot)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snapshot != null) {
                    Text(
                        stringRes("simple_last_checked", formatLocalizedDateTime(snapshot.collectedAtEpochMs, TimeUtils.DEFAULT_ZONE_ID)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { devicesViewModel.requestDeviceDetails(connected.deviceId, summaryOnly = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringRes("refresh"))
                }
            }
            HorizontalDivider()
            SimpleClipboardRow(connected)
            if (!PeerPlatform.isDesktop(connected.os, connected.platform)) {
                HorizontalDivider()
                SimpleNotificationSection(connected)
            }
        }
    }
    if (showDropHint) {
        Text(
            stringRes(if (maxWidth >= 480.dp) "simple_drop_page_full" else "simple_drop_page_short"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
    }
    }
}

// Android blocks starting an activity from the background, so the other device shows a notification to tap.
@Composable
private fun SimpleClipboardRow(device: DeviceListRow) {
    val scope = rememberCoroutineScope()
    var sharingEnabled by remember(device.deviceId) { mutableStateOf<Boolean?>(null) }
    var promptSent by remember(device.deviceId) { mutableStateOf(false) }
    var note by remember(device.deviceId) { mutableStateOf<String?>(null) }
    val sentText = stringRes("simple_clipboard_prompt_sent")
    val failedText = stringRes("simple_prompt_failed")

    LaunchedEffect(device.deviceId, device.online, promptSent) {
        if (!device.online) return@LaunchedEffect
        val entity = FileApexServices.deviceRepository.getDevice(device.deviceId) ?: return@LaunchedEffect
        val endpoint = resolvePeerEndpoint(entity, isTailscaleEnabled()) ?: return@LaunchedEffect
        do {
            sharingEnabled = cancellableCatching {
                FileApexServices.client.getClipboardStatus(endpoint.host, endpoint.port).sharingEnabled
            }.getOrNull()
            if (sharingEnabled == true || !promptSent) break
            delay(3_000)
        } while (true)
    }

    val off = sharingEnabled == false
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = off) {
                scope.launch {
                    val entity = FileApexServices.deviceRepository.getDevice(device.deviceId)
                    val sent = entity != null &&
                        NotificationBroadcaster.requestPhonePrompt(entity, PhoneSettingsPage.CLIPBOARD)
                    promptSent = sent
                    note = if (sent) sentText else failedText
                }
            },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringRes("clipboard"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                when (sharingEnabled) {
                    true -> stringRes("simple_clipboard_on")
                    false -> stringRes("simple_clipboard_off")
                    null -> "—"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        val hint = note ?: if (off) stringRes("simple_clipboard_tap_enable") else null
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val NOTIFICATION_CHECK_RETRY_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

@Composable
private fun SimpleNotificationSection(device: DeviceListRow) {
    val scope = rememberCoroutineScope()
    val items by NotificationInbox.items.collectAsState()
    val counts = remember(items) { NotificationInbox.counts(items) }
    var status by remember(device.deviceId) { mutableStateOf<NotificationSyncStatus?>(null) }
    var checked by remember(device.deviceId) { mutableStateOf(false) }
    var promptSent by remember(device.deviceId) { mutableStateOf(false) }
    var promptFailed by remember(device.deviceId) { mutableStateOf(false) }
    val ready = status?.let { it.supported && it.enabled && it.accessGranted } == true
    var listKind by remember { mutableStateOf<NotificationKind?>(null) }
    var snapshot by remember { mutableStateOf(emptyList<NotificationThread>()) }
    listKind?.let { kind ->
        NotificationListDialog(
            kind = kind,
            title = stringRes(
                when (kind) {
                    NotificationKind.MESSAGE -> "simple_messages"
                    NotificationKind.EMAIL -> "simple_emails"
                    NotificationKind.OTHER -> "simple_other_notifications"
                }
            ),
            threads = snapshot,
            onClear = { keys -> NotificationBroadcaster.dismissOnDevice(device.deviceId, keys) },
            onReply = { key, text -> NotificationBroadcaster.replyOnDevice(device.deviceId, key, text) },
            onDismiss = { listKind = null }
        )
    }

    // Changes whenever this phone is verified reachable again, e.g. after the Mac wakes from sleep.
    val reachedAt = FileApexServices.presenceMonitor.reachabilityEpochMs.collectAsState().value[device.deviceId]
    val foregroundedAt by com.fileapex.domain.presence.PresenceForegroundRefresh.foregroundedAtMs.collectAsState()
    var unreachable by remember(device.deviceId) { mutableStateOf(false) }
    var needsUpdate by remember(device.deviceId) { mutableStateOf(false) }

    LaunchedEffect(device.deviceId, device.online, promptSent, reachedAt, foregroundedAt) {
        if (!NotificationSyncFeature.ENABLED || !device.online) return@LaunchedEffect
        val entity = FileApexServices.deviceRepository.getDevice(device.deviceId) ?: return@LaunchedEffect
        var failures = 0
        while (true) {
            when (val result = NotificationBroadcaster.checkPhone(entity)) {
                PhoneNotificationState.NeedsUpdate -> {
                    needsUpdate = true
                    unreachable = false
                    checked = true
                    break
                }
                PhoneNotificationState.Unreachable -> {
                    // The first calls after a wake often fail before the network is back: retry, never report "update".
                    if (failures >= NOTIFICATION_CHECK_RETRY_MS.size) {
                        unreachable = true
                        checked = true
                        break
                    }
                    delay(NOTIFICATION_CHECK_RETRY_MS[failures++])
                }
                is PhoneNotificationState.Known -> {
                    status = result.status
                    needsUpdate = false
                    unreachable = false
                    checked = true
                    val done = result.status.supported && result.status.enabled && result.status.accessGranted
                    if (done || !promptSent) break
                    delay(3_000)
                }
            }
        }
    }

    Text(stringRes("notifications"), style = MaterialTheme.typography.titleMedium)
    when {
        !NotificationSyncFeature.ENABLED -> {
            SimpleNotificationCounts(null)
            Text(
                stringRes("simple_notifications_coming_soon"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        !device.online || unreachable -> Text(
            stringRes("simple_nme_unreachable"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        !checked -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        needsUpdate || status?.supported != true -> Text(
            stringRes("simple_nme_update_phone"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ready -> SimpleNotificationCounts(counts) { kind ->
            snapshot = NotificationThreads.threads(items, kind)
            listKind = kind
        }
        else -> {
            Text(stringRes("simple_nme_explainer"), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                scope.launch {
                    val entity = FileApexServices.deviceRepository.getDevice(device.deviceId)
                    val sent = entity != null &&
                        NotificationBroadcaster.requestPhonePrompt(entity, PhoneSettingsPage.BROADCAST_NOTIFICATIONS)
                    promptSent = sent
                    promptFailed = !sent
                }
            }) { Text(stringRes("simple_nme_enable")) }
            if (promptSent) {
                Text(
                    stringRes("simple_nme_prompt_sent"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringRes("simple_nme_waiting"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (promptFailed) {
                Text(
                    stringRes("simple_prompt_failed"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SimpleNotificationCounts(counts: NotificationCounts?, onOpen: ((NotificationKind) -> Unit)? = null) {
    fun line(label: String, count: Int?, kind: NotificationKind) = Triple(label, count, kind)
    listOf(
        line(stringRes("simple_messages"), counts?.messages, NotificationKind.MESSAGE),
        line(stringRes("simple_emails"), counts?.emails, NotificationKind.EMAIL),
        line(stringRes("simple_other_notifications"), counts?.other, NotificationKind.OTHER)
    ).forEach { (label, count, kind) ->
        SimpleInfoLine(
            label,
            count?.toString() ?: "—",
            onClick = if (onOpen != null && (count ?: 0) > 0) ({ onOpen(kind) }) else null
        )
    }
}

@Composable
private fun SimpleSnapshotLines(snapshot: PeerDeviceDiagnostics) {
    val level = DeviceDiagnosticsFormatter.formatPercent(snapshot.battery.levelPercent)
    val charging = snapshot.battery.chargingState.takeIf { it.isNotBlank() }
    SimpleInfoLine(stringRes("simple_battery"), if (charging != null) "$level · $charging" else level)
    SimpleInfoLine(stringRes("simple_storage"), DeviceDiagnosticsFormatter.storageSummary(snapshot.storage))
    val memory = snapshot.memory
    val memoryTotal = memory.totalBytes
    val memoryFree = memory.availableBytes
    if (memoryTotal != null && memoryFree != null && memoryTotal > 0L) {
        val memoryUsed = memory.usedBytes ?: (memoryTotal - memoryFree).coerceAtLeast(0L)
        SimpleInfoLine(
            stringRes("simple_memory"),
            stringRes(
                "simple_memory_free_of",
                DeviceDiagnosticsFormatter.formatBytes(memoryFree),
                DeviceDiagnosticsFormatter.formatBytes(memoryTotal)
            ),
            detail = stringRes("simple_memory_used", DeviceDiagnosticsFormatter.formatBytes(memoryUsed))
        )
    } else {
        SimpleInfoLine(stringRes("simple_memory"), "—")
    }
    SimpleInfoLine(stringRes("simple_uptime"), DeviceDiagnosticsFormatter.formatUptime(snapshot.uptime.uptimeMs))
}

@Composable
private fun SimpleInfoLine(label: String, value: String, detail: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).padding(vertical = 6.dp) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Springs out of the button that opened it and folds back into it. While it is up, a transparent
 * layer over everything else turns any tap, including the button itself, into a close.
 */
@Composable
private fun SimplePopoverHost(
    visible: Boolean,
    anchor: Rect?,
    overlayOrigin: Offset,
    anchoredBelow: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        val popoverWidth = 264.dp
        val gap = with(density) { 8.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val popoverWidthPx = with(density) { popoverWidth.toPx() }
        val estimatedHeightPx = with(density) { 320.dp.toPx() }
        val anchorLocal = anchor?.translate(-overlayOrigin.x, -overlayOrigin.y)
        val placement = if (anchorLocal == null) {
            Modifier.align(Alignment.TopStart)
        } else if (anchoredBelow) {
            val x = (anchorLocal.right + gap).coerceIn(0f, (widthPx - popoverWidthPx).coerceAtLeast(0f))
            val y = anchorLocal.top.coerceIn(0f, (heightPx - estimatedHeightPx).coerceAtLeast(0f))
            Modifier.align(Alignment.TopStart).offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        } else {
            val x = (anchorLocal.right - popoverWidthPx).coerceIn(gap, (widthPx - popoverWidthPx).coerceAtLeast(gap))
            val bottomGap = heightPx - anchorLocal.top + gap
            Modifier.align(Alignment.BottomStart).offset { IntOffset(x.roundToInt(), -bottomGap.roundToInt()) }
        }
        val origin = if (anchoredBelow) TransformOrigin(0f, 0.12f) else TransformOrigin(0.85f, 1f)
        AnimatedVisibility(
            visible = visible && anchorLocal != null,
            modifier = placement.width(popoverWidth),
            enter = scaleIn(
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                transformOrigin = origin
            ) + fadeIn(tween(120)),
            exit = scaleOut(tween(170), transformOrigin = origin) + fadeOut(tween(140))
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp), content = content)
            }
        }
    }
}

@Composable
private fun SimpleManagementItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(icon, contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        headlineContent = {
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SimpleInfo(devicesViewModel: DevicesViewModel, active: DeviceListRow?) {
    val state by devicesViewModel.uiState.collectAsState()
    val preferences by FileApexServices.settings.deviceDetailsDisplayPreferences.collectAsState()
    LaunchedEffect(active?.deviceId) {
        active?.let { devicesViewModel.requestDeviceDetails(it.deviceId) }
    }
    val details = state.deviceDetails?.takeIf { it.deviceId == active?.deviceId }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringRes("info"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when {
            active == null -> Text(stringRes("simple_no_device_title"))
            details == null || details.loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            details.errorMessage != null -> Text(details.errorMessage, color = MaterialTheme.colorScheme.error)
            details.snapshot != null -> DeviceDiagnosticsFormatter.detailRows(
                snapshot = details.snapshot,
                preferences = preferences
            ).forEach { (label, value) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
