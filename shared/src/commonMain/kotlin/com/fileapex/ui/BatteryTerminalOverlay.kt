package com.fileapex.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun BatteryTerminalOverlay(
    logLines: List<String>,
    isComplete: Boolean,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    var userWidthDp by remember { mutableStateOf(520.dp) }
    var userHeightDp by remember { mutableStateOf(360.dp) }
    var isMaximized by remember { mutableStateOf(false) }

    val activeWidth = if (isMaximized) 820.dp else userWidthDp
    val activeHeight = if (isMaximized) 560.dp else userHeightDp

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.lastIndex)
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        )
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .width(activeWidth)
                .height(activeHeight)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .border(BorderStroke(1.5.dp, Color(0xFF00FF66).copy(alpha = 0.85f)), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xF8080E0A)
        ) {
            Column(modifier = Modifier.width(activeWidth).height(activeHeight)) {
                // Retro DOS / Linux Terminal Titlebar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1E12))
                        .border(BorderStroke(0.5.dp, Color(0xFF00FF66).copy(alpha = 0.4f)))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF00FF66))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "root@fileapex:~# batstat (tty1)",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF00FF66)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Maximize / Restore Toggle
                        IconButton(
                            onClick = { isMaximized = !isMaximized },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text(
                                text = if (isMaximized) "⧉" else "□",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FF66).copy(alpha = 0.8f)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close Terminal",
                                tint = Color(0xFFFF5555),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Terminal Output Body
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    items(logLines) { line ->
                        val linePrefix = line.substringBefore(' ', "")
                        val textColor = when {
                            linePrefix == "[LOCAL]" -> Color(0xFF00E5FF)
                            linePrefix == "[ONLINE]" -> Color(0xFF33FF66)
                            linePrefix == "[OFFLINE]" -> Color(0xFFFF6E6E)
                            linePrefix == "[INIT]" || linePrefix == "[DONE]" -> Color(0xFFFFD54F)
                            line.startsWith("fileapex@") -> Color(0xFF81C784)
                            else -> Color(0xFFB9F6CA)
                        }
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "fileapex@node:~$ ",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = Color(0xFF81C784)
                            )
                            Text(
                                text = if (cursorAlpha > 0.5f) "█" else " ",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = Color(0xFF00FF66)
                            )
                        }
                    }
                }

                // Terminal Bottom Command Prompt Bar + Resize Handle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B170F))
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isComplete) "SESSION COMPLETE" else "POLLING NODES...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isComplete) Color(0xFF00FF66) else Color(0xFFFFD54F)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[ESC / TAP OUTSIDE TO EXIT]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Interactive Resize Handle
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .pointerInput(density) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        isMaximized = false
                                        val deltaWDp = with(density) { dragAmount.x.toDp() }
                                        val deltaHDp = with(density) { dragAmount.y.toDp() }
                                        userWidthDp = (userWidthDp + deltaWDp).coerceIn(340.dp, 1000.dp)
                                        userHeightDp = (userHeightDp + deltaHDp).coerceIn(240.dp, 750.dp)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "◢",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF00FF66).copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}
