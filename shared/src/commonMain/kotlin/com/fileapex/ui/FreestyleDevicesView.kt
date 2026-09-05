package com.fileapex.ui

import com.fileapex.ui.dnd.deviceFileDropTarget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DeviceListRow
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class FreestyleMenuAction(val key: String, val shortLabel: String, val fullLabelRes: String, val icon: ImageVector) {
    BROWSE("browse", "Browse", "browse", Icons.Filled.FolderOpen),
    CLIPBOARD("clipboard", "Clip", "clipboard", Icons.Filled.ContentPaste),
    INFO("info", "Details", "details", Icons.Filled.Info),
    RENAME("rename", "Rename", "rename", Icons.Filled.Edit),
    REMOVE("remove", "Remove", "remove", Icons.Filled.Delete);

    companion object {
        fun parseOrder(orderStr: String?): List<FreestyleMenuAction> {
            if (orderStr.isNullOrBlank()) return entries
            val tokens = orderStr.split(",").map { it.trim().lowercase() }
            val resolved = tokens.mapNotNull { token -> entries.firstOrNull { it.key == token } }
            val missing = entries.filterNot { resolved.contains(it) }
            return (resolved + missing)
        }

        fun encodeOrder(actions: List<FreestyleMenuAction>): String =
            actions.joinToString(",") { it.key }
    }
}

private enum class FreestyleOptionAction(val key: String, val icon: ImageVector) {
    FILES("files", Icons.Filled.Folder),
    ADD_DEVICE("add_device", Icons.Filled.QrCode),
    JOIN_DEVICE("join_device", Icons.Filled.Devices),
    SEND_CLIPBOARD("send_clipboard", Icons.Filled.ContentPaste),
    CHECK_BATTERIES("check_batteries", Icons.Filled.BatteryChargingFull),
    SETTINGS("settings", Icons.Filled.Settings);

    companion object {
        val DEFAULT_ORDER = listOf(
            FILES,
            ADD_DEVICE,
            JOIN_DEVICE,
            SEND_CLIPBOARD,
            CHECK_BATTERIES,
            SETTINGS
        )

        fun parseOrder(raw: String?): List<FreestyleOptionAction> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val tokens = raw.split(",").map { it.trim().lowercase() }
            val byKey = entries.associateBy { it.key }
            val parsed = tokens.mapNotNull { byKey[it] }.distinct()
            val missing = DEFAULT_ORDER.filter { it !in parsed }
            return parsed + missing
        }

        fun encodeOrder(list: List<FreestyleOptionAction>): String =
            list.joinToString(",") { it.key }
    }
}

@Composable
private fun FreestyleOptionAction.label(): String = when (this) {
    FreestyleOptionAction.FILES -> "Files"
    FreestyleOptionAction.ADD_DEVICE -> stringRes("add_new_device")
    FreestyleOptionAction.JOIN_DEVICE -> stringRes("join_device")
    FreestyleOptionAction.SEND_CLIPBOARD -> stringRes("send_clipboard")
    FreestyleOptionAction.CHECK_BATTERIES -> stringRes("check_batteries")
    FreestyleOptionAction.SETTINGS -> stringRes("settings")
}

