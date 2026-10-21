package com.fileapex.ui.devices

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.DemoModeBanner
import com.fileapex.ui.DeviceOrderDragHandle
import com.fileapex.ui.DeviceOrderEdgeAutoScrollEffect
import com.fileapex.ui.DeviceOrderItemPlacementSpec
import com.fileapex.ui.DevicesScreenLayoutMode
import com.fileapex.ui.EmptyDevicesQuickStartCard
import com.fileapex.ui.adaptive.FileApexWidthSizeClass
import com.fileapex.ui.adaptive.widthSizeClassFor
import com.fileapex.ui.deviceOrderDragIntOffset
import com.fileapex.ui.deviceOrderItemVisualOffsetPx
import com.fileapex.ui.deviceOrderListOverflowsViewport
import com.fileapex.ui.rememberDeviceOrderDragState
import kotlin.math.roundToInt

/** Approximate device card row height used for desktop window sizing. */
val DeviceCardSlotHeight = 96.dp

/** Bottom padding under the last device card, inside the list section. */
val DeviceListToAddGap = 16.dp

internal data class DeviceGridLayoutSpec(
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
            iconSize = 24.dp,
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

/**
 * Diff-keyed list for paired devices. Edit reorder uses a fixed-height [Column] so every
 * device stays composed; browse mode uses a diff-keyed [LazyColumn].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PairedDevicesList(
    listState: LazyListState,
    viewMode: ExplorerViewMode,
    layoutMode: DevicesScreenLayoutMode,
    deviceRows: List<DeviceListRow>,
    isInitialLoadComplete: Boolean = true,
    editMode: Boolean,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    onAddDevice: () -> Unit,
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
                    isInitialLoadComplete = isInitialLoadComplete,
                    layoutMode = layoutMode,
                    connectingDeviceId = connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    onAddDevice = onAddDevice,
                    onOpenDevice = onOpenDevice,
                    onRenameDevice = onRenameDevice,
                    onDeviceDetails = onDeviceDetails,
                    onSendClipboardDevice = onSendClipboardDevice,
                    onRemoveDevice = onRemoveDevice,
                    onFilesDropped = onFilesDropped,
                    modifier = modifier
                )
                ExplorerViewMode.List, ExplorerViewMode.Split -> PairedDevicesBrowseList(
                    listState = listState,
                    deviceRows = deviceRows,
                    isInitialLoadComplete = isInitialLoadComplete,
                    connectingDeviceId = connectingDeviceId,
                    selectedDeviceId = selectedDeviceId,
                    onAddDevice = onAddDevice,
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
    isInitialLoadComplete: Boolean = true,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    onAddDevice: () -> Unit = {},
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
    val isDemoActive by com.fileapex.domain.demo.DemoModeState.isDemoModeActive.collectAsState()
    val bottomPadding = if (isInitialLoadComplete && deviceRows.isEmpty() && !isDemoActive) 24.dp else DeviceListToAddGap

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = listTopPadding,
            bottom = bottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(itemSpacing)
    ) {
        if (isDemoActive) {
            item(key = "demo_banner") {
                DemoModeBanner(
                    onExitDemo = { com.fileapex.domain.demo.DemoModeState.exitDemo() }
                )
            }
        }
        if (isInitialLoadComplete && deviceRows.isEmpty() && !isDemoActive) {
            item(
                key = "empty",
                contentType = "empty"
            ) {
                EmptyDevicesQuickStartCard(
                    onAddDevice = onAddDevice,
                    onLaunchDemo = { com.fileapex.domain.demo.DemoModeState.launchDemo() }
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
    isInitialLoadComplete: Boolean = true,
    layoutMode: DevicesScreenLayoutMode,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    onAddDevice: () -> Unit = {},
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDemoActive by com.fileapex.domain.demo.DemoModeState.isDemoModeActive.collectAsState()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Skip first frame(s) with unset constraints so Fixed column count is not computed wrong.
        if (!maxWidth.value.isFinite() || maxWidth <= 0.dp) return@BoxWithConstraints
        val grid = resolveDeviceGridLayout(maxWidth = maxWidth, layoutMode = layoutMode)
        val bottomPadding = if (isInitialLoadComplete && deviceRows.isEmpty() && !isDemoActive) 24.dp else DeviceListToAddGap
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = grid.contentPadding,
                end = grid.contentPadding,
                top = 8.dp,
                bottom = bottomPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(grid.cellSpacing),
            verticalArrangement = Arrangement.spacedBy(grid.cellSpacing)
        ) {
            if (isDemoActive) {
                item(key = "demo_banner", span = { GridItemSpan(grid.columnCount) }) {
                    DemoModeBanner(
                        onExitDemo = { com.fileapex.domain.demo.DemoModeState.exitDemo() }
                    )
                }
            }
            if (isInitialLoadComplete && deviceRows.isEmpty() && !isDemoActive) {
                item(key = "empty", span = { GridItemSpan(grid.columnCount) }) {
                    EmptyDevicesQuickStartCard(
                        onAddDevice = onAddDevice,
                        onLaunchDemo = { com.fileapex.domain.demo.DemoModeState.launchDemo() }
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
