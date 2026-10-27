package com.fileapex.ui.carousel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceDetailsState
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.theme.CarouselPalette
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Endless row of device cards: the centred card is full size, the ones beside it shrink, fade and turn,
 * and the row wraps in both directions. The caller guarantees at least three rows.
 */
@Composable
fun CarouselDevicesView(
    deviceRows: List<DeviceListRow>,
    details: DeviceDetailsState?,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onDismissDetails: () -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val n = deviceRows.size
    if (n == 0) return
    val settings = com.fileapex.di.FileApexServices.settings
    val favoriteId by settings.carouselFavoriteDevice.collectAsState()
    var target by remember {
        val wanted = CarouselFocus.lastDeviceId ?: favoriteId
        mutableIntStateOf(deviceRows.indexOfFirst { it.deviceId == wanted }.coerceAtLeast(0))
    }
    LaunchedEffect(target, n) { CarouselFocus.lastDeviceId = deviceRows.getOrNull(CarouselMath.wrap(target, n))?.deviceId }
    val pos = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) { pos.animateTo(target.toFloat(), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }

    val flippedId by CarouselFlip.flippedId.collectAsState()
    LaunchedEffect(target) { CarouselFlip.unflip() }
    LaunchedEffect(flippedId) {
        flippedId?.let(onDeviceDetails) ?: onDismissDetails()
    }
    DisposableEffect(Unit) { onDispose { CarouselFlip.unflip() } }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { target -= 1; true }
                    Key.DirectionRight -> { target += 1; true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        if (CarouselFlip.flippedId.value != null) return@detectHorizontalDragGestures
                        change.consume()
                        dragged += dragAmount
                        if (abs(dragged) > 90f) {
                            target += if (dragged < 0) 1 else -1
                            dragged = 0f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                val gate = CarouselScrollGate()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll || CarouselFlip.flippedId.value != null) continue
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val amount = if (abs(delta.x) > abs(delta.y)) delta.x else delta.y
                        target += gate.onScroll(amount, System.currentTimeMillis())
                    }
                }
            }
    ) {
        val cardW = (maxWidth * 0.36f).coerceIn(300.dp, 400.dp)
        val cardH = cardW * 0.72f
        val spacingPx = with(density) { (cardW * 0.86f).toPx() }
        val k = min(2, (n - 1) / 2)
        val base = floor(pos.value).toInt()
        for (slot in (base - k - 1)..(base + k + 2)) {
            val offset = slot - pos.value
            if (abs(offset) > k + 0.5f) continue
            key(slot) {
                val row = deviceRows[CarouselMath.wrap(slot, n)]
                val centered = abs(offset) < 0.5f
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(10f - abs(offset))
                        .graphicsLayer {
                            translationX = offset * spacingPx
                            val s = CarouselMath.cardScale(offset)
                            scaleX = s
                            scaleY = s
                            alpha = CarouselMath.cardAlpha(offset)
                            rotationY = (-offset * 16f).coerceIn(-32f, 32f)
                            cameraDistance = 18f * density.density
                        }
                ) {
                    CarouselCard(
                        row = row,
                        flipped = flippedId == row.deviceId,
                        isFavorite = favoriteId == row.deviceId,
                        onToggleFavorite = { settings.setCarouselFavoriteDevice(if (favoriteId == row.deviceId) "" else row.deviceId) },
                        details = details?.takeIf { it.deviceId == row.deviceId },
                        width = cardW,
                        height = cardH,
                        onOpen = { onOpenDevice(row.deviceId) },
                        onRename = { onRenameDevice(row.deviceId, row.deviceName) },
                        onSendClipboard = { onSendClipboardDevice(row.deviceId) },
                        onRemove = { onRemoveDevice(row.deviceId, row.deviceName) },
                        onFilesDropped = { onFilesDropped(row.deviceId, it) }
                    )
                    if (!centered) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { target = slot }
                        )
                    }
                }
            }
        }
        ArrowButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            description = "previous",
            onClick = { target -= 1 },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).zIndex(100f)
        )
        ArrowButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            description = "next",
            onClick = { target += 1 },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).zIndex(100f)
        )
    }
}

@Composable
private fun ArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .thawGlass(CircleShape, blur = 20.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}
