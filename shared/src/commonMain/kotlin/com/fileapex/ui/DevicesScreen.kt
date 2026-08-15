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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
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

/** Responsive paired-devices grid metrics — SSOT across platforms. */
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
    if (onMainHomeScreen) "Devices" else "Home"

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
    onScanQr: () -> Unit,
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
    var showManualCodeDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pinText by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    val isListPane = layoutMode == DevicesScreenLayoutMode.ListPane

    val deviceOrderHeaderActions: @Composable RowScope.() -> Unit = {
        if (LocalAppTheme.current != AppTheme.KINETIC_SPHERE) {
            if (editMode) {
                TextButton(onClick = viewModel::revertDeviceOrderInEditMode) {
                    Text("Revert")
                }
                TextButton(onClick = viewModel::saveDeviceOrderAndExitEditMode) {
                    Text("Done")
                }
            } else if (deviceRows.isNotEmpty()) {
                IconButton(onClick = viewModel::enterDeviceOrderEditMode) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Reorder devices"
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (embeddedInCompactShell && !isListPane) {
                CompactDevicesTitleBand(
                    actions = deviceOrderHeaderActions,
                    showLayoutView = true,
                    onToggleLayoutView = { FileApexServices.settings.setDevicesViewMode(viewMode.toggled()) },
                    showCloseService = true,
                    onCloseService = onExitApp,
                    onOpenNotes = onOpenNotes,
                    onOpenTransferQueue = onOpenTransferQueue
                )
            }
            if (isKineticSphere && !editMode) {
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
                    onScanQr = onScanQr,
                    onManualEntry = { showManualCodeDialog = true },
                    onCheckBatteries = { viewModel.checkBatteries() },
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
            if (!editMode && !isKineticSphere) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 10.dp)
            ) {
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
                        text = "Add New Device",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Generate QR Code") },
                        onClick = {
                            addMenuOpen = false
                            onGenerateQr()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Scan QR Code") },
                        onClick = {
                            addMenuOpen = false
                            onScanQr()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Manually Enter Code") },
                        onClick = {
                            addMenuOpen = false
                            showManualCodeDialog = true
                        }
                    )
                }
            }
            }
        }
    }

    if (showManualCodeDialog) {
        ManualPairingCodeDialog(
            onDismiss = { showManualCodeDialog = false },
            onConfirm = { code ->
                viewModel.pairFromManualInput(code)
            }
        )
    }

    val renameId = state.renameTargetId
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRename(renameId, renameText) }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) { Text("Cancel") }
            }
        )
    }

    state.pendingPinPairing?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinPairing()
            },
            title = { Text("Enter device PIN") },
            text = {
                Column {
                    Text(
                        text = "Enter the PIN for ${pending.deviceName} to finish pairing.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text("PIN") }
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
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinPairing()
                    }
                ) { Text("Cancel") }
            }
        )
    }

    state.pendingPinUnlock?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinUnlock()
            },
            title = { Text("Enter device PIN") },
            text = {
                Column {
                    Text(
                        text = "Enter the PIN for ${pending.displayName} to browse files.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text("PIN") }
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
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinUnlock()
                    }
                ) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove device?") },
            text = {
                Text(
                    "Remove ${device.deviceName} from paired devices? " +
                        "It will stay gone until you pair again."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDevice(device.deviceId)
                        pendingDelete = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
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
        BatteryStatusOverlay(
            loading = overlay.loading,
            items = overlay.items,
            onDismiss = viewModel::dismissBatteryOverlay
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Exit FileApex?") },
            text = { Text("Stop sharing and close the app.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onExitApp()
                    }
                ) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
            }
        )
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
                    text = "No paired devices yet. Tap Add New Device to generate or scan a QR code.",
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
                    text = "No paired devices yet. Tap Add New Device to generate or scan a QR code.",
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
                        text = "No paired devices yet. Tap Add New Device to generate or scan a QR code.",
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
                if (connecting) {
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
                            text = "Connecting…",
                            style = subtitleStyle.copy(textAlign = TextAlign.Center),
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else {
                    Text(
                        text = row.subtitle,
                        style = subtitleStyle.copy(textAlign = TextAlign.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
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
                        contentDescription = "Device options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (grid.compactTypography) 16.dp else 18.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Device Details") },
                        onClick = {
                            menuOpen = false
                            onDeviceDetails()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Send Clipboard") },
                        onClick = {
                            menuOpen = false
                            onSendClipboard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
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
                    contentDescription = "Selected",
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
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE
    val allowLayoutView = onToggleLayoutView != null && currentTheme != AppTheme.KINETIC_SPHERE
    if (isCustomGlass) {
        FluxGlassHeader(
            primaryTitle = "FileApex",
            secondaryTitle = "Paired Devices",
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
                        label = "Local Files",
                        modifier = Modifier.weight(1f)
                    )
                    CustomGlassNavItem(
                        selected = selected == HomeTab.Settings,
                        onClick = onSettings,
                        icon = Icons.Filled.Settings,
                        label = "Settings",
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
                        contentDescription = "Local Files"
                    )
                },
                label = {
                    Text(
                        "Local Files",
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
                        contentDescription = "Settings"
                    )
                },
                label = {
                    Text(
                        "Settings",
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
        // Icon inside compact green accent pill indicator
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
        // Text label OUTSIDE the green pill, cleanly aligned under icon
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 13.sp
            ),
            color = if (selected) Color.White else inactiveTint,
            maxLines = 1,
            softWrap = false,
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
                DeviceEntryIcon(
                    row = row,
                    modifier = Modifier.size(24.dp),
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
                if (connecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = FileApexTeal
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Connecting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = FileApexTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = row.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
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
                            contentDescription = "Device options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                        }
                        if (onDeviceDetails != null) {
                            DropdownMenuItem(
                                text = { Text("Device Details") },
                                onClick = {
                                    menuOpen = false
                                    onDeviceDetails()
                                }
                            )
                        }
                        if (onSendClipboard != null) {
                            DropdownMenuItem(
                                text = { Text("Send Clipboard") },
                                onClick = {
                                    menuOpen = false
                                    onSendClipboard()
                                }
                            )
                        }
                        if (onRemove != null) {
                            DropdownMenuItem(
                                text = { Text("Remove") },
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
        title = { Text("Device Details — ${details.deviceName}") },
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
                        Text("Fetching snapshot…")
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
            TextButton(onClick = onDismiss) { Text("Close") }
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
                        text = "Device Battery Levels",
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
                            text = "Polling battery levels...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                } else if (items.isEmpty()) {
                    Text(
                        text = "No paired devices found.",
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
                                    !item.online -> "Offline"
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
                                    else -> "Unknown"
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
                        text = "OK",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualPairingCodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var codeText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manually Enter Pairing Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Type the 6-digit code shown on the other device (e.g. 742 - 918) or paste a pairing code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it },
                    label = { Text("Pairing Code") },
                    placeholder = { Text("e.g. 742 918") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (codeText.isNotBlank()) {
                        onConfirm(codeText)
                        onDismiss()
                    }
                }
            ) {
                Text("Connect & Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
