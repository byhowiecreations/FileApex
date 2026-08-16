package com.fileapex.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fileapex.di.FileApexServices
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.dnd.deviceFileDropTarget
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class EllipticalOrbitConfig(
    val rotationDeg: Float,
    val scaleX: Float,
    val scaleY: Float
)

/**
 * Kinetic Sphere Theme (`AppTheme.KINETIC_SPHERE`) - Spatial Node-Based Orbital Network.
 * Dynamic data-driven layout supporting N paired devices placed on static 3D elliptical orbits.
 * Selected devices enlarge smoothly to indicate selection without shifting position on screen.
 * Tapping a device opens Option 1: Floating Glass Action Capsule on top Z-index layer with 100% opaque coverage.
 */
/**
 * Set to true for smooth 'ellipses connected' orbital curves matching design mockup.
 * Set to false to revert to straight 'connected device lines' (spokes).
 */
const val USE_ELLIPSES_CONNECTED = false

@Composable
fun KineticSphereDevicesView(
    deviceRows: List<DeviceListRow>,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    onGenerateQr: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onManualEntry: () -> Unit = {},
    onCheckBatteries: (() -> Unit)? = null,
    onOpenNotes: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var addMenuOpen by remember { mutableStateOf(false) }
    var activeRadialNodeId by remember { mutableStateOf<String?>(null) }
    var sphereCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { sphereCoords = it }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                activeRadialNodeId = null
            }
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val centerX = widthPx / 2f
        val centerY = heightPx * 0.50f
        val hubCenter = Offset(centerX, centerY)

        val nodeCount = deviceRows.size
        val minR = with(density) { 120.dp.toPx() }
        val maxR = with(density) { 240.dp.toPx() }
        val baseRadiusPx = (minOf(widthPx, heightPx) * 0.36f).coerceIn(minR, maxR)

        val orbitConfigs = remember {
            listOf(
                EllipticalOrbitConfig(rotationDeg = -28f, scaleX = 1.28f, scaleY = 0.68f),
                EllipticalOrbitConfig(rotationDeg = 35f, scaleX = 1.42f, scaleY = 0.78f),
                EllipticalOrbitConfig(rotationDeg = 115f, scaleX = 1.58f, scaleY = 0.88f),
                EllipticalOrbitConfig(rotationDeg = 155f, scaleX = 1.32f, scaleY = 0.64f)
            )
        }

        val staticNodePositions = remember(deviceRows, widthPx, heightPx, baseRadiusPx) {
            val marginPx = with(density) { 70.dp.toPx() }
            val topMarginPx = with(density) { 55.dp.toPx() }
            val minDistancePx = with(density) { 135.dp.toPx() }
            val minHubDistancePx = with(density) { 138.dp.toPx() }

            val rawPositions = deviceRows.mapIndexed { index, _ ->
                val config = orbitConfigs[index % orbitConfigs.size]
                val radiusMultiplier = if (nodeCount <= 4) 1.0f else (if (index % 2 == 0) 0.85f else 1.38f)
                val angleStep = (2.0 * PI / nodeCount)
                val baseAngle = (angleStep * index) - (PI / 2.0)

                val rx = baseRadiusPx * config.scaleX * radiusMultiplier
                val ry = baseRadiusPx * config.scaleY * radiusMultiplier

                val localX = rx * cos(baseAngle)
                val localY = ry * sin(baseAngle)

                val rad = config.rotationDeg * (PI / 180.0)
                val rotX = (localX * cos(rad) - localY * sin(rad)).toFloat()
                val rotY = (localX * sin(rad) + localY * cos(rad)).toFloat()

                var cx = centerX + rotX
                var cy = centerY + rotY

                val hubDx = cx - centerX
                val hubDy = cy - centerY
                val hubDist = kotlin.math.sqrt(hubDx * hubDx + hubDy * hubDy)
                if (hubDist < minHubDistancePx && hubDist > 0.001f) {
                    val factor = minHubDistancePx / hubDist
                    cx = centerX + (hubDx * factor)
                    cy = centerY + (hubDy * factor)
                }

                cx = cx.coerceIn(marginPx, widthPx - marginPx)
                cy = cy.coerceIn(topMarginPx, heightPx - marginPx)
                Offset(cx, cy)
            }.toMutableList()

            for (iter in 0 until 6) {
                for (i in 0 until rawPositions.size) {
                    for (j in i + 1 until rawPositions.size) {
                        val posI = rawPositions[i]
                        val posJ = rawPositions[j]
                        val dx = posJ.x - posI.x
                        val dy = posJ.y - posI.y
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (dist < minDistancePx && dist > 0.001f) {
                            val overlap = (minDistancePx - dist) / 2f
                            val nx = dx / dist
                            val ny = dy / dist
                            val newIx = (posI.x - nx * overlap).coerceIn(marginPx, widthPx - marginPx)
                            val newIy = (posI.y - ny * overlap).coerceIn(topMarginPx, heightPx - marginPx)
                            val newJx = (posJ.x + nx * overlap).coerceIn(marginPx, widthPx - marginPx)
                            val newJy = (posJ.y + ny * overlap).coerceIn(topMarginPx, heightPx - marginPx)
                            rawPositions[i] = Offset(newIx, newIy)
                            rawPositions[j] = Offset(newJx, newJy)
                        }
                    }
                }
            }
            rawPositions
        }

        val isExpandedDisplay = maxWidth >= 600.dp
        val layoutScopePrefix = if (isExpandedDisplay) "exp:" else "cmp:"

        val persistedNodeOffsets by FileApexServices.settings.kineticNodeOffsets.collectAsState()

        val effectiveNodePositions = remember(staticNodePositions, persistedNodeOffsets, deviceRows, widthPx, heightPx, layoutScopePrefix) {
            val marginPx = with(density) { 70.dp.toPx() }
            val topMarginPx = with(density) { 55.dp.toPx() }
            val minHubDistancePx = with(density) { 138.dp.toPx() }

            staticNodePositions.mapIndexed { index, staticPos ->
                val row = deviceRows.getOrNull(index) ?: return@mapIndexed staticPos
                val scopedKey = layoutScopePrefix + row.deviceId
                val offsetPair = persistedNodeOffsets[scopedKey] ?: persistedNodeOffsets[row.deviceId]
                val dragOffset = if (offsetPair != null) Offset(offsetPair.first, offsetPair.second) else Offset.Zero
                var cx = staticPos.x + dragOffset.x
                var cy = staticPos.y + dragOffset.y

                val hubDx = cx - centerX
                val hubDy = cy - centerY
                val hubDist = kotlin.math.sqrt(hubDx * hubDx + hubDy * hubDy)
                if (hubDist < minHubDistancePx && hubDist > 0.001f) {
                    val factor = minHubDistancePx / hubDist
                    cx = centerX + (hubDx * factor)
                    cy = centerY + (hubDy * factor)
                }

                cx = cx.coerceIn(marginPx, widthPx - marginPx)
                cy = cy.coerceIn(topMarginPx, heightPx - marginPx)
                Offset(cx, cy)
            }
        }

        val showConnectedLines by FileApexServices.settings.kineticSphereConnectedLinesEnabled.collectAsState()
        val showOrbitalRings by FileApexServices.settings.kineticSphereOrbitalRingsEnabled.collectAsState()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val solidGlowStroke = Stroke(width = 4f.dp.toPx())
            val solidCoreStroke = Stroke(width = 1.8f.dp.toPx())

            if (showOrbitalRings) {
                orbitConfigs.forEach { config ->
                    withTransform({
                        translate(centerX, centerY)
                        rotate(config.rotationDeg)
                    }) {
                        val rx = baseRadiusPx * config.scaleX
                        val ry = baseRadiusPx * config.scaleY

                        drawOval(
                            color = Color(0xFF00E5FF).copy(alpha = 0.14f),
                            topLeft = Offset(-rx, -ry),
                            size = Size(rx * 2f, ry * 2f),
                            style = solidGlowStroke
                        )
                        drawOval(
                            color = Color(0xFF00E5FF).copy(alpha = 0.40f),
                            topLeft = Offset(-rx, -ry),
                            size = Size(rx * 2f, ry * 2f),
                            style = solidCoreStroke
                        )
                    }
                }
            }

            val stars = listOf(
                Offset(centerX * 0.3f, centerY * 0.4f),
                Offset(centerX * 1.6f, centerY * 0.3f),
                Offset(centerX * 0.2f, centerY * 1.5f),
                Offset(centerX * 1.7f, centerY * 1.6f),
                Offset(centerX * 1.4f, centerY * 0.8f),
                Offset(centerX * 0.6f, centerY * 1.2f)
            )
            stars.forEach { star ->
                drawCircle(color = Color.White.copy(alpha = 0.4f), radius = 2f, center = star)
            }

            if (showConnectedLines) {
                effectiveNodePositions.forEachIndexed { index, pos ->
                    val row = deviceRows.getOrNull(index) ?: return@forEachIndexed
                    val online = row.online
                    val statusGlow = if (online) Color(0xFF00E676) else Color(0xFFFFC107)

                    drawLine(
                        color = statusGlow.copy(alpha = 0.50f),
                        start = Offset(centerX, centerY),
                        end = pos,
                        strokeWidth = 1.5f.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }
            }
        }

        val hubSizeDp = 110.dp
        val hubPx = with(density) { hubSizeDp.toPx() }
        Box(
            modifier = Modifier.offset {
                IntOffset((centerX - hubPx / 2f).roundToInt(), (centerY - hubPx / 2f).roundToInt())
            }
        ) {
            Box(
                modifier = Modifier
                    .size(hubSizeDp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0x9900E5FF),
                                Color(0x660A2A4A),
                                Color(0x22030B14)
                            )
                        )
                    )
                    .border(
                        BorderStroke(2.dp, Color(0xFF00E5FF).copy(alpha = 0.85f)),
                        CircleShape
                    )
                    .clickable { addMenuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add New Device",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Add New\nDevice",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 13.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }

            DropdownMenu(
                expanded = addMenuOpen,
                onDismissRequest = { addMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.QrCode,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = "Generate QR Code", color = Color.White)
                        }
                    },
                    onClick = {
                        addMenuOpen = false
                        onGenerateQr()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.QrCodeScanner,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = "Scan QR Code", color = Color.White)
                        }
                    },
                    onClick = {
                        addMenuOpen = false
                        onScanQr()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = "Manually Enter Code", color = Color.White)
                        }
                    },
                    onClick = {
                        addMenuOpen = false
                        onManualEntry()
                    }
                )
            }
        }

        if (deviceRows.isNotEmpty() && onCheckBatteries != null) {
            val batteryText = if (deviceRows.size <= 1) "Check Battery" else "Check Batteries"
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            centerX.roundToInt(),
                            (centerY + hubPx / 2f + 16.dp.toPx()).roundToInt()
                        )
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-placeable.width / 2, 0)
                        }
                    }
            ) {
                Surface(
                    onClick = onCheckBatteries,
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xDD0D1C22),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.50f)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        BatteryIcon(
                            modifier = Modifier.size(15.dp),
                            tint = Color(0xFF00E676)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = batteryText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            ),
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (nodeCount > 0) {
            deviceRows.forEachIndexed { index, row ->
                val pos = effectiveNodePositions.getOrNull(index) ?: return@forEachIndexed
                val nodeCx = pos.x
                val nodeCy = pos.y

                val isFocused = activeRadialNodeId == row.deviceId
                val focusProgress by animateFloatAsState(
                    targetValue = if (isFocused) 1.0f else 0.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "nodeFocus_${row.deviceId}"
                )

                val baseOrbSizeDp = 68.dp
                val focusedOrbSizeDp = 88.dp
                val currentOrbSizeDp = baseOrbSizeDp + ((focusedOrbSizeDp - baseOrbSizeDp) * focusProgress)
                val currentOrbSizePx = with(density) { currentOrbSizeDp.toPx() }

                val statusColor = if (row.online) Color(0xFF00E676) else Color(0xFFFFC107)
                var dropHover by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset((nodeCx - currentOrbSizePx / 2f).roundToInt(), (nodeCy - currentOrbSizePx / 2f).roundToInt())
                        }
                        .size(currentOrbSizeDp)
                        .deviceFileDropTarget(
                            enabled = true,
                            onHoverChange = { dropHover = it },
                            onFilesDropped = { paths ->
                                val firstName = paths.firstOrNull()
                                    ?.substringAfterLast('/')
                                    ?.substringAfterLast('\\')
                                    .orEmpty()
                                if (firstName.isNotEmpty()) {
                                    sphereCoords?.let { coords ->
                                        startKineticDropFx(
                                            sphere = coords,
                                            node = Offset(nodeCx, nodeCy),
                                            queued = !row.online,
                                            fileName = firstName
                                        )
                                    }
                                }
                                onFilesDropped(row.deviceId, paths)
                            }
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    if (dropHover) Color(0xAA00E676) else (if (isFocused) Color(0xAA00E5FF) else Color(0x7714344D)),
                                    if (isFocused) Color(0x660A2A4A) else Color(0x440B1B2B),
                                    Color(0x11040B14)
                                )
                            )
                        )
                        .border(
                            BorderStroke(
                                width = 1.5.dp + (2.dp * focusProgress),
                                color = if (dropHover) Color(0xFF00E676) else (if (isFocused) Color(0xFF00E5FF) else statusColor)
                            ),
                            CircleShape
                        )
                        .pointerInput(row.deviceId, layoutScopePrefix) {
                            detectDragGestures(
                                onDragStart = { activeRadialNodeId = null },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val scopedKey = layoutScopePrefix + row.deviceId
                                    val currentPair = persistedNodeOffsets[scopedKey]
                                        ?: persistedNodeOffsets[row.deviceId]
                                        ?: Pair(0f, 0f)
                                    val newDx = currentPair.first + dragAmount.x
                                    val newDy = currentPair.second + dragAmount.y
                                    FileApexServices.settings.setKineticNodeOffset(scopedKey, newDx, newDy)
                                }
                            )
                        }
                        .clickable {
                            activeRadialNodeId = if (isFocused) null else row.deviceId
                        },
                    contentAlignment = Alignment.Center
                ) {
                    DeviceEntryIcon(
                        row = row,
                        modifier = Modifier.size(28.dp + (8.dp * focusProgress)),
                        tint = Color.White
                    )
                }

                val labelWidthDp = 125.dp
                val labelWidthPx = with(density) { labelWidthDp.toPx() }
                val labelTopPx = nodeCy + (currentOrbSizePx / 2f) + with(density) { 4.dp.toPx() }
                val labelAlpha = (1.0f - focusProgress).coerceIn(0f, 1f)

                if (labelAlpha > 0.01f) {
                    Column(
                        modifier = Modifier
                            .offset {
                                IntOffset((nodeCx - labelWidthPx / 2f).roundToInt(), labelTopPx.roundToInt())
                            }
                            .width(labelWidthDp)
                            .alpha(labelAlpha),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                lineHeight = 14.sp
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = row.subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            activeRadialNodeId?.let { activeId ->
                val activeIndex = deviceRows.indexOfFirst { it.deviceId == activeId }
                if (activeIndex != -1) {
                    val row = deviceRows[activeIndex]
                    val pos = effectiveNodePositions.getOrNull(activeIndex)
                    if (pos != null) {
                        KineticGlassActionCapsule(
                            centerPx = pos,
                            hubCenter = hubCenter,
                            canvasWidthPx = widthPx,
                            canvasHeightPx = heightPx,
                            expansionProgress = 1.0f,
                            row = row,
                            onOpen = {
                                activeRadialNodeId = null
                                onOpenDevice(row.deviceId)
                            },
                            onSendClipboard = {
                                activeRadialNodeId = null
                                onSendClipboardDevice(row.deviceId)
                            },
                            onRename = {
                                activeRadialNodeId = null
                                onRenameDevice(row.deviceId, row.deviceName)
                            },
                            onInfo = {
                                activeRadialNodeId = null
                                onDeviceDetails(row.deviceId)
                            }
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = "No Paired Devices Yet\nTap Central Hub (+) to Pair",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Option 1: Floating Glass Action Capsule
 * Rendered on top Z-index layer with 100% opaque frosted glass container to cover underlying nodes & labels.
 */
@Composable
private fun KineticGlassActionCapsule(
    centerPx: Offset,
    hubCenter: Offset,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    expansionProgress: Float,
    row: DeviceListRow,
    onOpen: () -> Unit,
    onSendClipboard: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit
) {
    val density = LocalDensity.current
    val statusColor = if (row.online) Color(0xFF00E676) else Color(0xFFFFC107)

    val dx = centerPx.x - hubCenter.x
    val dy = centerPx.y - hubCenter.y
    val outwardAngle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())

    val distDp = 82.dp
    val distPx = with(density) { distDp.toPx() }

    val rawX = centerPx.x + (distPx * cos(outwardAngle)).toFloat()
    val rawY = centerPx.y + (distPx * sin(outwardAngle)).toFloat()

    val capsuleWidthDp = 224.dp
    val capsuleHeightDp = 106.dp
    val capsuleWidthPx = with(density) { capsuleWidthDp.toPx() }
    val capsuleHeightPx = with(density) { capsuleHeightDp.toPx() }

    val edgeMarginPx = with(density) { 16.dp.toPx() }
    val clampedX = (rawX - (capsuleWidthPx / 2f)).coerceIn(edgeMarginPx, canvasWidthPx - edgeMarginPx - capsuleWidthPx)
    val clampedY = (rawY - (capsuleHeightPx / 2f)).coerceIn(edgeMarginPx, canvasHeightPx - edgeMarginPx - capsuleHeightPx)

    Box(
        modifier = Modifier
            .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
            .width(capsuleWidthDp)
            .height(capsuleHeightDp)
            .graphicsLayer {
                scaleX = 0.6f + (0.4f * expansionProgress)
                scaleY = 0.6f + (0.4f * expansionProgress)
                alpha = expansionProgress.coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B1F30),
                        Color(0xFF061420)
                    )
                )
            )
            .border(
                BorderStroke(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF00E5FF),
                            Color(0xFF00E676)
                        )
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .padding(vertical = 8.dp, horizontal = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (row.online) "Ready" else "Wake",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = statusColor
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x3300E5FF))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CapsuleActionButton("Browse", Icons.Filled.Folder, Color(0xFF00E676), onOpen)
                CapsuleActionButton("Clipboard", Icons.AutoMirrored.Filled.Assignment, Color(0xFF00E5FF), onSendClipboard)
                CapsuleActionButton("Rename", Icons.Filled.Edit, Color(0xFFFFC107), onRename)
                CapsuleActionButton("Info", Icons.Filled.Info, Color(0xFF40C4FF), onInfo)
            }
        }
    }
}

@Composable
private fun CapsuleActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = accentColor.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.70f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
fun BatteryIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF00E676)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, h * 0.15f),
            size = Size(w * 0.80f, h * 0.70f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f, w * 0.1f),
            style = Stroke(width = 1.5f.dp.toPx())
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.84f, h * 0.32f),
            size = Size(w * 0.16f, h * 0.36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.28f),
            size = Size(w * 0.56f, h * 0.44f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f)
        )
    }
}
