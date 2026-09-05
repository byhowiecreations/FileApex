package com.fileapex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.fileapex.ui.theme.isFileApexCustomGlassTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import com.fileapex.domain.transfer.TransferActivityGuard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.domain.diagnostics.DeviceDiagnosticsFormatter
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.di.FileApexServices
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DeviceDetailsState
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes

import com.fileapex.ui.adaptive.FluxGlassHeader
import com.fileapex.ui.adaptive.CompactDevicesTitleBand

import com.fileapex.ui.adaptive.CompactTealStrip
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.adaptive.FileApexWidthSizeClass
import com.fileapex.ui.adaptive.widthSizeClassFor
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FileApexTealDark
import com.fileapex.ui.theme.LocalFileApexUiStyle
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeTopEdge
import com.fileapex.ui.theme.fileApexNavigationBarItemColors
import com.fileapex.ui.theme.fileApexNavSelectedBackgroundColor
import com.fileapex.ui.theme.fileApexNavSelectedIconColor
import com.fileapex.ui.theme.fileApexNavSelectedTextColor
import com.fileapex.ui.theme.fileApexNavUnselectedIconColor
import com.fileapex.ui.theme.fileApexNavUnselectedTextColor
import kotlinx.coroutines.flow.distinctUntilChanged

/** Approximate device card row height used for desktop window sizing. */
val DeviceCardSlotHeight = 96.dp

/** Empty space under the last device card (~2 card rows), inside the list section. */
val DeviceListToAddGap = DeviceCardSlotHeight * 2

private data class DeviceGridLayoutSpec(
    val columnCount: Int,
    val cellHeight: Dp,
    val iconSize: Dp,
    val contentPadding: Dp,
    val cellSpacing: Dp,
    val innerPadding: Dp,
    val menuSize: Dp,
    val compactTypography: Boolean
)

private fun resolveDeviceGridLayout(
    maxWidth: Dp,
    layoutMode: DevicesScreenLayoutMode
): DeviceGridLayoutSpec {
    val widthClass = widthSizeClassFor(maxWidth)
    val isPhoneGrid =
        layoutMode == DevicesScreenLayoutMode.FullScreen &&
            widthClass == FileApexWidthSizeClass.Compact

    if (isPhoneGrid) {
        return DeviceGridLayoutSpec(
            columnCount = 2,
            cellHeight = 118.dp,
            iconSize = 32.dp,
            contentPadding = 16.dp,
            cellSpacing = 10.dp,
            innerPadding = 10.dp,
            menuSize = 28.dp,
            compactTypography = false
        )
    }

    val contentPadding = if (layoutMode == DevicesScreenLayoutMode.ListPane) 12.dp else 16.dp
    val horizontalInset = contentPadding * 2
    val availableWidth = (maxWidth - horizontalInset).coerceAtLeast(112.dp)
    val minCellWidth = when (layoutMode) {
        DevicesScreenLayoutMode.ListPane -> 112.dp
        DevicesScreenLayoutMode.FullScreen -> 140.dp
    }
    val columnCount = (availableWidth / minCellWidth)
        .toInt()
        .coerceIn(1, if (layoutMode == DevicesScreenLayoutMode.ListPane) 3 else 6)

    return if (layoutMode == DevicesScreenLayoutMode.ListPane) {
        DeviceGridLayoutSpec(
            columnCount = columnCount,
            cellHeight = 92.dp,
            iconSize = 22.dp,
            contentPadding = contentPadding,
            cellSpacing = 8.dp,
            innerPadding = 6.dp,
            menuSize = 20.dp,
            compactTypography = true
        )
    } else {
        DeviceGridLayoutSpec(
            columnCount = columnCount.coerceAtLeast(2),
            cellHeight = 104.dp,
            iconSize = 28.dp,
            contentPadding = contentPadding,
            cellSpacing = 10.dp,
            innerPadding = 8.dp,
            menuSize = 24.dp,
            compactTypography = false
        )
    }
}

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

