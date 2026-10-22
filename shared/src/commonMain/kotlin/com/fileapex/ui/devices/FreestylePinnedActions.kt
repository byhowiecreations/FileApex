package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

@Composable
internal fun FreestyleScope.FreestylePinnedActions() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        val hasFriendsNow = com.fileapex.friends.ui.rememberFriendsCount() > 0
        // Render Pinned Canvas Action Buttons
        currentPinnedActionsMap.forEach { (actionKey, offsetFraction) ->
            val optAction = FreestyleOptionAction.entries.firstOrNull { it.key == actionKey } ?: return@forEach
            if (optAction == FreestyleOptionAction.GROUPS && !hasFriendsNow) return@forEach

            val minDeviceX = marginPx
            val maxDeviceX = (widthPx - marginPx - deviceButtonSizePx).coerceAtLeast(marginPx)
            val minDeviceY = topMarginPx
            val maxDeviceY = (heightPx - bottomMarginPx - deviceButtonSizePx - with(density) { 36.dp.toPx() }).coerceAtLeast(topMarginPx)

            val livePos = livePinnedPositions.getOrPut(actionKey) {
                val initialX = offsetFraction.first * widthPx - (deviceButtonSizePx / 2f)
                val initialY = offsetFraction.second * heightPx - (deviceButtonSizePx / 2f)
                Offset(
                    initialX.coerceIn(minDeviceX, maxDeviceX),
                    initialY.coerceIn(minDeviceY, maxDeviceY)
                )
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(livePos.x.roundToInt(), livePos.y.roundToInt()) }
                    .zIndex(if (isEditMode) 60f else 30f)
            ) {
                Box(
                    modifier = Modifier
                        .size(deviceButtonSizeDp)
                        .pointerInput(actionKey, freestyleMode, widthPx, heightPx) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val cur = livePinnedPositions[actionKey] ?: livePos
                                    val nextX = (cur.x + dragAmount.x).coerceIn(minDeviceX, maxDeviceX)
                                    val nextY = (cur.y + dragAmount.y).coerceIn(minDeviceY, maxDeviceY)
                                    livePinnedPositions[actionKey] = Offset(nextX, nextY)
                                },
                                onDragEnd = {
                                    val finalPos = livePinnedPositions[actionKey] ?: livePos
                                    val centerX = finalPos.x + (deviceButtonSizePx / 2f)
                                    val centerY = finalPos.y + (deviceButtonSizePx / 2f)
                                    val fracX = (centerX / widthPx).coerceIn(0f, 1f)
                                    val fracY = (centerY / heightPx).coerceIn(0f, 1f)
                                    FileApexServices.settings.setFreestylePinnedActionOffset(freestyleMode, actionKey, fracX, fracY)
                                }
                            )
                        }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .clickable(enabled = !isEditMode) {
                                when (optAction) {
                                    FreestyleOptionAction.FILES -> showFilesWindow = true
                                    FreestyleOptionAction.ADD_DEVICE -> onGenerateQr()
                                    FreestyleOptionAction.JOIN_DEVICE -> onJoinDevice()
                                    FreestyleOptionAction.FRIENDS, FreestyleOptionAction.GROUPS -> com.fileapex.friends.ui.FriendsUi.open()
                                    FreestyleOptionAction.SEND_CLIPBOARD -> onSendClipboard?.invoke()
                                    FreestyleOptionAction.CHECK_BATTERIES -> onCheckBatteries?.invoke()
                                    FreestyleOptionAction.SETTINGS -> onOpenSettings?.invoke()
                                }
                            },
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(
                            width = if (isEditMode) 2.5.dp else 1.5.dp,
                            color = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6)
                        ),
                        shadowElevation = if (isEditMode) 12.dp else 8.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xDD0D3859),
                                            Color(0xEE050B12)
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val iconStyle = LocalThemeIconStyle.current
                            val buttonBorderWidth = if (isEditMode) 2.5.dp else 1.5.dp
                            FreestyleOptionIcon(
                                action = optAction,
                                iconStyle = iconStyle,
                                modifier = if (iconStyle == ThemeIconStyle.STANDARD) {
                                    Modifier.size(32.dp)
                                } else {
                                    Modifier.fillMaxSize().padding(buttonBorderWidth)
                                },
                                tint = Color.White
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
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Delete / Unpin button in edit mode on top-right
                    if (isEditMode) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE53935))
                                .border(BorderStroke(1.5.dp, Color.White), CircleShape)
                                .clickable {
                                    livePinnedPositions.remove(actionKey)
                                    FileApexServices.settings.removeFreestylePinnedAction(freestyleMode, actionKey)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove from screen",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Action Label placed directly below the button
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
                        text = optAction.label(),
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
            }
        }

    } } } }
}