@Composable
fun FreestyleDevicesView(
    deviceRows: List<DeviceListRow>,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    isEditMode: Boolean,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    onSaveDeviceCardPosition: (deviceId: String, x: Float, y: Float) -> Unit,
    onSaveDeviceTilePosition: (deviceId: String, x: Float, y: Float) -> Unit,
    onSaveDeviceCardMenuOrder: (deviceId: String, order: String) -> Unit,
    onSaveDeviceTileMenuOrder: (deviceId: String, order: String) -> Unit,
    onGenerateQr: () -> Unit = {},
    onJoinDevice: () -> Unit = {},
    onCheckBatteries: (() -> Unit)? = null,
    onSendClipboard: (() -> Unit)? = null,
    onOpenLocalFiles: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    thisDeviceTarget: BrowseTarget? = null,
    onResolveBrowseTarget: ((deviceId: String, onTargetResolved: (BrowseTarget) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val freestyleMode by FileApexServices.settings.freestyleLayoutMode.collectAsState()
    val isCardLayout = freestyleMode.isCard
    val isTileLayout = freestyleMode.isTile

    val density = LocalDensity.current
    var activeDeviceId by remember { mutableStateOf<String?>(null) }
    var optionsMenuExpanded by remember { mutableStateOf(false) }

    // Floating File Navigation Window State
    var showFilesWindow by remember { mutableStateOf(false) }
    var browsingTarget by remember { mutableStateOf<BrowseTarget?>(null) }
    var isResolvingBrowse by remember { mutableStateOf(false) }
    var resolvingDeviceName by remember { mutableStateOf<String?>(null) }

    // One-time Edit Tutorial State
    val editTutorialShown by FileApexServices.settings.freestyleEditTutorialShown.collectAsState()
    var showTutorialDialog by remember { mutableStateOf(false) }

    val persistedCardOptionsX by FileApexServices.settings.freestyleCardOptionsPosX.collectAsState()
    val persistedCardOptionsY by FileApexServices.settings.freestyleCardOptionsPosY.collectAsState()
    val persistedCardVerticalOptionsX by FileApexServices.settings.freestyleCardVerticalOptionsPosX.collectAsState()
    val persistedCardVerticalOptionsY by FileApexServices.settings.freestyleCardVerticalOptionsPosY.collectAsState()
    val persistedTileOptionsX by FileApexServices.settings.freestyleTileOptionsPosX.collectAsState()
    val persistedTileOptionsY by FileApexServices.settings.freestyleTileOptionsPosY.collectAsState()

    val persistedCardNodeOffsets by FileApexServices.settings.freestyleCardNodeOffsets.collectAsState()
    val persistedCardVerticalNodeOffsets by FileApexServices.settings.freestyleCardVerticalNodeOffsets.collectAsState()
    val persistedTileNodeOffsets by FileApexServices.settings.freestyleTileNodeOffsets.collectAsState()
    val persistedCardMenuOrders by FileApexServices.settings.freestyleCardMenuOrders.collectAsState()
    val persistedCardVerticalMenuOrders by FileApexServices.settings.freestyleCardVerticalMenuOrders.collectAsState()
    val persistedTileMenuOrders by FileApexServices.settings.freestyleTileMenuOrders.collectAsState()
    val persistedOptionsMenuOrder by FileApexServices.settings.freestyleOptionsMenuOrder.collectAsState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isEditMode) {
                    activeDeviceId = null
                    optionsMenuExpanded = false
                }
            }
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val marginPx = with(density) { 16.dp.toPx() }
        val topMarginPx = with(density) { 16.dp.toPx() }
        // Full height utilization down to true bottom of app
        val bottomMarginPx = with(density) { 16.dp.toPx() }

        val deviceButtonSizeDp = 68.dp
        val deviceButtonSizePx = with(density) { deviceButtonSizeDp.toPx() }

        val optionsButtonWidthDp = 110.dp
        val optionsButtonHeightDp = 42.dp
        val optionsButtonWidthPx = with(density) { optionsButtonWidthDp.toPx() }
        val optionsButtonHeightPx = with(density) { optionsButtonHeightDp.toPx() }

        // Floating File Window Position & Size State
        var windowWidthDp by remember { mutableStateOf(400.dp) }
        var windowHeightDp by remember { mutableStateOf(480.dp) }
        var windowOffsetPx by remember(widthPx, heightPx) {
            val initialW = with(density) { 400.dp.toPx() }
            val initialH = with(density) { 480.dp.toPx() }
            val initX = ((widthPx - initialW) / 2f).coerceAtLeast(marginPx)
            val initY = ((heightPx - initialH) / 2f).coerceAtLeast(topMarginPx)
            mutableStateOf(Offset(initX, initY))
        }

        // Draggable Options Button per layout mode
        val savedOptionsFractionX = when (freestyleMode) {
            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardOptionsX
            FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalOptionsX ?: persistedCardOptionsX
            FreestyleLayoutMode.TILES -> persistedTileOptionsX
        }
        val savedOptionsFractionY = when (freestyleMode) {
            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardOptionsY
            FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalOptionsY ?: persistedCardOptionsY
            FreestyleLayoutMode.TILES -> persistedTileOptionsY
        }

        val defaultOptionsPx = remember(widthPx, heightPx, optionsButtonWidthPx, optionsButtonHeightPx) {
            Offset(
                x = (widthPx - optionsButtonWidthPx) / 2f,
                y = heightPx - bottomMarginPx - optionsButtonHeightPx - 8f
            )
        }

        var liveOptionsPos by remember(freestyleMode) {
            val initialX = savedOptionsFractionX?.let { it * widthPx - (optionsButtonWidthPx / 2f) } ?: defaultOptionsPx.x
            val initialY = savedOptionsFractionY?.let { it * heightPx - (optionsButtonHeightPx / 2f) } ?: defaultOptionsPx.y
            mutableStateOf(
                Offset(
                    initialX.coerceIn(marginPx, widthPx - marginPx - optionsButtonWidthPx),
                    initialY.coerceIn(topMarginPx, heightPx - bottomMarginPx - optionsButtonHeightPx)
                )
            )
        }

        LaunchedEffect(freestyleMode, isEditMode) {
            if (!isEditMode) {
                val initialX = savedOptionsFractionX?.let { it * widthPx - (optionsButtonWidthPx / 2f) } ?: defaultOptionsPx.x
                val initialY = savedOptionsFractionY?.let { it * heightPx - (optionsButtonHeightPx / 2f) } ?: defaultOptionsPx.y
                liveOptionsPos = Offset(
                    initialX.coerceIn(marginPx, widthPx - marginPx - optionsButtonWidthPx),
                    initialY.coerceIn(topMarginPx, heightPx - bottomMarginPx - optionsButtonHeightPx)
                )
            }
        }

        // Auto-open Options and the farthest device (to avoid overlap) when Edit Mode is toggled
        LaunchedEffect(isEditMode) {
            if (isEditMode) {
                optionsMenuExpanded = true
                if (activeDeviceId == null && deviceRows.isNotEmpty()) {
                    val optCenterX = liveOptionsPos.x + (optionsButtonWidthPx / 2f)
                    val optCenterY = liveOptionsPos.y + (optionsButtonHeightPx / 2f)
                    val farthest = deviceRows.maxByOrNull { row ->
                        val savedX = when (freestyleMode) {
                            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets[row.deviceId]?.first
                            FreestyleLayoutMode.CARDS_VERTICAL -> (persistedCardVerticalNodeOffsets[row.deviceId] ?: persistedCardNodeOffsets[row.deviceId])?.first
                            FreestyleLayoutMode.TILES -> persistedTileNodeOffsets[row.deviceId]?.first
                        }
                        val savedY = when (freestyleMode) {
                            FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets[row.deviceId]?.second
                            FreestyleLayoutMode.CARDS_VERTICAL -> (persistedCardVerticalNodeOffsets[row.deviceId] ?: persistedCardNodeOffsets[row.deviceId])?.second
                            FreestyleLayoutMode.TILES -> persistedTileNodeOffsets[row.deviceId]?.second
                        }
                        val pxX = savedX?.let { it * widthPx } ?: (widthPx / 2f)
                        val pxY = savedY?.let { it * heightPx } ?: (heightPx / 2f)
                        val dx = pxX - optCenterX
                        val dy = pxY - optCenterY
                        dx * dx + dy * dy
                    }
                    activeDeviceId = farthest?.deviceId ?: deviceRows.first().deviceId
                }
                if (!editTutorialShown) {
                    showTutorialDialog = true
                }
            } else {
                showTutorialDialog = false
                optionsMenuExpanded = false
                activeDeviceId = null
            }
        }

        val handleBrowseDevice: (String, String) -> Unit = { deviceId, deviceName ->
            showFilesWindow = true
            isResolvingBrowse = true
            resolvingDeviceName = deviceName
            if (onResolveBrowseTarget != null) {
                onResolveBrowseTarget(deviceId) { target ->
                    browsingTarget = target
                    isResolvingBrowse = false
                }
            } else {
                isResolvingBrowse = false
                onOpenDevice(deviceId)
            }
        }

        // Live node positions map to eliminate jerkiness on repeated moves
        val liveNodePositions = remember(freestyleMode) { mutableStateMapOf<String, Offset>() }

        LaunchedEffect(isEditMode) {
            if (!isEditMode) {
                liveNodePositions.clear()
            }
        }

        val syncOrderToAllDevices = { newOrder: String ->
            deviceRows.forEach { dev ->
                FileApexServices.settings.setFreestyleMenuOrder(freestyleMode, dev.deviceId, newOrder)
                if (freestyleMode == FreestyleLayoutMode.TILES) {
                    onSaveDeviceTileMenuOrder(dev.deviceId, newOrder)
                } else {
                    onSaveDeviceCardMenuOrder(dev.deviceId, newOrder)
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(liveOptionsPos.x.roundToInt(), liveOptionsPos.y.roundToInt()) }
                .zIndex(30f)
                .pointerInput(freestyleMode, widthPx, heightPx) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val nextX = (liveOptionsPos.x + dragAmount.x)
                                .coerceIn(marginPx, widthPx - marginPx - optionsButtonWidthPx)
                            val nextY = (liveOptionsPos.y + dragAmount.y)
                                .coerceIn(topMarginPx, heightPx - bottomMarginPx - optionsButtonHeightPx)
                            liveOptionsPos = Offset(nextX, nextY)
                        },
                        onDragEnd = {
                            val centerX = liveOptionsPos.x + optionsButtonWidthPx / 2f
                            val centerY = liveOptionsPos.y + optionsButtonHeightPx / 2f
                            FileApexServices.settings.setFreestyleOptionsPosition(
                                mode = freestyleMode,
                                x = (centerX / widthPx).coerceIn(0f, 1f),
                                y = (centerY / heightPx).coerceIn(0f, 1f)
                            )
                        }
                    )
                }
        ) {
            Surface(
                modifier = Modifier
                    .width(optionsButtonWidthDp)
                    .height(optionsButtonHeightDp)
                    .clip(RoundedCornerShape(21.dp))
                    .clickable { optionsMenuExpanded = !optionsMenuExpanded }
                    .border(
                        BorderStroke(
                            width = if (isEditMode) 1.5.dp else 1.dp,
                            color = if (isEditMode) Color(0xFF64B5F6) else Color(0x8864B5F6)
                        ),
                        RoundedCornerShape(21.dp)
                    ),
                shape = RoundedCornerShape(21.dp),
                color = Color(0xDD0D2235),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isEditMode) {
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "Options",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        color = Color.White
                    )
                }
            }

            var optionsActionsList by remember(persistedOptionsMenuOrder) {
                mutableStateOf(FreestyleOptionAction.parseOrder(persistedOptionsMenuOrder))
            }

            if (optionsMenuExpanded) {
                val dropdownWidthDp = 230.dp
                val dropdownWidthPx = with(density) { dropdownWidthDp.toPx() }
                val dropdownHeightDp = 260.dp
                val dropdownHeightPx = with(density) { dropdownHeightDp.toPx() }

                val idealDropdownX = liveOptionsPos.x
                val clampedDropdownX = idealDropdownX.coerceIn(marginPx, (widthPx - marginPx - dropdownWidthPx).coerceAtLeast(marginPx))
                val relDropdownXDp = with(density) { (clampedDropdownX - liveOptionsPos.x).toDp() }

                val placeBelow = (liveOptionsPos.y + optionsButtonHeightPx + with(density) { 6.dp.toPx() } + dropdownHeightPx) <= (heightPx - bottomMarginPx)
                val idealDropdownY = if (placeBelow) {
                    liveOptionsPos.y + optionsButtonHeightPx + with(density) { 6.dp.toPx() }
                } else {
                    liveOptionsPos.y - dropdownHeightPx - with(density) { 6.dp.toPx() }
                }
                val clampedDropdownY = idealDropdownY.coerceIn(topMarginPx, (heightPx - bottomMarginPx - dropdownHeightPx).coerceAtLeast(topMarginPx))
                val relDropdownYDp = with(density) { (clampedDropdownY - liveOptionsPos.y).toDp() }

                var draggingOptIndex by remember { mutableStateOf<Int?>(null) }
                var draggingOptOffsetY by remember { mutableStateOf(0f) }
                val optRowHeightDp = 40.dp
                val optRowHeightPx = with(density) { optRowHeightDp.toPx() }

                Surface(
                    modifier = Modifier
                        .offset(x = relDropdownXDp, y = relDropdownYDp)
                        .width(dropdownWidthDp)
                        .shadow(16.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            BorderStroke(
                                width = if (isEditMode) 1.5.dp else 1.dp,
                                color = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6).copy(alpha = 0.8f)
                            ),
                            RoundedCornerShape(14.dp)
                        ),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xF80B1B2B)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        optionsActionsList.forEachIndexed { optIndex, optAction ->
                            val isAvailable = when (optAction) {
                                FreestyleOptionAction.SEND_CLIPBOARD -> onSendClipboard != null
                                FreestyleOptionAction.CHECK_BATTERIES -> onCheckBatteries != null
                                FreestyleOptionAction.SETTINGS -> onOpenSettings != null
                                else -> true
                            }
                            if (!isAvailable && !isEditMode) return@forEachIndexed

                            key(optAction.name) {
                                val isDragging = draggingOptIndex == optIndex
                                val translationY = if (isDragging) draggingOptOffsetY else 0f
                                val zIndexVal = if (isDragging) 50f else 1f

                                Surface(
                                    color = if (isDragging) Color(0x5564B5F6) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(optRowHeightDp)
                                        .zIndex(zIndexVal)
                                        .graphicsLayer { this.translationY = translationY }
                                        .clickable(enabled = !isEditMode) {
                                            optionsMenuExpanded = false
                                            when (optAction) {
                                                FreestyleOptionAction.FILES -> showFilesWindow = true
                                                FreestyleOptionAction.ADD_DEVICE -> onGenerateQr()
                                                FreestyleOptionAction.JOIN_DEVICE -> onJoinDevice()
                                                FreestyleOptionAction.SEND_CLIPBOARD -> onSendClipboard?.invoke()
                                                FreestyleOptionAction.CHECK_BATTERIES -> onCheckBatteries?.invoke()
                                                FreestyleOptionAction.SETTINGS -> onOpenSettings?.invoke()
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isEditMode) {
                                            Icon(
                                                imageVector = Icons.Filled.DragHandle,
                                                contentDescription = stringRes("reorder_devices"),
                                                tint = Color(0xFFFFB300),
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .pointerInput(optAction) {
                                                        detectDragGestures(
                                                            onDragStart = {
                                                                draggingOptIndex = optIndex
                                                                draggingOptOffsetY = 0f
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                val currentIdx = draggingOptIndex ?: optIndex
                                                                val newOffset = draggingOptOffsetY + dragAmount.y
                                                                draggingOptOffsetY = newOffset
                                                                if (newOffset > optRowHeightPx * 0.5f && currentIdx < optionsActionsList.lastIndex) {
                                                                    val mutable = optionsActionsList.toMutableList()
                                                                    val temp = mutable[currentIdx]
                                                                    mutable[currentIdx] = mutable[currentIdx + 1]
                                                                    mutable[currentIdx + 1] = temp
                                                                    optionsActionsList = mutable
                                                                    draggingOptIndex = currentIdx + 1
                                                                    draggingOptOffsetY = newOffset - optRowHeightPx
                                                                    FileApexServices.settings.setFreestyleOptionsMenuOrder(
                                                                        FreestyleOptionAction.encodeOrder(mutable)
                                                                    )
                                                                } else if (newOffset < -optRowHeightPx * 0.5f && currentIdx > 0) {
                                                                    val mutable = optionsActionsList.toMutableList()
                                                                    val temp = mutable[currentIdx]
                                                                    mutable[currentIdx] = mutable[currentIdx - 1]
                                                                    mutable[currentIdx - 1] = temp
                                                                    optionsActionsList = mutable
                                                                    draggingOptIndex = currentIdx - 1
                                                                    draggingOptOffsetY = newOffset + optRowHeightPx
                                                                    FileApexServices.settings.setFreestyleOptionsMenuOrder(
                                                                        FreestyleOptionAction.encodeOrder(mutable)
                                                                    )
                                                                }
                                                            },
                                                            onDragEnd = {
                                                                draggingOptIndex = null
                                                                draggingOptOffsetY = 0f
                                                            },
                                                            onDragCancel = {
                                                                draggingOptIndex = null
                                                                draggingOptOffsetY = 0f
                                                            }
                                                        )
                                                    }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Icon(
                                            imageVector = optAction.icon,
                                            contentDescription = null,
                                            tint = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = optAction.label(),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isEditMode) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = 13.sp
                                            ),
                                            color = Color.White,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Render Device Nodes and Menus
        deviceRows.forEachIndexed { index, row ->
            val cachedOffset = when (freestyleMode) {
                FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardNodeOffsets[row.deviceId]
                FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalNodeOffsets[row.deviceId] ?: persistedCardNodeOffsets[row.deviceId]
                FreestyleLayoutMode.TILES -> persistedTileNodeOffsets[row.deviceId]
            }
            val savedFractionX = cachedOffset?.first ?: (if (freestyleMode == FreestyleLayoutMode.TILES) row.tilePosX else row.cardPosX)
            val savedFractionY = cachedOffset?.second ?: (if (freestyleMode == FreestyleLayoutMode.TILES) row.tilePosY else row.cardPosY)

            val defaultPos = remember(index, deviceRows.size, widthPx, heightPx) {
                val count = deviceRows.size
                if (count == 1) {
                    Offset(
                        x = (widthPx - deviceButtonSizePx) / 2f,
                        y = (heightPx * 0.40f) - (deviceButtonSizePx / 2f)
                    )
                } else {
                    val angle = (2 * PI * index / count) - (PI / 2)
                    val rx = (widthPx * 0.32f).coerceAtLeast(100f)
                    val ry = (heightPx * 0.28f).coerceAtLeast(100f)
                    val cx = widthPx / 2f
                    val cy = heightPx * 0.40f
                    Offset(
                        x = (cx + (rx * cos(angle)).toFloat() - (deviceButtonSizePx / 2f)),
                        y = (cy + (ry * sin(angle)).toFloat() - (deviceButtonSizePx / 2f))
                    )
                }
            }

            val nameOffsetPx = with(density) { 36.dp.toPx() }
            val minDeviceX = marginPx
            val maxDeviceX = (widthPx - marginPx - deviceButtonSizePx).coerceAtLeast(marginPx)
            val minDeviceY = topMarginPx
            val maxDeviceY = (heightPx - bottomMarginPx - deviceButtonSizePx - nameOffsetPx).coerceAtLeast(topMarginPx)

            val livePos = liveNodePositions.getOrPut(row.deviceId) {
                val initialX = savedFractionX?.let { it * widthPx - (deviceButtonSizePx / 2f) } ?: defaultPos.x
                val initialY = savedFractionY?.let { it * heightPx - (deviceButtonSizePx / 2f) } ?: defaultPos.y
                Offset(
                    initialX.coerceIn(minDeviceX, maxDeviceX),
                    initialY.coerceIn(minDeviceY, maxDeviceY)
                )
            }

            val isExpanded = activeDeviceId == row.deviceId
            val isConnecting = connectingDeviceId == row.deviceId
            val isSelected = selectedDeviceId == row.deviceId
            val statusColor = when {
                isConnecting -> Color(0xFF00E5FF)
                row.online -> Color(0xFF00E676)
                else -> Color(0xFFFFB300)
            }

            val elementZIndex = when {
                isExpanded -> 100f
                isSelected -> 50f
                else -> 20f
            }

            var dropHover by remember { mutableStateOf(false) }

            val deviceAlpha = if (activeDeviceId != null && !isExpanded) 0.35f else 1f

            // Single Device Node Box (Button + Label below)
            Box(
                modifier = Modifier
                    .offset { IntOffset(livePos.x.roundToInt(), livePos.y.roundToInt()) }
                    .zIndex(elementZIndex)
                    .alpha(deviceAlpha)
            ) {
                // Circular Device Action Button (Representing device type via DeviceEntryIcon)
                Box(
                    modifier = Modifier
                        .size(deviceButtonSizeDp)
                        .pointerInput(row.deviceId, freestyleMode, widthPx, heightPx) {
                            detectDragGestures(
                                onDragStart = {
                                    if (!isEditMode) {
                                        activeDeviceId = null
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val cur = liveNodePositions[row.deviceId] ?: livePos
                                    val nextX = (cur.x + dragAmount.x).coerceIn(minDeviceX, maxDeviceX)
                                    val nextY = (cur.y + dragAmount.y).coerceIn(minDeviceY, maxDeviceY)
                                    liveNodePositions[row.deviceId] = Offset(nextX, nextY)
                                },
                                onDragEnd = {
                                    val finalPos = liveNodePositions[row.deviceId] ?: livePos
                                    val centerX = finalPos.x + (deviceButtonSizePx / 2f)
                                    val centerY = finalPos.y + (deviceButtonSizePx / 2f)
                                    val fracX = (centerX / widthPx).coerceIn(0f, 1f)
                                    val fracY = (centerY / heightPx).coerceIn(0f, 1f)
                                    FileApexServices.settings.setFreestyleNodeOffset(freestyleMode, row.deviceId, fracX, fracY)
                                    if (freestyleMode == FreestyleLayoutMode.TILES) {
                                        onSaveDeviceTilePosition(row.deviceId, fracX, fracY)
                                    } else {
                                        onSaveDeviceCardPosition(row.deviceId, fracX, fracY)
                                    }
                                }
                            )
                        }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .deviceFileDropTarget(
                                enabled = true,
                                onHoverChange = { dropHover = it },
                                onFilesDropped = { paths -> onFilesDropped(row.deviceId, paths) }
                            )
                            .clickable {
                                activeDeviceId = if (isExpanded && !isEditMode) null else row.deviceId
                            },
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(
                            width = if (isExpanded || isSelected || dropHover) 2.5.dp else 1.5.dp,
                            color = if (dropHover) Color(0xFF00E676) else (if (isExpanded) Color(0xFF64B5F6) else statusColor)
                        ),
                        shadowElevation = if (isExpanded || dropHover) 14.dp else 6.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            if (dropHover) Color(0xCC00E676) else (if (isExpanded) Color(0xCC0D3859) else Color(0xDD0D1E2D)),
                                            Color(0xEE050B12)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val iconStyle = LocalThemeIconStyle.current
                            val buttonBorderWidth = if (isExpanded || isSelected || dropHover) 2.5.dp else 1.5.dp
                            DeviceEntryIcon(
                                row = row,
                                modifier = if (iconStyle == ThemeIconStyle.STANDARD) {
                                    Modifier.size(32.dp)
                                } else {
                                    Modifier.fillMaxSize().padding(buttonBorderWidth)
                                },
                                tint = if (isExpanded) Color(0xFF64B5F6) else Color.White
                            )

                            if (isEditMode) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DragHandle,
                                        contentDescription = null,
                                        tint = Color(0xFF64B5F6),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            // Online / Status Dot
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 6.dp)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                                    .border(BorderStroke(1.5.dp, Color(0xFF0D1E2D)), CircleShape)
                            )
                        }
                    }
                }

                // Device Name placed directly below the button
                Column(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                -with(density) { 16.dp.toPx() }.roundToInt(),
                                (deviceButtonSizePx + with(density) { 4.dp.toPx() }).roundToInt()
                            )
                        }
                        .width(deviceButtonSizeDp + 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = row.deviceName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            lineHeight = 13.sp
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                // Expanded Context Actions for this device
                if (isExpanded) {
                    val savedMenuOrder = when (freestyleMode) {
                        FreestyleLayoutMode.CARDS_HORIZONTAL -> {
                            persistedCardMenuOrders[row.deviceId]
                                ?: persistedCardMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: deviceRows.mapNotNull { it.cardMenuOrder.takeIf { o -> o.isNotBlank() } }.firstOrNull()
                                ?: row.cardMenuOrder
                        }
                        FreestyleLayoutMode.CARDS_VERTICAL -> {
                            persistedCardVerticalMenuOrders[row.deviceId]
                                ?: persistedCardVerticalMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: persistedCardMenuOrders[row.deviceId]
                                ?: persistedCardMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: deviceRows.mapNotNull { it.cardMenuOrder.takeIf { o -> o.isNotBlank() } }.firstOrNull()
                                ?: row.cardMenuOrder
                        }
                        FreestyleLayoutMode.TILES -> {
                            persistedTileMenuOrders[row.deviceId]
                                ?: persistedTileMenuOrders.values.firstOrNull { it.isNotBlank() }
                                ?: deviceRows.mapNotNull { it.tileMenuOrder.takeIf { o -> o.isNotBlank() } }.firstOrNull()
                                ?: row.tileMenuOrder
                        }
                    }
                    var actionsList by remember(row.deviceId, freestyleMode, savedMenuOrder) {
                        mutableStateOf(FreestyleMenuAction.parseOrder(savedMenuOrder))
                    }

                    when (freestyleMode) {
                        FreestyleLayoutMode.CARDS_HORIZONTAL -> {
                            // Layout 1: Horizontal Card extending horizontally from device button
                            val cardWidthDp = 280.dp
                            val cardWidthPx = with(density) { cardWidthDp.toPx() }
                            val cardHeightDp = 64.dp
                            val cardHeightPx = with(density) { cardHeightDp.toPx() }

                            val idealCardScreenX = if ((livePos.x + deviceButtonSizePx + cardWidthPx + marginPx) <= widthPx) {
                                livePos.x + deviceButtonSizePx + with(density) { 8.dp.toPx() }
                            } else {
                                livePos.x - cardWidthPx - with(density) { 8.dp.toPx() }
                            }
                            val clampedCardScreenX = idealCardScreenX.coerceIn(marginPx, (widthPx - marginPx - cardWidthPx).coerceAtLeast(marginPx))
                            val relativeCardXDp = with(density) { (clampedCardScreenX - livePos.x).toDp() }

                            val clampedCardScreenY = livePos.y.coerceIn(topMarginPx, (heightPx - bottomMarginPx - cardHeightPx).coerceAtLeast(topMarginPx))
                            val relativeCardYDp = with(density) { (clampedCardScreenY - livePos.y).toDp() }

                            var draggingHActionIndex by remember(row.deviceId) { mutableStateOf<Int?>(null) }
                            var draggingHActionOffsetX by remember(row.deviceId) { mutableStateOf(0f) }
                            val hItemWidthDp = 50.dp
                            val hItemWidthPx = with(density) { hItemWidthDp.toPx() }

                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                                exit = fadeOut() + scaleOut(targetScale = 0.85f),
                                modifier = Modifier.offset(x = relativeCardXDp, y = relativeCardYDp)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .width(cardWidthDp)
                                        .height(cardHeightDp)
                                        .shadow(16.dp, RoundedCornerShape(16.dp))
                                        .border(
                                            BorderStroke(
                                                width = if (isEditMode) 2.dp else 1.5.dp,
                                                color = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6).copy(alpha = 0.8f)
                                            ),
                                            RoundedCornerShape(16.dp)
                                        ),
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xF50A1826)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        actionsList.forEachIndexed { actionIndex, action ->
                                            key(action.name) {
                                                val isDragging = draggingHActionIndex == actionIndex
                                                val dragOffsetX = if (isDragging) draggingHActionOffsetX else 0f

                                                FreestyleHorizontalCardItem(
                                                    action = action,
                                                    isEditMode = isEditMode,
                                                    isDragging = isDragging,
                                                    dragOffsetX = dragOffsetX,
                                                    onDragStart = {
                                                        draggingHActionIndex = actionIndex
                                                        draggingHActionOffsetX = 0f
                                                    },
                                                    onDrag = { deltaX ->
                                                        val currentIdx = draggingHActionIndex ?: actionIndex
                                                        val newOffset = draggingHActionOffsetX + deltaX
                                                        draggingHActionOffsetX = newOffset
                                                        if (newOffset > hItemWidthPx * 0.5f && currentIdx < actionsList.lastIndex) {
                                                            val mutable = actionsList.toMutableList()
                                                            val temp = mutable[currentIdx]
                                                            mutable[currentIdx] = mutable[currentIdx + 1]
                                                            mutable[currentIdx + 1] = temp
                                                            actionsList = mutable
                                                            draggingHActionIndex = currentIdx + 1
                                                            draggingHActionOffsetX = newOffset - hItemWidthPx
                                                            val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                                            syncOrderToAllDevices(encoded)
                                                        } else if (newOffset < -hItemWidthPx * 0.5f && currentIdx > 0) {
                                                            val mutable = actionsList.toMutableList()
                                                            val temp = mutable[currentIdx]
                                                            mutable[currentIdx] = mutable[currentIdx - 1]
                                                            mutable[currentIdx - 1] = temp
                                                            actionsList = mutable
                                                            draggingHActionIndex = currentIdx - 1
                                                            draggingHActionOffsetX = newOffset + hItemWidthPx
                                                            val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                                            syncOrderToAllDevices(encoded)
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggingHActionIndex = null
                                                        draggingHActionOffsetX = 0f
                                                    },
                                                    onClick = {
                                                        if (!isEditMode) {
                                                            activeDeviceId = null
                                                            when (action) {
                                                                FreestyleMenuAction.BROWSE -> handleBrowseDevice(row.deviceId, row.deviceName)
                                                                FreestyleMenuAction.CLIPBOARD -> onSendClipboardDevice(row.deviceId)
                                                                FreestyleMenuAction.INFO -> onDeviceDetails(row.deviceId)
                                                                FreestyleMenuAction.RENAME -> onRenameDevice(row.deviceId, row.deviceName)
                                                                FreestyleMenuAction.REMOVE -> onRemoveDevice(row.deviceId, row.deviceName)
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        FreestyleLayoutMode.CARDS_VERTICAL -> {
                            // Layout 2: Vertical Card extending vertically/sideways with wider 180dp width
                            val cardWidthDp = 180.dp
                            val cardWidthPx = with(density) { cardWidthDp.toPx() }
                            val cardHeightDp = 240.dp
                            val cardHeightPx = with(density) { cardHeightDp.toPx() }

                            val idealCardScreenX = if ((livePos.x + deviceButtonSizePx + cardWidthPx + marginPx) <= widthPx) {
                                livePos.x + deviceButtonSizePx + with(density) { 10.dp.toPx() }
                            } else {
                                livePos.x - cardWidthPx - with(density) { 10.dp.toPx() }
                            }
                            val clampedCardScreenX = idealCardScreenX.coerceIn(marginPx, (widthPx - marginPx - cardWidthPx).coerceAtLeast(marginPx))
                            val relativeCardXDp = with(density) { (clampedCardScreenX - livePos.x).toDp() }

                            val clampedCardScreenY = livePos.y.coerceIn(topMarginPx, (heightPx - bottomMarginPx - cardHeightPx).coerceAtLeast(topMarginPx))
                            val relativeCardYDp = with(density) { (clampedCardScreenY - livePos.y).toDp() }

                            var draggingCardActionIndex by remember(row.deviceId) { mutableStateOf<Int?>(null) }
                            var draggingCardActionOffsetY by remember(row.deviceId) { mutableStateOf(0f) }
                            val cardRowHeightDp = 34.dp
                            val cardRowHeightPx = with(density) { cardRowHeightDp.toPx() }

                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                                exit = fadeOut() + scaleOut(targetScale = 0.85f),
                                modifier = Modifier.offset(x = relativeCardXDp, y = relativeCardYDp)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .width(cardWidthDp)
                                        .shadow(16.dp, RoundedCornerShape(16.dp))
                                        .border(
                                            BorderStroke(
                                                width = if (isEditMode) 2.dp else 1.5.dp,
                                                color = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6).copy(alpha = 0.7f)
                                            ),
                                            RoundedCornerShape(16.dp)
                                        ),
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xF50A1826)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = row.deviceName,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            ),
                                            color = Color(0xFF64B5F6),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )

                                        actionsList.forEachIndexed { actionIndex, action ->
                                            key(action.name) {
                                                val isDragging = draggingCardActionIndex == actionIndex
                                                val dragOffsetY = if (isDragging) draggingCardActionOffsetY else 0f

                                                FreestyleMenuRowItem(
                                                    action = action,
                                                    isEditMode = isEditMode,
                                                    isDragging = isDragging,
                                                    dragOffsetY = dragOffsetY,
                                                    onDragStart = {
                                                        draggingCardActionIndex = actionIndex
                                                        draggingCardActionOffsetY = 0f
                                                    },
                                                    onDrag = { deltaY ->
                                                        val currentIdx = draggingCardActionIndex ?: actionIndex
                                                        val newOffset = draggingCardActionOffsetY + deltaY
                                                        draggingCardActionOffsetY = newOffset
                                                        if (newOffset > cardRowHeightPx * 0.5f && currentIdx < actionsList.lastIndex) {
                                                            val mutable = actionsList.toMutableList()
                                                            val temp = mutable[currentIdx]
                                                            mutable[currentIdx] = mutable[currentIdx + 1]
                                                            mutable[currentIdx + 1] = temp
                                                            actionsList = mutable
                                                            draggingCardActionIndex = currentIdx + 1
                                                            draggingCardActionOffsetY = newOffset - cardRowHeightPx
                                                            val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                                            syncOrderToAllDevices(encoded)
                                                        } else if (newOffset < -cardRowHeightPx * 0.5f && currentIdx > 0) {
                                                            val mutable = actionsList.toMutableList()
                                                            val temp = mutable[currentIdx]
                                                            mutable[currentIdx] = mutable[currentIdx - 1]
                                                            mutable[currentIdx - 1] = temp
                                                            actionsList = mutable
                                                            draggingCardActionIndex = currentIdx - 1
                                                            draggingCardActionOffsetY = newOffset + cardRowHeightPx
                                                            val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                                            syncOrderToAllDevices(encoded)
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggingCardActionIndex = null
                                                        draggingCardActionOffsetY = 0f
                                                    },
                                                    onClick = {
                                                        if (!isEditMode) {
                                                            activeDeviceId = null
                                                            when (action) {
                                                                FreestyleMenuAction.BROWSE -> handleBrowseDevice(row.deviceId, row.deviceName)
                                                                FreestyleMenuAction.CLIPBOARD -> onSendClipboardDevice(row.deviceId)
                                                                FreestyleMenuAction.INFO -> onDeviceDetails(row.deviceId)
                                                                FreestyleMenuAction.RENAME -> onRenameDevice(row.deviceId, row.deviceName)
                                                                FreestyleMenuAction.REMOVE -> onRemoveDevice(row.deviceId, row.deviceName)
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        FreestyleLayoutMode.TILES -> {
                            // Layout 3: Tiles (Directional Biasing & Quadrant Arc-Shifting Layout Engine)
                            val baseRadiusDp = 96.dp
                            val baseRadiusPx = with(density) { baseRadiusDp.toPx() }
                            val tileItemWidthDp = 46.dp
                            val tileItemHeightDp = 50.dp
                            val tileItemWidthPx = with(density) { tileItemWidthDp.toPx() }
                            val tileItemHeightPx = with(density) { tileItemHeightDp.toPx() }

                            val centerNodeX = livePos.x + (deviceButtonSizePx / 2f)
                            val centerNodeY = livePos.y + (deviceButtonSizePx / 2f)

                            var draggingTileIndex by remember(row.deviceId) { mutableStateOf<Int?>(null) }
                            var draggingTileAngularOffset by remember(row.deviceId) { mutableStateOf(0.0) }

                            val total = actionsList.size
                            val safePaddingPx = with(density) { 10.dp.toPx() }

                            // Absolute boundary bounds where tile centers can exist without crossing margins
                            val minSafeX = marginPx + (tileItemWidthPx / 2f)
                            val maxSafeX = widthPx - marginPx - (tileItemWidthPx / 2f)
                            val minSafeY = topMarginPx + (tileItemHeightPx / 2f)
                            val maxSafeY = heightPx - bottomMarginPx - (tileItemHeightPx / 2f)

                            val distLeft = centerNodeX - minSafeX
                            val distRight = maxSafeX - centerNodeX
                            val distTop = centerNodeY - minSafeY
                            val distBottom = maxSafeY - centerNodeY

                            val boundaryReachPx = baseRadiusPx + safePaddingPx

                            val isNearLeft = distLeft < boundaryReachPx
                            val isNearRight = distRight < boundaryReachPx
                            val isNearTop = distTop < boundaryReachPx
                            val isNearBottom = distBottom < boundaryReachPx

                            val isBoundaryConstrained = isNearLeft || isNearRight || isNearTop || isNearBottom

                            val isTopLeft = isNearTop && isNearLeft
                            val isTopRight = isNearTop && isNearRight
                            val isBottomLeft = isNearBottom && isNearLeft
                            val isBottomRight = isNearBottom && isNearRight

                            val (baseAngles, slotStep, itemRadiusPx) = remember(
                                total, isBoundaryConstrained, isTopLeft, isTopRight, isBottomLeft, isBottomRight,
                                isNearLeft, isNearRight, isNearTop, isNearBottom,
                                centerNodeX, centerNodeY, minSafeX, maxSafeX, minSafeY, maxSafeY,
                                baseRadiusPx, tileItemWidthPx, tileItemHeightPx
                            ) {
                                if (!isBoundaryConstrained || total <= 1) {
                                    val step = 2 * PI / total
                                    val startAngle = -PI / 2
                                    val list = (0 until total).map { idx ->
                                        startAngle + (idx * step)
                                    }
                                    Triple(list, step, baseRadiusPx)
                                } else {
                                    var startAngle: Double
                                    var endAngle: Double

                                    when {
                                        isTopLeft -> {
                                            // Top-Left Corner: open quadrant is Down-Right (angles ~ -10° to ~ 115°)
                                            val ratioY = ((minSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val minThetaY = asin(ratioY.toDouble())
                                            val ratioX = ((minSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val maxThetaX = acos(ratioX.toDouble())
                                            startAngle = max(-0.06 * PI, minThetaY)
                                            endAngle = min(0.66 * PI, maxThetaX)
                                        }
                                        isTopRight -> {
                                            // Top-Right Corner: open quadrant is Down-Left (angles ~ 65° to ~ 190°)
                                            val ratioY = ((minSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val maxThetaY = PI - asin(ratioY.toDouble())
                                            val ratioX = ((maxSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val minThetaX = acos(ratioX.toDouble())
                                            startAngle = max(0.34 * PI, minThetaX)
                                            endAngle = min(1.06 * PI, maxThetaY)
                                        }
                                        isBottomLeft -> {
                                            // Bottom-Left Corner: open quadrant is Up-Right (angles ~ -115° to ~ 10°)
                                            val ratioY = ((maxSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val maxThetaY = asin(ratioY.toDouble())
                                            val ratioX = ((minSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val minThetaX = -acos(ratioX.toDouble())
                                            startAngle = max(-0.66 * PI, minThetaX)
                                            endAngle = min(0.06 * PI, maxThetaY)
                                        }
                                        isBottomRight -> {
                                            // Bottom-Right Corner: open quadrant is Up-Left (angles ~ -190° to ~ -65°)
                                            val ratioY = ((maxSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val minThetaY = -PI - asin(ratioY.toDouble())
                                            val ratioX = ((maxSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val maxThetaX = -acos(ratioX.toDouble())
                                            startAngle = max(-1.06 * PI, minThetaY)
                                            endAngle = min(-0.34 * PI, maxThetaX)
                                        }
                                        isNearTop -> {
                                            // Top Edge: fan downward into bottom hemisphere
                                            val ratioY = ((minSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val minThetaY = asin(ratioY.toDouble())
                                            startAngle = max(0.10 * PI, minThetaY)
                                            endAngle = min(0.90 * PI, PI - minThetaY)
                                        }
                                        isNearBottom -> {
                                            // Bottom Edge: fan upward into top hemisphere
                                            val ratioY = ((maxSafeY - centerNodeY) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val maxThetaY = asin(ratioY.toDouble())
                                            startAngle = max(-0.90 * PI, -PI - maxThetaY)
                                            endAngle = min(-0.10 * PI, maxThetaY)
                                        }
                                        isNearLeft -> {
                                            // Left Edge: fan rightward into right hemisphere
                                            val ratioX = ((minSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val maxThetaX = acos(ratioX.toDouble())
                                            startAngle = max(-0.40 * PI, -maxThetaX)
                                            endAngle = min(0.40 * PI, maxThetaX)
                                        }
                                        else -> {
                                            // Right Edge: fan leftward into left hemisphere
                                            val ratioX = ((maxSafeX - centerNodeX) / baseRadiusPx).coerceIn(-1f, 1f)
                                            val minThetaX = acos(ratioX.toDouble())
                                            startAngle = max(0.60 * PI, minThetaX)
                                            endAngle = min(1.40 * PI, 2 * PI - minThetaX)
                                        }
                                    }

                                    // Ensure guaranteed forward sweep
                                    if (endAngle <= startAngle + 0.15) {
                                        val mid = (startAngle + endAngle) / 2.0
                                        startAngle = mid - (PI / 4.0)
                                        endAngle = mid + (PI / 4.0)
                                    }

                                    val span = endAngle - startAngle
                                    val step = if (total > 1) span / (total - 1) else 0.0

                                    // Vector Repulsion & Spacing: Ensure fixed padding gaps between adjacent tiles
                                    val minTileGapPx = with(density) { 8.dp.toPx() }
                                    val neededTileSpacingPx = max(tileItemWidthPx, tileItemHeightPx) + minTileGapPx
                                    val chordAtBaseR = (2 * baseRadiusPx * sin(step / 2.0)).toFloat().coerceAtLeast(1f)

                                    val resolvedRadiusPx = if (chordAtBaseR < neededTileSpacingPx && step > 0.02) {
                                        val expandedR = neededTileSpacingPx / (2 * sin(step / 2.0).toFloat())
                                        expandedR.coerceIn(baseRadiusPx, baseRadiusPx * 1.30f)
                                    } else {
                                        baseRadiusPx
                                    }

                                    val list = (0 until total).map { idx ->
                                        startAngle + (idx * step)
                                    }
                                    Triple(list, step, resolvedRadiusPx)
                                }
                            }

                            actionsList.forEachIndexed { actionIndex, action ->
                                val baseAngle = baseAngles.getOrElse(actionIndex) { (2 * PI * actionIndex / total) - (PI / 2) }
                                val isDragging = draggingTileIndex == actionIndex
                                val effectiveAngle = baseAngle + (if (isDragging) draggingTileAngularOffset else 0.0)

                                val rawScreenX = centerNodeX + (itemRadiusPx * cos(effectiveAngle)).toFloat() - (tileItemWidthPx / 2f)
                                val rawScreenY = centerNodeY + (itemRadiusPx * sin(effectiveAngle)).toFloat() - (tileItemHeightPx / 2f)

                                // Boundary clamping ensures tiles never touch or cross display edges
                                val safeScreenX = rawScreenX.coerceIn(marginPx, (widthPx - marginPx - tileItemWidthPx).coerceAtLeast(marginPx))
                                val safeScreenY = rawScreenY.coerceIn(topMarginPx, (heightPx - bottomMarginPx - tileItemHeightPx).coerceAtLeast(topMarginPx))

                                val tileOffsetDpX = with(density) { (safeScreenX - livePos.x).toDp() }
                                val tileOffsetDpY = with(density) { (safeScreenY - livePos.y).toDp() }

                                key(action.name) {
                                    Box(
                                        modifier = Modifier
                                            .offset(x = tileOffsetDpX, y = tileOffsetDpY)
                                            .zIndex(if (isDragging) 40f else 10f)
                                    ) {
                                        FreestyleTileRingItem(
                                            action = action,
                                            isEditMode = isEditMode,
                                            isDragging = isDragging,
                                            onDragStart = {
                                                draggingTileIndex = actionIndex
                                                draggingTileAngularOffset = 0.0
                                            },
                                            onDrag = { dragAmount ->
                                                val curAngle = baseAngle + draggingTileAngularOffset
                                                val tilePos = Offset(
                                                    x = centerNodeX + (itemRadiusPx * cos(curAngle)).toFloat(),
                                                    y = centerNodeY + (itemRadiusPx * sin(curAngle)).toFloat()
                                                )
                                                val nextPos = tilePos + dragAmount
                                                val nextAngle = atan2(
                                                    y = (nextPos.y - centerNodeY).toDouble(),
                                                    x = (nextPos.x - centerNodeX).toDouble()
                                                )
                                                var delta = nextAngle - curAngle
                                                while (delta > PI) delta -= 2 * PI
                                                while (delta < -PI) delta += 2 * PI
                                                val newOffset = draggingTileAngularOffset + delta
                                                draggingTileAngularOffset = newOffset

                                                if (newOffset > slotStep * 0.5) {
                                                    val currentIdx = draggingTileIndex ?: actionIndex
                                                    if (!isBoundaryConstrained || currentIdx < total - 1) {
                                                        val targetIdx = (currentIdx + 1) % total
                                                        val mutable = actionsList.toMutableList()
                                                        val temp = mutable[currentIdx]
                                                        mutable[currentIdx] = mutable[targetIdx]
                                                        mutable[targetIdx] = temp
                                                        actionsList = mutable
                                                        draggingTileIndex = targetIdx
                                                        draggingTileAngularOffset = newOffset - slotStep
                                                        val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                                        syncOrderToAllDevices(encoded)
                                                    }
                                                } else if (newOffset < -slotStep * 0.5) {
                                                    val currentIdx = draggingTileIndex ?: actionIndex
                                                    if (!isBoundaryConstrained || currentIdx > 0) {
                                                        val targetIdx = if (currentIdx > 0) currentIdx - 1 else total - 1
                                                        val mutable = actionsList.toMutableList()
                                                        val temp = mutable[currentIdx]
                                                        mutable[currentIdx] = mutable[targetIdx]
                                                        mutable[targetIdx] = temp
                                                        actionsList = mutable
                                                        draggingTileIndex = targetIdx
                                                        draggingTileAngularOffset = newOffset + slotStep
                                                        val encoded = FreestyleMenuAction.encodeOrder(mutable)
                                                        syncOrderToAllDevices(encoded)
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                draggingTileIndex = null
                                                draggingTileAngularOffset = 0.0
                                            },
                                            onClick = {
                                                if (!isEditMode) {
                                                    activeDeviceId = null
                                                    when (action) {
                                                        FreestyleMenuAction.BROWSE -> handleBrowseDevice(row.deviceId, row.deviceName)
                                                        FreestyleMenuAction.CLIPBOARD -> onSendClipboardDevice(row.deviceId)
                                                        FreestyleMenuAction.INFO -> onDeviceDetails(row.deviceId)
                                                        FreestyleMenuAction.RENAME -> onRenameDevice(row.deviceId, row.deviceName)
                                                        FreestyleMenuAction.REMOVE -> onRemoveDevice(row.deviceId, row.deviceName)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


        // Floating Movable & Resizable In-App File Browser Window (No scrim covering canvas)
        if (showFilesWindow) {
            val minWidthDp = 320.dp
            val minHeightDp = 340.dp
            val maxWidthDp = (maxWidth - 32.dp).coerceAtLeast(minWidthDp)
            val maxHeightDp = (maxHeight - 32.dp).coerceAtLeast(minHeightDp)

            val currentWindowWidthPx = with(density) { windowWidthDp.toPx() }
            val currentWindowHeightPx = with(density) { windowHeightDp.toPx() }

            Surface(
                modifier = Modifier
                    .offset { IntOffset(windowOffsetPx.x.roundToInt(), windowOffsetPx.y.roundToInt()) }
                    .size(width = windowWidthDp, height = windowHeightDp)
                    .zIndex(150f)
                    .shadow(24.dp, RoundedCornerShape(18.dp))
                    .border(BorderStroke(1.5.dp, Color(0xFF64B5F6).copy(alpha = 0.85f)), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xF8081420)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Draggable Titlebar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0D2235))
                                .pointerInput(widthPx, heightPx, currentWindowWidthPx, currentWindowHeightPx) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val nextX = (windowOffsetPx.x + dragAmount.x)
                                            .coerceIn(marginPx, widthPx - marginPx - currentWindowWidthPx)
                                        val nextY = (windowOffsetPx.y + dragAmount.y)
                                            .coerceIn(topMarginPx, heightPx - bottomMarginPx - currentWindowHeightPx)
                                        windowOffsetPx = Offset(nextX, nextY)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (browsingTarget != null || isResolvingBrowse) {
                                    IconButton(
                                        onClick = {
                                            browsingTarget = null
                                            isResolvingBrowse = false
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = Color(0xFF64B5F6),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = Color(0xFF64B5F6),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                val title = if (isResolvingBrowse) {
                                    "Connecting to ${resolvingDeviceName ?: "Device"}..."
                                } else if (browsingTarget != null) {
                                    if (browsingTarget is BrowseTarget.Local) stringRes("local_files")
                                    else browsingTarget?.displayName ?: "Files"
                                } else {
                                    "File Navigation"
                                }
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.OpenWith,
                                    contentDescription = "Drag Window",
                                    tint = Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        showFilesWindow = false
                                        browsingTarget = null
                                        isResolvingBrowse = false
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Close",
                                        tint = Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Window Body: Resolving Indicator OR In-Window File Explorer OR Root Selection View
                        if (isResolvingBrowse) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(36.dp),
                                        color = Color(0xFF64B5F6),
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Connecting to ${resolvingDeviceName ?: "device"}...",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Loading remote file system...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        } else if (browsingTarget != null) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                FileExplorerScreen(
                                    target = browsingTarget!!,
                                    onBack = { browsingTarget = null },
                                    embeddedInCompactShell = true,
                                    titleOverride = if (browsingTarget is BrowseTarget.Local) stringRes("local_files") else null
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                // Category 1: Local Files
                                Text(
                                    text = "LOCAL FILES",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = Color(0xFF64B5F6).copy(alpha = 0.85f)
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (thisDeviceTarget != null) {
                                                browsingTarget = thisDeviceTarget
                                            } else {
                                                onOpenLocalFiles?.invoke()
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0x3364B5F6),
                                    border = BorderStroke(1.dp, Color(0x5564B5F6))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FolderOpen,
                                            contentDescription = null,
                                            tint = Color(0xFF00E676),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringRes("local_files"),
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = Color.White
                                            )
                                            Text(
                                                text = "Browse storage on this device",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White.copy(alpha = 0.65f)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Category 2: Remote Devices
                                Text(
                                    text = "REMOTE DEVICES",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = Color(0xFF64B5F6).copy(alpha = 0.85f)
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                if (deviceRows.isEmpty()) {
                                    Text(
                                        text = "No paired remote devices found",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                } else {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        deviceRows.forEach { row ->
                                            val statusColor = if (row.online) Color(0xFF00E676) else Color(0xFFFFB300)
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        isResolvingBrowse = true
                                                        resolvingDeviceName = row.deviceName
                                                        if (onResolveBrowseTarget != null) {
                                                            onResolveBrowseTarget(row.deviceId) { target ->
                                                                browsingTarget = target
                                                                isResolvingBrowse = false
                                                            }
                                                        } else {
                                                            isResolvingBrowse = false
                                                            onOpenDevice(row.deviceId)
                                                        }
                                                    },
                                                shape = RoundedCornerShape(12.dp),
                                                color = Color(0x221E3A5F),
                                                border = BorderStroke(1.dp, Color(0x4464B5F6))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val iconStyle = LocalThemeIconStyle.current
                                                    DeviceEntryIcon(
                                                        row = row,
                                                        modifier = if (iconStyle == ThemeIconStyle.STANDARD) Modifier.size(24.dp) else Modifier.size(36.dp),
                                                        tint = Color.White
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = row.deviceName,
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                            color = Color.White,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = if (row.online) "Online" else "Offline",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                            color = statusColor
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = Icons.Filled.FolderOpen,
                                                        contentDescription = null,
                                                        tint = Color(0xFF64B5F6),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Resize Grip on Bottom-Right Corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(36.dp)
                            .pointerInput(density) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val deltaWDp = with(density) { dragAmount.x.toDp() }
                                    val deltaHDp = with(density) { dragAmount.y.toDp() }
                                    windowWidthDp = (windowWidthDp + deltaWDp).coerceIn(minWidthDp, maxWidthDp)
                                    windowHeightDp = (windowHeightDp + deltaHDp).coerceIn(minHeightDp, maxHeightDp)
                                }
                            }
                            .padding(6.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenWith,
                            contentDescription = "Resize Window",
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // One-time Edit Mode Tutorial Dialog
        if (showTutorialDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(300f)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 380.dp)
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFA0B1B2B),
                    border = BorderStroke(1.5.dp, Color(0xFF64B5F6)),
                    shadowElevation = 24.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Customize Your Layout",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You can drag and rearrange the Options button and device buttons across the canvas. In Edit mode, you can also reorder action menus inside device cards and Options.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = {
                                showTutorialDialog = false
                                FileApexServices.settings.setFreestyleEditTutorialShown(true)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1976D2),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Got it", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreestyleHorizontalCardItem(
    action: FreestyleMenuAction,
    isEditMode: Boolean,
    isDragging: Boolean = false,
    dragOffsetX: Float = 0f,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(50.dp)
            .height(if (isEditMode) 58.dp else 52.dp)
            .zIndex(if (isDragging) 20f else 1f)
            .graphicsLayer { translationX = dragOffsetX }
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !isEditMode, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isDragging) Color(0x6664B5F6) else Color(0x3364B5F6)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (isEditMode) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier
                        .size(16.dp)
                        .pointerInput(action) {
                            detectDragGestures(
                                onDragStart = { onDragStart?.invoke() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag?.invoke(dragAmount.x)
                                },
                                onDragEnd = { onDragEnd?.invoke() },
                                onDragCancel = { onDragEnd?.invoke() }
                            )
                        }
                )
            }
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6),
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = action.shortLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FreestyleMenuRowItem(
    action: FreestyleMenuAction,
    isEditMode: Boolean,
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .zIndex(if (isDragging) 20f else 1f)
            .graphicsLayer { translationY = dragOffsetY }
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !isEditMode, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isDragging) Color(0x6664B5F6) else Color(0x3364B5F6)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isEditMode) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier
                        .size(18.dp)
                        .pointerInput(action) {
                            detectDragGestures(
                                onDragStart = { onDragStart?.invoke() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag?.invoke(dragAmount.y)
                                },
                                onDragEnd = { onDragEnd?.invoke() },
                                onDragCancel = { onDragEnd?.invoke() }
                            )
                        }
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6),
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringRes(action.fullLabelRes),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isEditMode) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FreestyleTileRingItem(
    action: FreestyleMenuAction,
    isEditMode: Boolean,
    isDragging: Boolean = false,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(46.dp)
            .height(50.dp)
            .shadow(if (isDragging) 12.dp else 6.dp, RoundedCornerShape(11.dp))
            .clip(RoundedCornerShape(11.dp))
            .border(
                BorderStroke(
                    width = if (isDragging) 2.dp else (if (isEditMode) 1.5.dp else 1.dp),
                    color = if (isDragging) Color(0xFF00E5FF) else (if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6).copy(alpha = 0.85f))
                ),
                RoundedCornerShape(11.dp)
            )
            .clickable(enabled = !isEditMode, onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        color = if (isDragging) Color(0xFA143754) else Color(0xFA0B1C2A)
    ) {
        if (isEditMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(action) {
                        detectDragGestures(
                            onDragStart = { onDragStart?.invoke() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag?.invoke(dragAmount)
                            },
                            onDragEnd = { onDragEnd?.invoke() },
                            onDragCancel = { onDragEnd?.invoke() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.shortLabel,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = action.shortLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 10.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(9.dp)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 3.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.shortLabel,
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = action.shortLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 10.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