private data class PendingDelete(
    val deviceId: String,
    val deviceName: String
)

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
    val deviceRows by viewModel.deviceRows.collectAsState()
    val viewMode by FileApexServices.settings.devicesViewMode.collectAsState()
    val listRows = if (state.deviceOrderEditMode) state.editOrderRows else deviceRows
    val editMode = state.deviceOrderEditMode
    val snackbarHostState = remember { SnackbarHostState() }
    var addMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pinText by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    val isListPane = layoutMode == DevicesScreenLayoutMode.ListPane

    val deviceOrderHeaderActions: @Composable RowScope.() -> Unit = {
        if (LocalAppTheme.current != AppTheme.KINETIC_SPHERE) {
            if (editMode) {
                TextButton(onClick = viewModel::revertDeviceOrderInEditMode) {
                    Text(stringRes("revert"))
                }
                TextButton(onClick = viewModel::saveDeviceOrderAndExitEditMode) {
                    Text(stringRes("done"))
                }
            } else if (deviceRows.isNotEmpty()) {
                IconButton(onClick = viewModel::enterDeviceOrderEditMode) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringRes("reorder_devices")
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

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val isKineticSphere = LocalAppTheme.current == AppTheme.KINETIC_SPHERE
        val isFreestyle = LocalAppTheme.current == AppTheme.FREESTYLE
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
                    onOpenDevice = { deviceId ->
                        viewModel.openDeviceOrExplain(deviceId) { target ->
                            currentOnOpenDevice(target)
                        }
                    },
                    onRenameDevice = { deviceId, deviceName ->
                        renameText = deviceName
                        viewModel.beginRename(deviceId)
                    },
                    onDeviceDetails = { deviceId ->
                        viewModel.requestDeviceDetails(deviceId)
                    },
                    onSendClipboardDevice = { deviceId ->
                        viewModel.sendClipboardToDevice(deviceId)
                    },
                    onRemoveDevice = { deviceId, deviceName ->
                        pendingDelete = PendingDelete(deviceId, deviceName)
                    },
                    onFilesDropped = { deviceId, paths ->
                        viewModel.sendDroppedLocalFiles(deviceId, paths)
                    },
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
                        viewModel.openDeviceOrExplain(deviceId) { target ->
                            onTargetResolved(target)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else if (isKineticSphere && !editMode) {
                KineticSphereDevicesView(
                    deviceRows = listRows,
                    connectingDeviceId = state.connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    onOpenDevice = { deviceId ->
                        viewModel.openDeviceOrExplain(deviceId) { target ->
                            currentOnOpenDevice(target)
                        }
                    },
                    onRenameDevice = { deviceId, deviceName ->
                        renameText = deviceName
                        viewModel.beginRename(deviceId)
                    },
                    onDeviceDetails = { deviceId ->
                        viewModel.requestDeviceDetails(deviceId)
                    },
                    onSendClipboardDevice = { deviceId ->
                        viewModel.sendClipboardToDevice(deviceId)
                    },
                    onRemoveDevice = { deviceId, deviceName ->
                        pendingDelete = PendingDelete(deviceId, deviceName)
                    },
                    onFilesDropped = { deviceId, paths ->
                        viewModel.sendDroppedLocalFiles(deviceId, paths)
                    },
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
                    editMode = editMode,
                    connectingDeviceId = if (editMode) null else state.connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    onOpenDevice = { deviceId ->
                        viewModel.openDeviceOrExplain(deviceId) { target ->
                            currentOnOpenDevice(target)
                        }
                    },
                    onRenameDevice = { deviceId, deviceName ->
                        renameText = deviceName
                        viewModel.beginRename(deviceId)
                    },
                    onDeviceDetails = { deviceId ->
                        viewModel.requestDeviceDetails(deviceId)
                    },
                    onSendClipboardDevice = { deviceId ->
                        viewModel.sendClipboardToDevice(deviceId)
                    },
                    onRemoveDevice = { deviceId, deviceName ->
                        pendingDelete = PendingDelete(deviceId, deviceName)
                    },
                    onFilesDropped = { deviceId, paths ->
                        viewModel.sendDroppedLocalFiles(deviceId, paths)
                    },
                    onReorder = viewModel::reorderEditDevice,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            // Always pinned above bottom navigation — not overlapping the list.
            if (!editMode && !isKineticSphere && !isFreestyle) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SendClipboardActionChip(onClick = viewModel::sendClipboardNow)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { addMenuOpen = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            shape = if (LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent) {
                                MaterialTheme.shapes.medium
                            } else {
                                RoundedCornerShape(20.dp)
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FileApexTeal,
                                contentColor = Color.White
                            ),
                            elevation = if (LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent) {
                                ButtonDefaults.buttonElevation(
                                    defaultElevation = 0.dp,
                                    pressedElevation = 0.dp,
                                    hoveredElevation = 0.dp
                                )
                            } else {
                                ButtonDefaults.buttonElevation()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringRes("add_new_device"),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                softWrap = true,
                                maxLines = 2
                            )
                        }
                        DropdownMenu(
                            expanded = addMenuOpen,
                            onDismissRequest = { addMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringRes("generate_qr")) },
                                onClick = {
                                    addMenuOpen = false
                                    onGenerateQr()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringRes("join_device")) },
                                onClick = {
                                    addMenuOpen = false
                                    onJoinDevice()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val renameId = state.renameTargetId
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text(stringRes("rename_device")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringRes("name")) }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRename(renameId, renameText) }) {
                    Text(stringRes("save"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) { Text(stringRes("cancel")) }
            }
        )
    }

    state.pendingPinPairing?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinPairing()
            },
            title = { Text(stringRes("enter_device_pin")) },
            text = {
                Column {
                    Text(
                        text = stringRes("enter_pin_pair", pending.deviceName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text(stringRes("pin")) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinPairing(pinText)
                        pinText = ""
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text(stringRes("pair"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinPairing()
                    }
                ) { Text(stringRes("cancel")) }
            }
        )
    }

    state.pendingPinUnlock?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinUnlock()
            },
            title = { Text(stringRes("enter_device_pin")) },
            text = {
                Column {
                    Text(
                        text = stringRes("enter_pin_browse", pending.displayName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text(stringRes("pin")) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinUnlock(pinText)
                        pinText = ""
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text(stringRes("unlock"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinUnlock()
                    }
                ) { Text(stringRes("cancel")) }
            }
        )
    }

    pendingDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringRes("remove_device")) },
            text = {
                Text(stringRes("remove_device_body", device.deviceName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDevice(device.deviceId)
                        pendingDelete = null
                    }
                ) { Text(stringRes("remove")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringRes("cancel")) }
            }
        )
    }

    state.deviceDetails?.let { details ->
        DeviceDetailsDialog(
            details = details,
            onDismiss = viewModel::dismissDeviceDetails
        )
    }

    state.batteryOverlayState?.let { overlay ->
        BatteryTerminalOverlay(
            logLines = overlay.logLines,
            isComplete = overlay.isComplete,
            onDismiss = viewModel::dismissBatteryOverlay
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringRes("exit_fileapex_q")) },
            text = { Text(stringRes("stop_sharing_close")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onExitApp()
                    }
                ) { Text(stringRes("exit")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text(stringRes("cancel")) }
            }
        )
    }
}

