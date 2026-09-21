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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun BatteryTerminalOverlay(
    items: List<com.fileapex.presentation.BatteryStatusItem> = emptyList(),
    logLines: List<String> = emptyList(),
    isComplete: Boolean,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current

    var userWidthDp by remember { mutableStateOf(520.dp) }
    var userHeightDp by remember { mutableStateOf(360.dp) }
    var isMaximized by remember { mutableStateOf(false) }

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxAvailableWidth = maxWidth
                val maxAvailableHeight = maxHeight
                val resolvedWidth = if (isMaximized) maxAvailableWidth else userWidthDp.coerceAtMost(maxAvailableWidth)
                val resolvedHeight = if (isMaximized) maxAvailableHeight else userHeightDp.coerceAtMost(maxAvailableHeight)

                val maxNameLength = remember(items) {
                    items.maxOfOrNull { it.deviceName.length } ?: 10
                }
                val nameColWidthDp = remember(maxNameLength) {
                    (maxNameLength * 6.85f).dp
                }

                val hasLowPower = remember(items) { items.any { it.lowPowerMode } }
                val requiredFullWidth = remember(nameColWidthDp, hasLowPower) {
                    28.dp + 72.dp + 4.dp + nameColWidthDp + 14.dp + 27.dp + 14.dp + 88.dp + (if (hasLowPower) 75.dp else 0.dp)
                }
                val isCompact = resolvedWidth < requiredFullWidth
                val isUltraNarrow = resolvedWidth < 280.dp

                LaunchedEffect(items.size, logLines.size, isComplete) {
                    val totalCount = if (items.isNotEmpty()) {
                        items.size + 4
                    } else {
                        logLines.size + 1
                    }
                    if (totalCount > 0) {
                        listState.animateScrollToItem(totalCount)
                    }
                }

                Surface(
                    modifier = Modifier
                        .width(resolvedWidth)
                        .height(resolvedHeight)
                        .shadow(24.dp, RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.5.dp, Color(0xFF00FF66).copy(alpha = 0.85f)), RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xF8080E0A)
                ) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                                text = if (isCompact) "root@fileapex:~# batstat (tty1)" else "root@fileapex:~# batstat --all-devices (tty1)",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFF00FF66)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
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

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .horizontalScroll(horizontalScrollState)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (items.isEmpty()) {
                            items(logLines) { line ->
                                Text(
                                    text = formatTerminalLine(line),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp,
                                    softWrap = false
                                )
                            }
                        } else {
                            item {
                                Text(
                                    text = formatTerminalLine("FileApex Linux v${com.fileapex.update.currentAppVersionName()} (tty1)"),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp,
                                    softWrap = false
                                )
                                Text(
                                    text = formatTerminalLine("login: fileapex"),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp,
                                    softWrap = false
                                )
                                Text(
                                    text = formatTerminalLine("root@fileapex:~# ${if (isCompact) "batstat" else "batstat --all-devices"}"),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp,
                                    softWrap = false
                                )
                                Text(
                                    text = formatTerminalLine("[INIT] Polling battery telemetry across cluster..."),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp,
                                    softWrap = false
                                )
                                Text(
                                    text = formatTerminalLine("--------------------------------------------------------------------------------"),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp,
                                    softWrap = false
                                )
                            }

                            if (isUltraNarrow) {
                                items(items, key = { it.deviceId }) { item ->
                                    BatteryStackedItemRow(item, isCompact = isCompact)
                                }
                            } else {
                                items(items, key = { it.deviceId }) { item ->
                                    BatteryWideItemRow(item, nameColWidthDp, isCompact = isCompact)
                                }
                            }

                            if (isComplete) {
                                item {
                                    Text(
                                        text = formatTerminalLine("--------------------------------------------------------------------------------"),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        lineHeight = 15.sp,
                                        softWrap = false
                                    )
                                    val (onlineItems, offlineItems) = items.partition { it.online }
                                    Text(
                                        text = formatTerminalLine("[DONE] Query complete: ${onlineItems.size} online, ${offlineItems.size} offline."),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        lineHeight = 15.sp,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.height(16.dp)
                            ) {
                                Text(
                                    text = "root@fileapex:~# ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF00FF66)
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
                                            userWidthDp = (userWidthDp + deltaWDp).coerceIn(300.dp, 1000.dp)
                                            userHeightDp = (userHeightDp + deltaHDp).coerceIn(240.dp, 800.dp)
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
}
}

@Composable
private fun BatteryStackedItemRow(
    item: com.fileapex.presentation.BatteryStatusItem,
    isCompact: Boolean
) {
    val chargingStr = item.chargingState.trim().lowercase()
    val isCharging = (chargingStr == "ac" ||
        chargingStr == "usb" ||
        chargingStr == "wireless" ||
        chargingStr.contains("charging")) &&
        !chargingStr.contains("discharging")

    val percentStr = item.levelPercent?.let { "$it%" } ?: "---"
    val stateTag = when {
        !item.online -> "[OFFLINE]"
        isCharging -> {
            val type = item.chargingState.ifBlank { "AC" }.uppercase()
            "⚡ [$type]"
        }
        item.levelPercent != null -> if (isCompact) "↓" else "[DISCHARGING]"
        else -> "[TIMEOUT]"
    }

    val batteryColor = getBatteryColor(item.levelPercent, isCharging, item.online)
    val stateColor = when {
        !item.online -> Color(0xFFFF6E6E)
        isCharging -> Color(0xFF00E5FF)
        else -> batteryColor
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(16.dp)
        ) {
            Text(
                text = if (item.online) "[ONLINE]" else "[OFFLINE]",
                color = if (item.online) Color(0xFF33FF66) else Color(0xFFFF6E6E),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.width(72.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = item.deviceName,
                color = Color(0xFFE8F5E9),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = 1
            )
            Text(
                text = " │ ",
                color = Color(0xFF00FF66).copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
            Text(
                text = percentStr,
                color = batteryColor,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(16.dp)
        ) {
            Text(
                text = "  └─ ",
                color = Color(0xFF00FF66).copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
            Text(
                text = stateTag,
                color = stateColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
            if (item.lowPowerMode) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "[LOW POWER]",
                    color = Color(0xFFFFB300),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun BatteryWideItemRow(
    item: com.fileapex.presentation.BatteryStatusItem,
    nameColWidthDp: Dp,
    isCompact: Boolean
) {
    val chargingStr = item.chargingState.trim().lowercase()
    val isCharging = (chargingStr == "ac" ||
        chargingStr == "usb" ||
        chargingStr == "wireless" ||
        chargingStr.contains("charging")) &&
        !chargingStr.contains("discharging")

    val percentStr = item.levelPercent?.let { "$it%" } ?: "---"
    val stateTag = when {
        !item.online -> "[OFFLINE]"
        isCharging -> {
            val type = item.chargingState.ifBlank { "AC" }.uppercase()
            "⚡ [$type]"
        }
        item.levelPercent != null -> if (isCompact) "↓" else "[DISCHARGING]"
        else -> "[TIMEOUT]"
    }

    val batteryColor = getBatteryColor(item.levelPercent, isCharging, item.online)
    val stateColor = when {
        !item.online -> Color(0xFFFF6E6E)
        isCharging -> Color(0xFF00E5FF)
        else -> batteryColor
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(16.dp)
    ) {
        Text(
            text = if (item.online) "[ONLINE]" else "[OFFLINE]",
            color = if (item.online) Color(0xFF33FF66) else Color(0xFFFF6E6E),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.width(72.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = item.deviceName,
            color = Color(0xFFE8F5E9),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            modifier = Modifier.width(nameColWidthDp)
        )

        Text(
            text = " │ ",
            color = Color(0xFF00FF66).copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )

        Text(
            text = percentStr,
            color = batteryColor,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(27.dp)
        )

        Text(
            text = " │ ",
            color = Color(0xFF00FF66).copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )

        Text(
            text = stateTag,
            color = stateColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )

        if (item.lowPowerMode) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "[LOW POWER]",
                color = Color(0xFFFFB300),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
        }
    }
}

private fun getBatteryColor(percent: Int?, isCharging: Boolean, isOnline: Boolean): Color {
    return when {
        !isOnline -> Color(0xFFFF6E6E)
        isCharging -> Color(0xFF00E5FF) // Teal / Cyan (Charging)
        percent != null -> when (percent) {
            in 40..100 -> Color(0xFF00E676) // Green (40% – 100%)
            in 21..39 -> Color(0xFFFF9800)  // Orange (21% – 39%)
            else -> Color(0xFFFF5252)       // Red (1% – 20%)
        }
        else -> Color(0xFFB9F6CA)
    }
}

private fun formatTerminalLine(line: String): AnnotatedString {
    return buildAnnotatedString {
        when {
            line.contains("└─") -> {
                val treePrefix = line.substringBefore("└─") + "└─ "
                val afterTree = line.substringAfter("└─ ").trim()
                withStyle(SpanStyle(color = Color(0xFF00FF66).copy(alpha = 0.55f))) {
                    append(treePrefix)
                }
                val parts = afterTree.split("│")
                val percentPart = parts[0].trim()
                val statePart = if (parts.size > 1) parts[1].trim() else ""

                val isCharging = statePart.contains("[AC]", ignoreCase = true) ||
                    statePart.contains("[USB]", ignoreCase = true) ||
                    statePart.contains("[WIRELESS]", ignoreCase = true) ||
                    statePart.contains("[FULL]", ignoreCase = true) ||
                    (statePart.contains("CHARGING", ignoreCase = true) && !statePart.contains("DISCHARGING", ignoreCase = true))

                val percentNumber = Regex("""\b(\d+)%""").find(percentPart)?.groupValues?.get(1)?.toIntOrNull()

                val batteryColor = when {
                    isCharging -> Color(0xFF00E5FF) // Teal / Cyan (Charging)
                    percentNumber != null -> when (percentNumber) {
                        in 40..100 -> Color(0xFF00E676) // Green (40% – 100%)
                        in 21..39 -> Color(0xFFFF9800)  // Orange (21% – 39%)
                        else -> Color(0xFFFF5252)       // Red (1% – 20%)
                    }
                    statePart.contains("OFFLINE", ignoreCase = true) -> Color(0xFFFF6E6E)
                    else -> Color(0xFFB9F6CA)
                }

                withStyle(SpanStyle(color = batteryColor, fontWeight = FontWeight.SemiBold)) {
                    append(percentPart)
                }
                withStyle(SpanStyle(color = Color(0xFF00FF66).copy(alpha = 0.45f))) {
                    append(" │ ")
                }

                if (statePart.contains("[LOW POWER]")) {
                    val beforeLowPower = statePart.substringBefore("[LOW POWER]").trim()
                    val stateColor = if (isCharging) Color(0xFF00E5FF) else batteryColor
                    withStyle(SpanStyle(color = stateColor)) {
                        append(beforeLowPower)
                        append(" ")
                    }
                    withStyle(SpanStyle(color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)) {
                        append("[LOW POWER]")
                    }
                } else {
                    val stateColor = if (isCharging) Color(0xFF00E5FF) else batteryColor
                    withStyle(SpanStyle(color = stateColor)) {
                        append(statePart)
                    }
                }
            }

            line.contains("│") -> {
                val parts = line.split("│")
                val col0 = parts[0]
                val col1 = parts.getOrNull(1).orEmpty()
                val col2 = if (parts.size > 2) parts.subList(2, parts.size).joinToString("│") else ""

                if (col0.startsWith("[")) {
                    val tag = col0.substringBefore("]") + "]"
                    val rest = col0.substringAfter("]")
                    val tagColor = when {
                        tag.contains("ONLINE", ignoreCase = true) -> Color(0xFF33FF66)
                        tag.contains("LOCAL", ignoreCase = true) -> Color(0xFF00E5FF)
                        tag.contains("OFFLINE", ignoreCase = true) -> Color(0xFFFF6E6E)
                        else -> Color(0xFF81C784)
                    }
                    withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
                        append(tag)
                    }
                    withStyle(SpanStyle(color = Color(0xFFE8F5E9))) {
                        append(rest)
                    }
                } else {
                    withStyle(SpanStyle(color = Color(0xFFE8F5E9))) {
                        append(col0)
                    }
                }

                withStyle(SpanStyle(color = Color(0xFF00FF66).copy(alpha = 0.45f))) {
                    append("│")
                }

                val isCharging = col2.contains("[AC]", ignoreCase = true) ||
                    col2.contains("[USB]", ignoreCase = true) ||
                    col2.contains("[WIRELESS]", ignoreCase = true) ||
                    col2.contains("[FULL]", ignoreCase = true) ||
                    (col2.contains("CHARGING", ignoreCase = true) && !col2.contains("DISCHARGING", ignoreCase = true))

                val percentNumber = Regex("""\b(\d+)%""").find(col1)?.groupValues?.get(1)?.toIntOrNull()

                val batteryColor = when {
                    isCharging -> Color(0xFF00E5FF) // Teal / Cyan (Charging)
                    percentNumber != null -> when (percentNumber) {
                        in 40..100 -> Color(0xFF00E676) // Green (40% – 100%)
                        in 21..39 -> Color(0xFFFF9800)  // Orange (21% – 39%)
                        else -> Color(0xFFFF5252)       // Red (1% – 20%)
                    }
                    col0.contains("OFFLINE", ignoreCase = true) || col2.contains("OFFLINE", ignoreCase = true) ->
                        Color(0xFFFF6E6E)
                    else -> Color(0xFFB9F6CA)
                }

                withStyle(SpanStyle(color = batteryColor, fontWeight = FontWeight.SemiBold)) {
                    append(col1)
                }

                withStyle(SpanStyle(color = Color(0xFF00FF66).copy(alpha = 0.45f))) {
                    append("│")
                }

                if (col2.contains("[LOW POWER]")) {
                    val statePrefix = col2.substringBefore("[LOW POWER]")
                    val stateColor = when {
                        isCharging -> Color(0xFF00E5FF)
                        col2.contains("OFFLINE", ignoreCase = true) -> Color(0xFFFF6E6E)
                        else -> batteryColor
                    }
                    withStyle(SpanStyle(color = stateColor)) {
                        append(statePrefix)
                    }
                    withStyle(SpanStyle(color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)) {
                        append("[LOW POWER]")
                    }
                    val remainder = col2.substringAfter("[LOW POWER]")
                    if (remainder.isNotEmpty()) {
                        withStyle(SpanStyle(color = stateColor)) {
                            append(remainder)
                        }
                    }
                } else {
                    val stateColor = when {
                        isCharging -> Color(0xFF00E5FF)
                        col2.contains("OFFLINE", ignoreCase = true) -> Color(0xFFFF6E6E)
                        else -> batteryColor
                    }
                    withStyle(SpanStyle(color = stateColor)) {
                        append(col2)
                    }
                }
            }

            line.startsWith("[ONLINE]") || line.startsWith("[OFFLINE]") || line.startsWith("[LOCAL]") -> {
                val tag = line.substringBefore("]") + "]"
                val rest = line.substringAfter("]")
                val tagColor = when {
                    tag.contains("ONLINE", ignoreCase = true) -> Color(0xFF33FF66)
                    tag.contains("LOCAL", ignoreCase = true) -> Color(0xFF00E5FF)
                    tag.contains("OFFLINE", ignoreCase = true) -> Color(0xFFFF6E6E)
                    else -> Color(0xFF81C784)
                }
                withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
                    append(tag)
                }
                withStyle(SpanStyle(color = Color(0xFFE8F5E9))) {
                    append(rest)
                }
            }

            line.startsWith("root@fileapex:~#") || line.startsWith("fileapex@") -> {
                val prompt = if (line.contains("batstat")) {
                    line.substringBefore("batstat")
                } else {
                    line.substringBefore(" ") + " "
                }
                val cmd = line.substring(prompt.length)
                withStyle(SpanStyle(color = Color(0xFF00FF66), fontWeight = FontWeight.Bold)) {
                    append(prompt)
                }
                withStyle(SpanStyle(color = Color(0xFFFFFFFF), fontWeight = FontWeight.Bold)) {
                    append(cmd)
                }
            }

            line.startsWith("[INIT]") || line.startsWith("[DONE]") -> {
                withStyle(SpanStyle(color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)) {
                    append(line)
                }
            }

            line.startsWith("---") -> {
                withStyle(SpanStyle(color = Color(0xFF00FF66).copy(alpha = 0.4f))) {
                    append(line)
                }
            }

            else -> {
                withStyle(SpanStyle(color = Color(0xFF81C784))) {
                    append(line)
                }
            }
        }
    }
}
