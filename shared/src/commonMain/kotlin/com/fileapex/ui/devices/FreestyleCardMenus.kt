package com.fileapex.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.presentation.DeviceListRow

@Composable
internal fun FreestyleScope.FreestyleHorizontalCardMenu(
    row: DeviceListRow,
    livePos: Offset,
    actionsListState: MutableState<List<FreestyleMenuAction>>
) {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        var actionsList by actionsListState
        // Layout 1: Horizontal Card extending horizontally from device button
        // 5 tiles of 50 dp with 3.5 dp between them and 8 dp padding; fewer tiles (a friend has 4) shrink the bar, the gaps stay the same.
        val cardWidthDp = (16f + actionsList.size * 50f + (actionsList.size - 1).coerceAtLeast(0) * 3.5f).dp
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
    } } } }
}

@Composable
internal fun FreestyleScope.FreestyleVerticalCardMenu(
    row: DeviceListRow,
    livePos: Offset,
    actionsListState: MutableState<List<FreestyleMenuAction>>
) {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        var actionsList by actionsListState
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
    } } } }
}