@Composable
internal fun SendClipboardActionChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = isFileApexCustomGlassTheme()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (glass) Color(0xDD0D1C22) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (glass) Color(0xFF00E5FF).copy(alpha = 0.50f) else FileApexTeal.copy(alpha = 0.55f)
        ),
        shadowElevation = if (glass) 8.dp else 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.ContentPaste,
                contentDescription = null,
                tint = if (glass) Color(0xFF00E676) else FileApexTeal,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringRes("send_clipboard"),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp
                ),
                color = if (glass) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

/**
 * Diff-keyed list for paired devices. Edit reorder uses a fixed-height [Column] so every
 * device stays composed; browse mode uses a diff-keyed [LazyColumn].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PairedDevicesList(
    listState: LazyListState,
    viewMode: ExplorerViewMode,
    layoutMode: DevicesScreenLayoutMode,
    deviceRows: List<DeviceListRow>,
    editMode: Boolean,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (editMode) {
        PairedDevicesEditReorderList(
            deviceRows = deviceRows,
            selectedDeviceId = selectedDeviceId,
            onReorder = onReorder,
            modifier = modifier
        )
    } else {
        // key(viewMode): LazyColumn → LazyVerticalGrid must remount. Without it, Windows/Skiko
        // can keep the list subtree after devicesViewMode flips to Grid until a parent remount
        // (e.g. Compact ↔ Expanded). Local Files avoids this via a different content tree.
        key(viewMode) {
            when (viewMode) {
                ExplorerViewMode.Grid -> PairedDevicesGridBrowseList(
                    deviceRows = deviceRows,
                    layoutMode = layoutMode,
                    connectingDeviceId = connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    onOpenDevice = onOpenDevice,
                    onRenameDevice = onRenameDevice,
                    onDeviceDetails = onDeviceDetails,
                    onSendClipboardDevice = onSendClipboardDevice,
                    onRemoveDevice = onRemoveDevice,
                    onFilesDropped = onFilesDropped,
                    modifier = modifier
                )
                ExplorerViewMode.List -> PairedDevicesBrowseList(
                    listState = listState,
                    deviceRows = deviceRows,
                    connectingDeviceId = connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    onOpenDevice = onOpenDevice,
                    onRenameDevice = onRenameDevice,
                    onDeviceDetails = onDeviceDetails,
                    onSendClipboardDevice = onSendClipboardDevice,
                    onRemoveDevice = onRemoveDevice,
                    onFilesDropped = onFilesDropped,
                    modifier = modifier
                )
            }
        }
    }
}

@Composable
private fun PairedDevicesEditReorderList(
    deviceRows: List<DeviceListRow>,
    selectedDeviceId: String?,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val itemSpacing = 14.dp
    val listTopPadding = 8.dp
    val horizontalPadding = 20.dp
    val cardHeightPx = with(density) { DeviceCardSlotHeight.toPx() }
    val itemSpacingPx = with(density) { itemSpacing.toPx() }
    val topPaddingPx = with(density) { listTopPadding.toPx() }
    val itemStridePx = cardHeightPx + itemSpacingPx
    val dragState = rememberDeviceOrderDragState()
    val deviceIds = deviceRows.map { it.deviceId }
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        val viewportHeightPx = with(density) { maxHeight.toPx().roundToInt() }
        val listOverflowsViewport = deviceOrderListOverflowsViewport(
            itemCount = deviceIds.size,
            viewportHeightPx = viewportHeightPx,
            cardHeightPx = cardHeightPx,
            itemSpacingPx = itemSpacingPx,
            topPaddingPx = topPaddingPx
        )

        DeviceOrderEdgeAutoScrollEffect(
            dragState = dragState,
            scrollState = scrollState,
            deviceIds = deviceIds,
            itemStridePx = itemStridePx,
            viewportHeightPx = viewportHeightPx,
            listOverflowsViewport = listOverflowsViewport
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(
                    state = scrollState,
                    enabled = !dragState.isDragging && listOverflowsViewport
                )
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = listTopPadding,
                    bottom = DeviceListToAddGap
                ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            if (deviceRows.isEmpty()) {
                Text(
                    text = stringRes("no_paired_devices_hint"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            } else {
                deviceRows.forEachIndexed { index, row ->
                    val isDragging = dragState.draggingDeviceId == row.deviceId
                    val visualOffsetPx = if (dragState.isDragging) {
                        deviceOrderItemVisualOffsetPx(
                            index = index,
                            dragState = dragState,
                            itemCount = deviceRows.size,
                            itemStridePx = itemStridePx
                        )
                    } else {
                        0f
                    }
                    val cardModifier = Modifier
                        .fillMaxWidth()
                        .height(DeviceCardSlotHeight)
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset { deviceOrderDragIntOffset(visualOffsetPx) }
                        .then(
                            if (isDragging) {
                                Modifier.graphicsLayer {
                                    shadowElevation = 8f
                                    alpha = 0.98f
                                }
                            } else {
                                Modifier
                            }
                        )
                    DeviceCard(
                        row = row,
                        selected = selectedDeviceId == row.deviceId,
                        connecting = false,
                        editMode = true,
                        dragging = isDragging,
                        dragHandle = {
                            DeviceOrderDragHandle(
                                modifier = Modifier.padding(start = 4.dp),
                                deviceId = row.deviceId,
                                startIndex = index,
                                itemCount = deviceRows.size,
                                dragState = dragState,
                                itemStridePx = itemStridePx,
                                onReorder = onReorder
                            )
                        },
                        onClick = {},
                        onRename = null,
                        onDeviceDetails = null,
                        onRemove = null,
                        dropDeviceId = null,
                        onFilesDropped = null,
                        modifier = cardModifier
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PairedDevicesBrowseList(
    listState: LazyListState,
    deviceRows: List<DeviceListRow>,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemSpacing = 14.dp
    val listTopPadding = 8.dp

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = listTopPadding,
            bottom = DeviceListToAddGap
        ),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)
    ) {
        if (deviceRows.isEmpty()) {
            item(
                key = "empty",
                contentType = "empty"
            ) {
                Text(
                    text = stringRes("no_paired_devices_hint"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = null
                        )
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        } else {
            itemsIndexed(
                items = deviceRows,
                key = { _, row -> row.deviceId },
                contentType = { _, _ -> "paired-device" }
            ) { _, row ->
                DeviceCard(
                    row = row,
                    selected = selectedDeviceId == row.deviceId,
                    connecting = connectingDeviceId == row.deviceId,
                    editMode = false,
                    dragging = false,
                    dragHandle = null,
                    onClick = { onOpenDevice(row.deviceId) },
                    onRename = { onRenameDevice(row.deviceId, row.deviceName) },
                    onDeviceDetails = { onDeviceDetails(row.deviceId) },
                    onSendClipboard = { onSendClipboardDevice(row.deviceId) },
                    onRemove = { onRemoveDevice(row.deviceId, row.deviceName) },
                    dropDeviceId = row.deviceId,
                    onFilesDropped = onFilesDropped,
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = DeviceOrderItemPlacementSpec
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PairedDevicesGridBrowseList(
    deviceRows: List<DeviceListRow>,
    layoutMode: DevicesScreenLayoutMode,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Skip first frame(s) with unset constraints so Fixed column count is not computed wrong.
        if (!maxWidth.value.isFinite() || maxWidth <= 0.dp) return@BoxWithConstraints
        val grid = resolveDeviceGridLayout(maxWidth = maxWidth, layoutMode = layoutMode)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = grid.contentPadding,
                end = grid.contentPadding,
                top = 8.dp,
                bottom = DeviceListToAddGap
            ),
            horizontalArrangement = Arrangement.spacedBy(grid.cellSpacing),
            verticalArrangement = Arrangement.spacedBy(grid.cellSpacing)
        ) {
            if (deviceRows.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(grid.columnCount) }) {
                    Text(
                        text = stringRes("no_paired_devices_hint"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                itemsIndexed(
                    items = deviceRows,
                    key = { _, row -> row.deviceId }
                ) { _, row ->
                    DeviceGridCell(
                        row = row,
                        grid = grid,
                        selected = selectedDeviceId == row.deviceId,
                        connecting = connectingDeviceId == row.deviceId,
                        onClick = { onOpenDevice(row.deviceId) },
                        onRename = { onRenameDevice(row.deviceId, row.deviceName) },
                        onDeviceDetails = { onDeviceDetails(row.deviceId) },
                        onSendClipboard = { onSendClipboardDevice(row.deviceId) },
                        onRemove = { onRemoveDevice(row.deviceId, row.deviceName) },
                        dropDeviceId = row.deviceId,
                        onFilesDropped = onFilesDropped
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceGridCell(
    row: DeviceListRow,
    grid: DeviceGridLayoutSpec,
    selected: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDeviceDetails: () -> Unit,
    onSendClipboard: () -> Unit,
    onRemove: () -> Unit,
    dropDeviceId: String,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var dropHover by remember { mutableStateOf(false) }
    val highlighted = selected || dropHover
    val fluent = LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent
    val cellShape = if (fluent) RoundedCornerShape(10.dp) else RoundedCornerShape(12.dp)
    val titleStyle = if (grid.compactTypography) {
        MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
    val subtitleStyle = if (grid.compactTypography) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    val containerColor = when {
        dropHover -> FileApexTeal.copy(alpha = 0.22f)
        selected -> FileApexTeal.copy(alpha = 0.12f)
        else -> if (fluent) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        }
    }

    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val pendingItems by FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList())
    val activeSending = pendingItems.firstOrNull { it.isSending }
    val isSendingToThis = liveStats.isActive && (row.deviceId == dropDeviceId || (activeSending != null && (row.deviceId in activeSending.pendingDeviceIds || activeSending.pendingDeviceNames.any { it.equals(row.title, ignoreCase = true) })))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(grid.cellHeight)
            .deviceFileDropTarget(
                enabled = true,
                onHoverChange = { dropHover = it },
                onFilesDropped = { paths -> onFilesDropped(dropDeviceId, paths) }
            )
            .clip(cellShape)
            .clickable(enabled = !connecting, onClick = onClick),
        shape = cellShape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = if (fluent || highlighted) 0.dp else 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                highlighted -> FileApexTeal
                fluent -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                else -> FileApexTeal.copy(alpha = 0.35f)
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(grid.innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = grid.menuSize / 2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                DeviceEntryIcon(
                    row = row,
                    modifier = Modifier.size(grid.iconSize),
                    tint = FileApexTealDark
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.title,
                    style = titleStyle.copy(textAlign = TextAlign.Center),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (isSendingToThis) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { liveStats.progress },
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = FileApexTeal,
                            trackColor = FileApexTeal.copy(alpha = 0.2f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val statText = buildList {
                            if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                            if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                            val pct = (liveStats.progress * 100).toInt().coerceIn(0, 100)
                            if (pct in 1..99) add("$pct%")
                        }.joinToString(" • ")
                        Text(
                            text = if (statText.isNotBlank()) statText else stringRes("sending"),
                            style = subtitleStyle.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else if (connecting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (grid.compactTypography) 10.dp else 12.dp),
                            strokeWidth = 1.5.dp,
                            color = FileApexTeal
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringRes("connecting"),
                            style = subtitleStyle.copy(textAlign = TextAlign.Center),
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else {
                    Text(
                        text = DeviceListRow.localizedSubtitle(row),
                        style = subtitleStyle.copy(textAlign = TextAlign.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(grid.menuSize)
            ) {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(grid.menuSize)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = stringRes("device_options"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (grid.compactTypography) 16.dp else 18.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringRes("rename")) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringRes("device_details")) },
                        onClick = {
                            menuOpen = false
                            onDeviceDetails()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringRes("send_clipboard")) },
                        onClick = {
                            menuOpen = false
                            onSendClipboard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringRes("remove")) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        }
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringRes("selected"),
                    tint = FileApexTeal,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    onExitClick: () -> Unit,
    headerActions: @Composable RowScope.() -> Unit = {},
    onToggleLayoutView: (() -> Unit)? = null,
    onOpenNotes: (() -> Unit)? = null,
    onOpenTransferQueue: (() -> Unit)? = null
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE || currentTheme == AppTheme.FREESTYLE
    val allowLayoutView = onToggleLayoutView != null && currentTheme != AppTheme.KINETIC_SPHERE
    if (isCustomGlass) {
        FluxGlassHeader(
            primaryTitle = "FileApex",
            secondaryTitle = stringRes("paired_devices_title"),
            showLayoutView = allowLayoutView,
            onToggleLayoutView = if (allowLayoutView) onToggleLayoutView else null,
            showCloseService = true,
            onCloseService = onExitClick,
            onOpenNotes = onOpenNotes,
            onOpenTransferQueue = onOpenTransferQueue,
            actions = headerActions
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            CompactTealStrip(showExitPower = true, onExitClick = onExitClick)
            CompactDevicesTitleBand(
                actions = headerActions,
                onOpenNotes = onOpenNotes,
                onOpenTransferQueue = onOpenTransferQueue
            )
        }
    }
}

@Composable
fun FileApexBottomBar(
    selected: HomeTab,
    onMainHomeScreen: Boolean = true,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit
) {
    val devicesLabel = devicesNavLabel(onMainHomeScreen)
    val currentTheme = LocalAppTheme.current
    if (currentTheme == AppTheme.FREESTYLE) return

    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE

    if (isCustomGlass) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp, top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val pillWidth = if (maxWidth < 480.dp) {
                (maxWidth * 0.76f).coerceIn(285.dp, 360.dp)
            } else {
                (maxWidth * 0.333f).coerceIn(320.dp, 440.dp)
            }

            Surface(
                modifier = Modifier
                    .width(pillWidth)
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                color = Color(0xEE0D1C22),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f)),
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CustomGlassNavItem(
                        selected = selected == HomeTab.Devices,
                        onClick = onDevices,
                        icon = Icons.Filled.Devices,
                        label = devicesLabel,
                        modifier = Modifier.weight(1f)
                    )
                    CustomGlassNavItem(
                        selected = selected == HomeTab.Files,
                        onClick = onFiles,
                        icon = Icons.Filled.Folder,
                        label = stringRes("local_files"),
                        modifier = Modifier.weight(1f)
                    )
                    CustomGlassNavItem(
                        selected = selected == HomeTab.Settings,
                        onClick = onSettings,
                        icon = Icons.Filled.Settings,
                        label = stringRes("settings"),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    } else {
        NavigationBar(
            modifier = Modifier
                .fileApexChromeTopEdge()
                .navigationBarsPadding(),
            containerColor = fileApexChromeContainerColor(),
            contentColor = fileApexNavSelectedTextColor(),
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = selected == HomeTab.Devices,
                onClick = onDevices,
                icon = {
                    NavIcon(
                        selected = selected == HomeTab.Devices,
                        imageVector = Icons.Filled.Devices,
                        contentDescription = devicesLabel
                    )
                },
                label = {
                    Text(
                        devicesLabel,
                        color = if (selected == HomeTab.Devices) {
                            fileApexNavSelectedTextColor()
                        } else {
                            fileApexNavUnselectedTextColor()
                        }
                    )
                },
                colors = fileApexNavigationBarItemColors()
            )
            NavigationBarItem(
                selected = selected == HomeTab.Files,
                onClick = onFiles,
                icon = {
                    NavIcon(
                        selected = selected == HomeTab.Files,
                        imageVector = Icons.Filled.Folder,
                        contentDescription = stringRes("local_files")
                    )
                },
                label = {
                    Text(
                        stringRes("local_files"),
                        color = if (selected == HomeTab.Files) {
                            fileApexNavSelectedTextColor()
                        } else {
                            fileApexNavUnselectedTextColor()
                        }
                    )
                },
                colors = fileApexNavigationBarItemColors()
            )
            NavigationBarItem(
                selected = selected == HomeTab.Settings,
                onClick = onSettings,
                icon = {
                    NavIcon(
                        selected = selected == HomeTab.Settings,
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringRes("settings")
                    )
                },
                label = {
                    Text(
                        stringRes("settings"),
                        maxLines = 2,
                        softWrap = true,
                        color = if (selected == HomeTab.Settings) {
                            fileApexNavSelectedTextColor()
                        } else {
                            fileApexNavUnselectedTextColor()
                        }
                    )
                },
                colors = fileApexNavigationBarItemColors()
            )
        }
    }
}

@Composable
private fun CustomGlassNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    val activeBg = Color(0xFF00E676).copy(alpha = 0.22f)
    val activeTint = Color(0xFF00E676)
    val inactiveTint = Color.White.copy(alpha = 0.72f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (selected) activeBg else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) activeTint else inactiveTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 13.sp
            ),
            color = if (selected) Color.White else inactiveTint,
            maxLines = 2,
            softWrap = true,
            textAlign = TextAlign.Center
        )
    }
}


@Composable
private fun NavIcon(
    selected: Boolean,
    imageVector: ImageVector,
    contentDescription: String
) {
    val isGlass = isFileApexCustomGlassTheme()
    val pillShape = if (isGlass) RoundedCornerShape(14.dp) else RoundedCornerShape(12.dp)
    val pillSizeModifier = if (isGlass) Modifier.size(width = 48.dp, height = 26.dp) else Modifier.size(40.dp)

    Box(
        modifier = pillSizeModifier
            .clip(pillShape)
            .background(if (selected) fileApexNavSelectedBackgroundColor() else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (selected) {
                fileApexNavSelectedIconColor()
            } else {
                fileApexNavUnselectedIconColor()
            },
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun DeviceCard(
    row: DeviceListRow,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDeviceDetails: (() -> Unit)? = null,
    onSendClipboard: (() -> Unit)? = null,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    connecting: Boolean = false,
    editMode: Boolean = false,
    dragging: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
    dropDeviceId: String? = null,
    onFilesDropped: ((deviceId: String, paths: List<String>) -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    var dropHover by remember { mutableStateOf(false) }
    val dropTargetId = dropDeviceId
    val dropCallback = onFilesDropped
    val dropModifier = if (dropTargetId != null && dropCallback != null) {
        Modifier.deviceFileDropTarget(
            enabled = true,
            onHoverChange = { dropHover = it },
            onFilesDropped = { paths -> dropCallback(dropTargetId, paths) }
        )
    } else {
        Modifier
    }
    val highlighted = selected || dropHover || dragging
    val fluent = LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent
    val containerColor = when {
        dragging -> FileApexTeal.copy(alpha = 0.14f)
        dropHover -> FileApexTeal.copy(alpha = 0.22f)
        selected -> FileApexTeal.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surface
    }
    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val pendingItems by FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList())
    val activeSending = pendingItems.firstOrNull { it.isSending }
    val isSendingToThis = liveStats.isActive && (row.deviceId == dropDeviceId || (activeSending != null && (row.deviceId in activeSending.pendingDeviceIds || activeSending.pendingDeviceNames.any { it.equals(row.title, ignoreCase = true) })))

    val cardShape = if (fluent) RoundedCornerShape(10.dp) else RoundedCornerShape(16.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(dropModifier)
            .clickable(enabled = !connecting && !editMode, onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = when {
                fluent -> 0.dp
                highlighted -> 0.dp
                else -> 3.dp
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                highlighted -> FileApexTeal
                fluent -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                else -> FileApexTeal.copy(alpha = 0.55f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val iconStyle = LocalThemeIconStyle.current
                DeviceEntryIcon(
                    row = row,
                    modifier = if (iconStyle == ThemeIconStyle.STANDARD) {
                        Modifier.size(24.dp)
                    } else {
                        Modifier.fillMaxSize().padding(2.dp)
                    },
                    tint = FileApexTealDark
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                if (isSendingToThis) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val statText = buildList {
                            add(stringRes("sending"))
                            if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                            if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                            val pct = (liveStats.progress * 100).toInt().coerceIn(0, 100)
                            if (pct in 1..99) add("$pct%")
                        }.joinToString(" • ")
                        Text(
                            text = statText,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { liveStats.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = FileApexTeal,
                            trackColor = FileApexTeal.copy(alpha = 0.2f)
                        )
                    }
                } else if (connecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = FileApexTeal
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringRes("connecting"),
                            style = MaterialTheme.typography.bodySmall,
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else {
                    Text(
                        text = DeviceListRow.localizedSubtitle(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringRes("selected"),
                    tint = FileApexTeal,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(22.dp)
                )
            }
            if (editMode && dragHandle != null) {
                dragHandle()
            } else if (onRename != null || onDeviceDetails != null || onSendClipboard != null || onRemove != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = stringRes("device_options"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text(stringRes("rename")) },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                        }
                        if (onDeviceDetails != null) {
                            DropdownMenuItem(
                                text = { Text(stringRes("device_details")) },
                                onClick = {
                                    menuOpen = false
                                    onDeviceDetails()
                                }
                            )
                        }
                        if (onSendClipboard != null) {
                            DropdownMenuItem(
                                text = { Text(stringRes("send_clipboard")) },
                                onClick = {
                                    menuOpen = false
                                    onSendClipboard()
                                }
                            )
                        }
                        if (onRemove != null) {
                            DropdownMenuItem(
                                text = { Text(stringRes("remove")) },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailsDialog(
    details: DeviceDetailsState,
    onDismiss: () -> Unit
) {
    val displayPreferences by FileApexServices.settings.deviceDetailsDisplayPreferences
        .collectAsState(DeviceDetailsDisplayPreferences.defaults())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("device_details_title", details.deviceName), softWrap = true) },
        text = {
            when {
                details.loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringRes("fetching_snapshot"))
                    }
                }
                details.errorMessage != null -> {
                    Text(
                        text = details.errorMessage,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                details.snapshot != null -> {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DeviceDiagnosticsFormatter.detailRows(
                            snapshot = details.snapshot,
                            preferences = displayPreferences
                        ).forEach { (label, value) ->
                            Column {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringRes("close")) }
        }
    )
}

@Composable
private fun BatteryStatusOverlay(
    loading: Boolean,
    items: List<com.fileapex.presentation.BatteryStatusItem>,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xEE0D1C22),
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
            shadowElevation = 16.dp,
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    BatteryIcon(
                        modifier = Modifier.size(24.dp),
                        tint = Color(0xFF00E676)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringRes("device_battery_levels"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E5FF),
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringRes("polling_batteries"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                } else if (items.isEmpty()) {
                    Text(
                        text = stringRes("no_paired_devices_found"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x33FFFFFF))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.deviceName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val batteryText = when {
                                    !item.online -> stringRes("offline")
                                    item.levelPercent != null -> {
                                        val stateLower = item.chargingState.trim().lowercase()
                                        val isCharging = when {
                                            stateLower.contains("discharging") || stateLower.contains("not charging") -> false
                                            stateLower == "ac" || stateLower == "usb" || stateLower == "wireless" -> true
                                            stateLower.contains("charging") -> true
                                            else -> false
                                        }
                                        if (isCharging) "⚡ ${item.levelPercent}%" else "${item.levelPercent}%"
                                    }
                                    else -> stringRes("unknown")
                                }
                                val textColor = when {
                                    !item.online -> Color.White.copy(alpha = 0.5f)
                                    item.levelPercent != null && item.levelPercent <= 20 -> Color(0xFFFF5252)
                                    else -> Color(0xFF00E676)
                                }
                                Text(
                                    text = batteryText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = textColor
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text(
                        text = stringRes("ok"),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
