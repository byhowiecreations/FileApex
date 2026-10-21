package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import kotlin.math.roundToInt

@Composable
internal fun FreestyleScope.FreestyleOptionsButton() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
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

            if (optionsMenuExpanded) {
                FreestyleOptionsDropdown()
            }
        }

    } } } }
}

@Composable
internal fun FreestyleScope.FreestyleOptionGhost() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        // Ghost button while dragging an option outside the menu onto the canvas
        if (optionsMenuExpanded && draggingOptIndex != null && isDraggingOut && dragCanvasPos != null) {
            val currentIdx = draggingOptIndex!!
            if (currentIdx in visibleOptions.indices) {
                val ghostAction = visibleOptions[currentIdx]
                val ghostX = (dragCanvasPos!!.x - deviceButtonSizePx / 2f)
                    .coerceIn(marginPx, widthPx - marginPx - deviceButtonSizePx)
                val ghostY = (dragCanvasPos!!.y - deviceButtonSizePx / 2f)
                    .coerceIn(topMarginPx, heightPx - bottomMarginPx - deviceButtonSizePx)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(ghostX.roundToInt(), ghostY.roundToInt()) }
                        .size(deviceButtonSizeDp)
                        .zIndex(400f)
                        .alpha(0.92f)
                        .shadow(16.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xDD0D3859),
                                    Color(0xEE050B12)
                                )
                            ),
                            CircleShape
                        )
                        .border(BorderStroke(2.5.dp, Color(0xFFFFB300)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val iconStyle = LocalThemeIconStyle.current
                    FreestyleOptionIcon(
                        action = ghostAction,
                        iconStyle = iconStyle,
                        modifier = if (iconStyle == ThemeIconStyle.STANDARD) {
                            Modifier.size(32.dp)
                        } else {
                            Modifier.fillMaxSize().padding(2.5.dp)
                        },
                        tint = Color.White
                    )
                }
            }
        }
    } } } }
}


@Composable
internal fun FreestyleScope.FreestyleOptionsDropdown() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
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
                if (visibleOptions.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "All shortcuts placed on screen",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            ),
                            color = Color(0xFF64B5F6),
                            textAlign = TextAlign.Center
                        )
                        if (isEditMode) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    livePinnedPositions.clear()
                                    FileApexServices.settings.resetFreestylePinnedActions(freestyleMode)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Return All to Menu",
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                } else {
                    visibleOptions.forEachIndexed { optIndex, optAction ->
                        val isAvailable = when (optAction) {
                            FreestyleOptionAction.SEND_CLIPBOARD -> onSendClipboard != null
                            FreestyleOptionAction.CHECK_BATTERIES -> onCheckBatteries != null
                            FreestyleOptionAction.SETTINGS -> onOpenSettings != null
                            else -> true
                        }
                        if (!isAvailable && !isEditMode) return@forEachIndexed

                        key(optAction.name) {
                            val isDragging = draggingOptIndex == optIndex
                            val zIndexVal = if (isDragging) 50f else 1f

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(optRowHeightDp)
                                    .zIndex(zIndexVal)
                                    .pointerInput(optAction, dropdownWidthPx, isEditMode) {
                                        detectDragGestures(
                                            onDragStart = { startDragAction(optIndex) },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                onDragDeltaAction(optIndex, dragAmount)
                                            },
                                            onDragEnd = { endDragAction() },
                                            onDragCancel = { endDragAction() }
                                        )
                                    }
                            ) {
                                Surface(
                                    color = if (isDragging && !isDraggingOut) Color(0x5564B5F6) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            if (isDragging) {
                                                if (isDraggingOut) {
                                                    alpha = 0.35f
                                                } else {
                                                    translationY = draggingOptOffsetY.coerceIn(-optRowHeightPx * 0.8f, optRowHeightPx * 0.8f)
                                                }
                                            }
                                        }
                                        .clickable(enabled = !isDraggingOut) {
                                            if (!isEditMode) {
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
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = if (isEditMode) 6.dp else 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isEditMode) {
                                            Icon(
                                                imageVector = Icons.Filled.DragHandle,
                                                contentDescription = stringRes("reorder_devices"),
                                                tint = Color(0xFFFFB300),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            IconButton(
                                                onClick = {
                                                    val deltaX = with(density) { (if (liveOptionsPos.x > widthPx / 2) -90.dp else 90.dp).toPx() }
                                                    val deltaY = with(density) { -80.dp.toPx() }
                                                    val initialX = ((liveOptionsPos.x + deltaX) / widthPx).coerceIn(0.1f, 0.9f)
                                                    val initialY = ((liveOptionsPos.y + deltaY) / heightPx).coerceIn(0.1f, 0.85f)
                                                    FileApexServices.settings.setFreestylePinnedActionOffset(freestyleMode, optAction.key, initialX, initialY)
                                                },
                                                modifier = Modifier.size(22.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.AddCircleOutline,
                                                    contentDescription = "Place on screen",
                                                    tint = Color(0xFFFFB300),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Icon(
                                            imageVector = optAction.icon,
                                            contentDescription = null,
                                            tint = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = optAction.label(),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isEditMode) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = 12.5.sp
                                            ),
                                            color = Color.White,
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
    } } } }
}
