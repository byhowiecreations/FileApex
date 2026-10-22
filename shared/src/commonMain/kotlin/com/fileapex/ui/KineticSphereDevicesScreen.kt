package com.fileapex.ui

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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.kinetic.KineticBatteryChip
import com.fileapex.ui.kinetic.KineticFriendsChip
import com.fileapex.ui.kinetic.KineticClipboardChip
import com.fileapex.ui.kinetic.KineticEffects
import com.fileapex.ui.kinetic.KineticHub
import com.fileapex.ui.kinetic.KineticInputs
import com.fileapex.ui.kinetic.KineticLive
import com.fileapex.ui.kinetic.KineticNodes
import com.fileapex.ui.kinetic.KineticOrbitCanvas
import com.fileapex.ui.kinetic.KineticScope
import com.fileapex.ui.kinetic.rememberKineticFrame
import com.fileapex.ui.theme.JadedSteelWash
import com.fileapex.ui.theme.KineticStyleLook
import fileapex.shared.generated.resources.Res
import fileapex.shared.generated.resources.bg
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.jetbrains.compose.resources.painterResource

internal data class EllipticalOrbitConfig(
    val rotationDeg: Float,
    val scaleX: Float,
    val scaleY: Float
)

@Composable
fun KineticSphereWallpaperBackground(
    modifier: Modifier = Modifier
) {
    when (LocalKineticStyle.current) {
        KineticStyle.JADED_STEEL -> JadedSteelWash(modifier.fillMaxSize())
        KineticStyle.SPACE -> Image(
            painter = painterResource(Res.drawable.bg),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun KineticSphereDevicesView(
    deviceRows: List<DeviceListRow>,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    onGenerateQr: () -> Unit = {},
    onJoinDevice: () -> Unit = {},
    onCheckBatteries: (() -> Unit)? = null,
    onSendClipboard: (() -> Unit)? = null,
    onOpenNotes: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val inputs = KineticInputs(
        deviceRows = deviceRows,
        onOpenDevice = onOpenDevice,
        onRenameDevice = onRenameDevice,
        onDeviceDetails = onDeviceDetails,
        onSendClipboardDevice = onSendClipboardDevice,
        onRemoveDevice = onRemoveDevice,
        onFilesDropped = onFilesDropped,
        onGenerateQr = onGenerateQr,
        onJoinDevice = onJoinDevice,
        onCheckBatteries = onCheckBatteries,
        onSendClipboard = onSendClipboard,
        onOpenNotes = onOpenNotes
    )
    val live = remember { KineticLive() }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { live.sphereCoordsRef[0] = it }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                live.activeRadialNodeId = null
            }
    ) {
        val frame = rememberKineticFrame(inputs, density, maxWidth, maxHeight)
        val scope = KineticScope(inputs, frame, live)
        scope.KineticEffects()
        scope.KineticOrbitCanvas()
        scope.KineticHub()
        scope.KineticClipboardChip()
        scope.KineticBatteryChip()
        scope.KineticFriendsChip()
        scope.KineticNodes()
    }
}


/**
 * Option 1: Floating Glass Action Capsule
 * Rendered on top Z-index layer with 100% opaque glass container to cover underlying nodes & labels.
 */
@Composable
internal fun KineticGlassActionCapsule(
    centerPx: Offset,
    hubCenter: Offset,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    expansionProgress: Float,
    row: DeviceListRow,
    onOpen: () -> Unit,
    onSendClipboard: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit
) {
    val density = LocalDensity.current
    val kineticStyle = LocalKineticStyle.current
    val statusColor = if (row.online) Color(0xFF00E676) else Color(0xFFFFC107)

    val dx = centerPx.x - hubCenter.x
    val dy = centerPx.y - hubCenter.y
    val outwardAngle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())

    val distDp = 90.dp
    val distPx = with(density) { distDp.toPx() }

    val rawX = centerPx.x + (distPx * cos(outwardAngle)).toFloat()
    val rawY = centerPx.y + (distPx * sin(outwardAngle)).toFloat()

    val capsuleWidthDp = 236.dp
    val fallbackHeightDp = 148.dp
    val capsuleWidthPx = with(density) { capsuleWidthDp.toPx() }
    val fallbackHeightPx = with(density) { fallbackHeightDp.toPx() }
    var measuredHeightPx by remember { mutableStateOf(0f) }
    val capsuleHeightPx = measuredHeightPx.takeIf { it > 0f } ?: fallbackHeightPx

    val edgeMarginPx = with(density) { 16.dp.toPx() }
    val clampedX = (rawX - (capsuleWidthPx / 2f)).coerceIn(edgeMarginPx, canvasWidthPx - edgeMarginPx - capsuleWidthPx)
    val clampedY = (rawY - (capsuleHeightPx / 2f)).coerceIn(edgeMarginPx, canvasHeightPx - edgeMarginPx - capsuleHeightPx)

    Box(
        modifier = Modifier
            .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
            .width(capsuleWidthDp)
            .wrapContentHeight()
            .onSizeChanged { measuredHeightPx = it.height.toFloat() }
            .graphicsLayer {
                scaleX = 0.6f + (0.4f * expansionProgress)
                scaleY = 0.6f + (0.4f * expansionProgress)
                alpha = expansionProgress.coerceIn(0f, 1f)
            }
            .then(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .then(
                            when (kineticStyle) {
                                KineticStyle.JADED_STEEL -> Modifier.background(KineticStyleLook.jadedMenu)
                                KineticStyle.SPACE -> Modifier.background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFF0B1F30),
                                            Color(0xFF061420)
                                        )
                                    )
                                )
                            }
                        )
                        .border(
                            when (kineticStyle) {
                                KineticStyle.JADED_STEEL -> BorderStroke(1.dp, KineticStyleLook.jadedCardEdge)
                                KineticStyle.SPACE -> BorderStroke(
                                    width = 1.5.dp,
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF00E5FF),
                                            Color(0xFF00E676)
                                        )
                                    )
                                )
                            },
                            RoundedCornerShape(20.dp)
                        )
            )
            .padding(vertical = 10.dp, horizontal = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        text = row.friendGroup ?: if (row.online) stringRes("ready") else stringRes("tap_to_wake"),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = statusColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true
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
                if (row.isFriend) {
                    CapsuleActionButton(stringRes("friends_short_send"), Icons.Filled.UploadFile, Color(0xFF00E676), onOpen)
                    CapsuleActionButton(stringRes("friends_short_accept"), if (row.friendAutoAccept) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, Color(0xFF40C4FF), onInfo)
                    CapsuleActionButton(stringRes("rename"), Icons.Filled.Edit, Color(0xFFFFC107), onRename)
                } else {
                    CapsuleActionButton(stringRes("browse"), Icons.Filled.Folder, Color(0xFF00E676), onOpen)
                    CapsuleActionButton(stringRes("clipboard"), Icons.AutoMirrored.Filled.Assignment, Color(0xFF00E5FF), onSendClipboard)
                    CapsuleActionButton(stringRes("rename"), Icons.Filled.Edit, Color(0xFFFFC107), onRename)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!row.isFriend) CapsuleActionButton(stringRes("info"), Icons.Filled.Info, Color(0xFF40C4FF), onInfo)
                if (row.isFriend) CapsuleActionButton(stringRes("friends_short_unpin"), Icons.Filled.PushPin, Color(0xFFFF5252), onRemove)
                else CapsuleActionButton(stringRes("remove"), Icons.Filled.Delete, Color(0xFFFF5252), onRemove)
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
            modifier = Modifier
                .size(34.dp),
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
            softWrap = true,
            maxLines = 2,
            textAlign = TextAlign.Center
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
