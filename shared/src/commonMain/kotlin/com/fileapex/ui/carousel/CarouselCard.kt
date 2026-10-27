package com.fileapex.ui.carousel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceDetailsState
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.devices.DeviceDetailsRows
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.CarouselPalette

@Composable
internal fun CarouselCard(
    row: DeviceListRow,
    flipped: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    details: DeviceDetailsState?,
    width: Dp,
    height: Dp,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onSendClipboard: () -> Unit,
    onRemove: () -> Unit,
    onFilesDropped: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(durationMillis = 550))
    val density = LocalDensity.current
    var dropHover by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .size(width, height)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density.density
            }
            .onGloballyPositioned { if (flipped) CarouselFlip.cardBounds = it.boundsInRoot() }
            .thawGlass(shape, blur = 36.dp, base = Color(0xFF181B2C), blurred = rotation == 0f)
            .then(if (dropHover) Modifier.border(BorderStroke(2.dp, CarouselPalette.accent), shape) else Modifier)
            .deviceFileDropTarget(onHoverChange = { dropHover = it }, onFilesDropped = onFilesDropped)
    ) {
        if (rotation <= 90f) {
            CarouselFront(row, onFlip = { CarouselFlip.toggle(row.deviceId) }, onOpen, onRename, onSendClipboard)
        } else {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                CarouselBack(row, details, isFavorite, onToggleFavorite, onFlip = { CarouselFlip.toggle(row.deviceId) }, onRemove)
            }
        }
    }
}

@Composable
private fun CarouselHeader(row: DeviceListRow, subtitle: String, onFlip: () -> Unit) {
    Column {
        Text(
            text = row.deviceName,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onFlip)
        )
        Text(text = subtitle, fontSize = 12.sp, color = CarouselPalette.textMuted, maxLines = 1)
    }
}

@Composable
private fun CarouselFront(
    row: DeviceListRow,
    onFlip: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onSendClipboard: () -> Unit
) {
    val version = (row.displayVersion ?: row.appVersion)?.takeIf { it.isNotBlank() }?.let { "v$it" }.orEmpty()
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(modifier = Modifier.weight(1f)) { CarouselHeader(row, version, onFlip) }
            Spacer(Modifier.width(8.dp))
            StatusPill(row)
        }
        DeviceEntryIcon(row = row, modifier = Modifier.align(Alignment.Center).size(110.dp))
        Row(modifier = Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!row.isFriend) {
                CardIconButton(Icons.Outlined.ContentPaste, stringRes("clipboard"), onSendClipboard)
            }
            CardIconButton(Icons.Outlined.Edit, stringRes("rename"), onRename)
            CardIconButton(Icons.Outlined.Info, stringRes("info"), onFlip)
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(12.dp))
                .background(CarouselPalette.accent)
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringRes("carousel_navigate_files"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CarouselPalette.onAccent)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = CarouselPalette.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CarouselBack(
    row: DeviceListRow,
    details: DeviceDetailsState?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onFlip: () -> Unit,
    onRemove: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.weight(1f)) { CarouselHeader(row, stringRes("device_details"), onFlip) }
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringRes("carousel_favorite"),
                        tint = if (isFavorite) CarouselPalette.accent else CarouselPalette.textMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val snapshot = details?.snapshot
                when {
                    snapshot != null -> DeviceDetailsRows(details, snapshot, Modifier.fillMaxSize(), bottomInset = 56.dp)
                    details?.errorMessage != null -> Text(details.errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    else -> CircularProgressIndicator(modifier = Modifier.size(22.dp).align(Alignment.Center), strokeWidth = 2.dp)
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(46.dp)
                .thawChip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = stringRes("remove"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun StatusPill(row: DeviceListRow) {
    val dot = if (row.online) CarouselPalette.ready else Color(0xFFFFC107)
    val label = if (row.online) DeviceListRow.localizedOnlineLabel(row.reachedBy) else stringRes("tap_to_wake")
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(dot.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, dot.copy(alpha = 0.4f)), CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = dot, maxLines = 1)
    }
}

@Composable
private fun CardIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .thawChip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}
