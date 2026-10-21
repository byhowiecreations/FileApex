package com.fileapex.ui.kinetic

import com.fileapex.ui.KineticGlassActionCapsule
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.deviceIconFillsOrb
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.kineticFractionKey
import com.fileapex.ui.startKineticDropFx
import com.fileapex.ui.theme.KineticStyleLook
import kotlin.math.roundToInt

@Composable
internal fun KineticScope.KineticNodes() {
    with(inputs) { with(frame) { with(live) {
        if (nodeCount > 0) {
            deviceRows.forEachIndexed { index, row ->
                KineticNode(index, row)
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
                            },
                            onRemove = {
                                activeRadialNodeId = null
                                onRemoveDevice(row.deviceId, row.deviceName)
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
                    text = stringRes("kinetic_empty_hint"),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    ),
                    color = when (kineticStyle) {
                        KineticStyle.JADED_STEEL -> KineticStyleLook.ink
                        KineticStyle.SPACE -> Color.White.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    } } }
}


@Composable
internal fun KineticScope.KineticNode(index: Int, row: DeviceListRow) {
    with(inputs) { with(frame) { with(live) {
        val pos = effectiveNodePositions.getOrNull(index) ?: return
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
        val latestNodeCx by rememberUpdatedState(nodeCx)
        val latestNodeCy by rememberUpdatedState(nodeCy)

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
                            sphereCoordsRef[0]?.let { coords ->
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
                .then(
                        Modifier
                            .clip(CircleShape)
                            .then(
                                when (kineticStyle) {
                                    KineticStyle.JADED_STEEL -> Modifier.background(
                                        if (dropHover || isFocused) KineticStyleLook.jadedCardSelected else KineticStyleLook.jadedCard
                                    )
                                    KineticStyle.SPACE -> Modifier.background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                if (dropHover) Color(0xAA00E676) else (if (isFocused) Color(0xAA00E5FF) else Color(0x7714344D)),
                                                if (isFocused) Color(0x660A2A4A) else Color(0x440B1B2B),
                                                Color(0x11040B14)
                                            )
                                        )
                                    )
                                }
                            )
                            .border(
                                BorderStroke(
                                    width = if (kineticStyle == KineticStyle.JADED_STEEL) {
                                        with(density) { 1f.toDp() }
                                    } else {
                                        1.5.dp + (2.dp * focusProgress)
                                    },
                                    color = when (kineticStyle) {
                                        KineticStyle.JADED_STEEL -> if (dropHover || isFocused) KineticStyleLook.jade else statusColor
                                        KineticStyle.SPACE -> if (dropHover) Color(0xFF00E676) else (if (isFocused) Color(0xFF00E5FF) else statusColor)
                                    }
                                ),
                                CircleShape
                            )
                )
                // Do not key on nodeCx/nodeCy: that restarts the gesture mid-drag and locks nodes.
                .pointerInput(row.deviceId, layoutBucket) {
                    var dragX = 0f
                    var dragY = 0f
                    var dragged = false
                    fun saveDrag(persist: Boolean) {
                        if (!dragged || widthPx <= 1f || heightPx <= 1f) return
                        val edge = with(density) { 70.dp.toPx() }
                        val top = with(density) { 55.dp.toPx() }
                        val maxX = (widthPx - edge).coerceAtLeast(edge)
                        val maxY = (heightPx - edge).coerceAtLeast(top)
                        val px = dragX.coerceIn(edge, maxX).roundToInt().toFloat().coerceIn(edge, maxX)
                        val py = dragY.coerceIn(top, maxY).roundToInt().toFloat().coerceIn(top, maxY)
                        val pixelKey = if (layoutBucket.endsWith("-cmp")) {
                            "pos:cmp:${row.deviceId}"
                        } else {
                            "pos:exp:${row.deviceId}"
                        }
                        FileApexServices.settings.setKineticNodeOffsets(
                            mapOf(
                                kineticFractionKey(layoutBucket, row.deviceId) to
                                    Pair((px / widthPx).coerceIn(0f, 1f), (py / heightPx).coerceIn(0f, 1f)),
                                pixelKey to Pair(px, py),
                                "pos:${row.deviceId}" to Pair(px, py)
                            ),
                            persist = persist
                        )
                    }
                    detectDragGestures(
                        onDragStart = {
                            activeRadialNodeId = null
                            dragged = false
                            dragX = latestNodeCx
                            dragY = latestNodeCy
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragged = true
                            dragX += dragAmount.x
                            dragY += dragAmount.y
                            saveDrag(persist = false)
                        },
                        onDragCancel = { saveDrag(persist = true) },
                        onDragEnd = { saveDrag(persist = true) }
                    )
                }
                .clickable {
                    activeRadialNodeId = if (isFocused) null else row.deviceId
                },
            contentAlignment = Alignment.Center
        ) {
            val iconStyle = LocalThemeIconStyle.current
            val nodeBorderWidth = 1.5.dp + (2.dp * focusProgress)
            DeviceEntryIcon(
                row = row,
                modifier = if (deviceIconFillsOrb(iconStyle)) {
                    Modifier.fillMaxSize().padding(nodeBorderWidth)
                } else {
                    Modifier.size(28.dp + (8.dp * focusProgress))
                },
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
                    text = DeviceListRow.localizedSubtitle(row),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true,
                    textAlign = TextAlign.Center
                )
            }
        }
    } } }
}
