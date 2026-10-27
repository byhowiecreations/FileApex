package com.fileapex.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.ui.adaptive.CompactDevicesTitleBand
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.devices.DevicesActionBar
import com.fileapex.ui.devices.DevicesDialogs
import com.fileapex.ui.devices.PairedDevicesList
import com.fileapex.ui.devices.PendingDelete
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.fileApexHeaderActionTint
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexCleanCurved
import kotlinx.coroutines.flow.distinctUntilChanged

enum class HomeTab {
    Devices,
    Files,
    Settings
}

/** True only on the paired-devices root (not explorer, settings, or local-files navigation). */
fun isMainHomeScreen(selectedTab: HomeTab, hasActiveDetail: Boolean = false): Boolean =
    selectedTab == HomeTab.Devices && !hasActiveDetail

fun devicesNavLabel(onMainHomeScreen: Boolean): String =
    if (onMainHomeScreen) AppI18n.t("devices") else AppI18n.t("home")

enum class DevicesScreenLayoutMode {
    /** Phone / folded: full scaffold with bottom bar. */
    FullScreen,
    /** Tablet / unfolded list pane (~35%). */
    ListPane
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(
    onOpenDevice: (BrowseTarget) -> Unit,
    onOpenLocalFiles: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitApp: () -> Unit,
    onOpenNotes: () -> Unit = {},
    onOpenTransferQueue: () -> Unit = {},
    embeddedInCompactShell: Boolean = false,
    viewModel: DevicesViewModel = viewModel { DevicesViewModel() },
    layoutMode: DevicesScreenLayoutMode = DevicesScreenLayoutMode.FullScreen,
    selectedDeviceId: String? = null
) {
    val state by viewModel.uiState.collectAsState()
    val isInitialLoadComplete by viewModel.isInitialLoadComplete.collectAsState()
    val deviceRows by viewModel.deviceRows.collectAsState()
    val viewMode by FileApexServices.settings.devicesViewMode.collectAsState()
    val friendRows = com.fileapex.ui.devices.rememberFriendDeviceRows()
    val friendActions = com.fileapex.ui.devices.rememberFriendDeviceActions { onOpenLocalFiles() }
    val listRows = if (state.deviceOrderEditMode) state.editOrderRows else com.fileapex.ui.devices.rowsWithFriendsLast(deviceRows, friendRows, isInitialLoadComplete)
    val editMode = state.deviceOrderEditMode
    val snackbarHostState = remember { SnackbarHostState() }
    var addMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pinText by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    val isListPane = layoutMode == DevicesScreenLayoutMode.ListPane

    val deviceOrderHeaderActions: @Composable RowScope.() -> Unit = {
        val currentTheme = LocalAppTheme.current
        val isGlass = currentTheme.traits.reorderAccent
        val editTint = if (isGlass) FluxGlassPalette.accent else fileApexHeaderActionTint()
        if (!currentTheme.traits.orbitalHome) {
            if (editMode) {
                TextButton(onClick = viewModel::revertDeviceOrderInEditMode) {
                    Text(stringRes("revert"), color = editTint)
                }
                TextButton(onClick = viewModel::saveDeviceOrderAndExitEditMode) {
                    Text(stringRes("done"), color = Color(0xFF00E676))
                }
            } else if (deviceRows.isNotEmpty() && isFileApexCleanCurved()) {
                Box(
                    modifier = Modifier.size(40.dp).clickable(onClick = viewModel::enterDeviceOrderEditMode),
                    contentAlignment = Alignment.Center
                ) {
                    JadedRaisedTile(tileSize = 28.dp) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringRes("reorder_devices"),
                            tint = fileApexTileTint(),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else if (deviceRows.isNotEmpty()) {
                IconButton(onClick = viewModel::enterDeviceOrderEditMode) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringRes("reorder_devices"),
                        tint = editTint
                    )
                }
            }
        }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.initialListScrollIndex(),
        initialFirstVisibleItemScrollOffset = viewModel.initialListScrollOffset()
    )
    val currentOnOpenDevice by rememberUpdatedState(onOpenDevice)

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.saveListScroll(index, offset)
            }
    }

    DevicesSnackbars(state, snackbarHostState, LocalShowsTransferBanner.current, viewModel::dismissMessages)

    val currentTheme = LocalAppTheme.current
    val isGlassTheme = currentTheme.traits.glassChrome
    Scaffold(
        containerColor = if (isGlassTheme) Color.Transparent else MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val isKineticSphere = currentTheme.traits.orbitalHome
        val isFreestyle = currentTheme.traits.canvasHome
        val isCarousel = currentTheme.traits.dockHome
        val openDevice: (String) -> Unit = { id -> if (!friendActions.open(id)) viewModel.openDeviceOrExplain(id) { currentOnOpenDevice(it) } }
        val renameDevice: (String, String) -> Unit = { deviceId, deviceName ->
            if (!friendActions.rename(deviceId, deviceName)) { renameText = deviceName; viewModel.beginRename(deviceId) }
        }
        val showDetails: (String) -> Unit = { deviceId ->
            if (!friendActions.details(deviceId)) viewModel.requestDeviceDetails(deviceId)
        }
        val sendClipboardTo: (String) -> Unit = { deviceId ->
            if (!friendActions.isFriend(deviceId)) viewModel.sendClipboardToDevice(deviceId)
        }
        val removeDevice: (String, String) -> Unit = { deviceId, deviceName ->
            if (!friendActions.remove(deviceId)) pendingDelete = PendingDelete(deviceId, deviceName)
        }
        val dropFiles: (String, List<String>) -> Unit = { deviceId, paths ->
            if (!friendActions.drop(deviceId, paths)) viewModel.sendDroppedLocalFiles(deviceId, paths)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (embeddedInCompactShell && !isListPane) {
                CompactDevicesTitleBand(
                    actions = deviceOrderHeaderActions,
                    showLayoutView = true,
                    onToggleLayoutView = {
                        if (isFreestyle) {
                            val current = FileApexServices.settings.freestyleLayoutMode.value
                            FileApexServices.settings.setFreestyleLayoutMode(current.next())
                        } else {
                            FileApexServices.settings.setDevicesViewMode(viewMode.toggled())
                        }
                    },
                    showCloseService = true,
                    onCloseService = onExitApp,
                    onOpenNotes = onOpenNotes,
                    onOpenTransferQueue = onOpenTransferQueue
                )
            }
            ClipboardAccessibilityBanner(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (isFreestyle) {
                FreestyleDevicesView(
                    deviceRows = listRows,
                    connectingDeviceId = state.connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    isEditMode = editMode,
                    onOpenDevice = openDevice,
                    onRenameDevice = renameDevice,
                    onDeviceDetails = showDetails,
                    onSendClipboardDevice = sendClipboardTo,
                    onRemoveDevice = removeDevice,
                    onFilesDropped = dropFiles,
                    onSaveDeviceCardPosition = viewModel::saveDeviceCardPosition,
                    onSaveDeviceTilePosition = viewModel::saveDeviceTilePosition,
                    onSaveDeviceCardMenuOrder = viewModel::saveDeviceCardMenuOrder,
                    onSaveDeviceTileMenuOrder = viewModel::saveDeviceTileMenuOrder,
                    onGenerateQr = onGenerateQr,
                    onJoinDevice = onJoinDevice,
                    onCheckBatteries = { viewModel.checkBatteries() },
                    onSendClipboard = viewModel::sendClipboardNow,
                    onOpenLocalFiles = onOpenLocalFiles,
                    onOpenSettings = onOpenSettings,
                    thisDeviceTarget = viewModel.thisDeviceTarget(),
                    onResolveBrowseTarget = { deviceId, onTargetResolved ->
                        if (!friendActions.open(deviceId)) viewModel.openDeviceOrExplain(deviceId) { target ->
                            onTargetResolved(target)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else if (isCarousel) {
                com.fileapex.ui.carousel.CarouselDevicesView(
                    deviceRows = listRows,
                    details = state.deviceDetails,
                    onOpenDevice = openDevice,
                    onRenameDevice = renameDevice,
                    onDeviceDetails = showDetails,
                    onDismissDetails = viewModel::dismissDeviceDetails,
                    onSendClipboardDevice = sendClipboardTo,
                    onRemoveDevice = removeDevice,
                    onFilesDropped = dropFiles,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else if (isKineticSphere && !editMode) {
                KineticSphereDevicesView(
                    deviceRows = listRows,
                    onOpenDevice = openDevice,
                    onRenameDevice = renameDevice,
                    onDeviceDetails = showDetails,
                    onSendClipboardDevice = sendClipboardTo,
                    onRemoveDevice = removeDevice,
                    onFilesDropped = dropFiles,
                    onGenerateQr = onGenerateQr,
                    onJoinDevice = onJoinDevice,
                    onCheckBatteries = { viewModel.checkBatteries() },
                    onSendClipboard = viewModel::sendClipboardNow,
                    onOpenNotes = onOpenNotes,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                PairedDevicesList(
                    listState = listState,
                    viewMode = viewMode,
                    layoutMode = layoutMode,
                    deviceRows = listRows,
                    isInitialLoadComplete = isInitialLoadComplete,
                    editMode = editMode,
                    connectingDeviceId = if (editMode) null else state.connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    onAddDevice = { addMenuOpen = true },
                    onOpenDevice = openDevice,
                    onRenameDevice = renameDevice,
                    onDeviceDetails = showDetails,
                    onSendClipboardDevice = sendClipboardTo,
                    onRemoveDevice = removeDevice,
                    onFilesDropped = dropFiles,
                    onReorder = viewModel::reorderEditDevice,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            // Always pinned above bottom navigation, not overlapping the list.
            if (!editMode && !isKineticSphere && !isFreestyle && !isCarousel) {
                DevicesActionBar(
                    state = state,
                    addMenuOpen = addMenuOpen,
                    onAddMenuOpenChange = { addMenuOpen = it },
                    onSendClipboard = viewModel::sendClipboardNow,
                    onGenerateQr = onGenerateQr,
                    onJoinDevice = onJoinDevice
                )
            }
        }
    }

    DevicesDialogs(
        state = state,
        viewModel = viewModel,
        renameText = renameText,
        onRenameTextChange = { renameText = it },
        pinText = pinText,
        onPinTextChange = { pinText = it },
        pendingDelete = pendingDelete,
        onPendingDeleteChange = { pendingDelete = it },
        confirmExit = confirmExit,
        onConfirmExitChange = { confirmExit = it },
        onExitApp = onExitApp
    )
}
