package com.fileapex.ui.devices

import fileapex.shared.generated.resources.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.i18n.stringRes
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun FreestyleOptionIcon(
    action: FreestyleOptionAction,
    iconStyle: ThemeIconStyle,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF64B5F6)
) {
    when (iconStyle) {
        ThemeIconStyle.FLUX -> {
            when (action) {
                FreestyleOptionAction.CHECK_BATTERIES -> Image(
                    painter = painterResource(Res.drawable.opt_flux_battery),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.SEND_CLIPBOARD -> Image(
                    painter = painterResource(Res.drawable.opt_flux_clipboard),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.ADD_DEVICE,
                FreestyleOptionAction.FRIENDS,
                FreestyleOptionAction.JOIN_DEVICE -> Image(
                    painter = painterResource(Res.drawable.opt_flux_qrcode),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.SETTINGS -> Image(
                    painter = painterResource(Res.drawable.opt_flux_settings),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.FILES -> Image(
                    painter = painterResource(Res.drawable.opt_flux_folder),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.GROUPS -> Icon(action.icon, action.label(), modifier = modifier, tint = tint)
            }
        }
        ThemeIconStyle.FREESTYLE -> {
            when (action) {
                FreestyleOptionAction.CHECK_BATTERIES -> Image(
                    painter = painterResource(Res.drawable.opt_fs_battery),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.SEND_CLIPBOARD -> Image(
                    painter = painterResource(Res.drawable.opt_fs_clipboard),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.ADD_DEVICE,
                FreestyleOptionAction.FRIENDS,
                FreestyleOptionAction.JOIN_DEVICE -> Image(
                    painter = painterResource(Res.drawable.opt_fs_qrcode),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.SETTINGS -> Image(
                    painter = painterResource(Res.drawable.opt_fs_settings),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.FILES -> Image(
                    painter = painterResource(Res.drawable.opt_fs_folder),
                    contentDescription = action.label(),
                    modifier = modifier.clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                FreestyleOptionAction.GROUPS -> Icon(action.icon, action.label(), modifier = modifier, tint = tint)
            }
        }
        ThemeIconStyle.STANDARD -> {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label(),
                modifier = modifier,
                tint = tint
            )
        }
    }
}

@Composable
internal fun FreestyleHorizontalCardItem(
    action: FreestyleMenuAction,
    isEditMode: Boolean,
    isDragging: Boolean = false,
    dragOffsetX: Float = 0f,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(50.dp)
            .height(if (isEditMode) 58.dp else 52.dp)
            .zIndex(if (isDragging) 20f else 1f)
            .graphicsLayer { translationX = dragOffsetX }
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !isEditMode, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isDragging) Color(0x6664B5F6) else Color(0x3364B5F6)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 2.dp,
                    end = 2.dp,
                    top = if (isEditMode) 3.dp else 7.dp,
                    bottom = if (isEditMode) 3.dp else 6.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isEditMode) Arrangement.SpaceBetween else Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
        ) {
            if (isEditMode) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier
                        .size(16.dp)
                        .pointerInput(action) {
                            detectDragGestures(
                                onDragStart = { onDragStart?.invoke() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag?.invoke(dragAmount.x)
                                },
                                onDragEnd = { onDragEnd?.invoke() },
                                onDragCancel = { onDragEnd?.invoke() }
                            )
                        }
                )
            }
            Icon(
                imageVector = action.menuIcon(),
                contentDescription = null,
                tint = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6),
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = action.menuShortLabel(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun FreestyleMenuRowItem(
    action: FreestyleMenuAction,
    isEditMode: Boolean,
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .zIndex(if (isDragging) 20f else 1f)
            .graphicsLayer { translationY = dragOffsetY }
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !isEditMode, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isDragging) Color(0x6664B5F6) else Color(0x3364B5F6)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isEditMode) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier
                        .size(18.dp)
                        .pointerInput(action) {
                            detectDragGestures(
                                onDragStart = { onDragStart?.invoke() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag?.invoke(dragAmount.y)
                                },
                                onDragEnd = { onDragEnd?.invoke() },
                                onDragCancel = { onDragEnd?.invoke() }
                            )
                        }
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = action.menuIcon(),
                contentDescription = null,
                tint = if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6),
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = action.menuFullLabel(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isEditMode) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun FreestyleTileRingItem(
    action: FreestyleMenuAction,
    isEditMode: Boolean,
    isDragging: Boolean = false,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(46.dp)
            .height(50.dp)
            .shadow(if (isDragging) 12.dp else 6.dp, RoundedCornerShape(11.dp))
            .clip(RoundedCornerShape(11.dp))
            .border(
                BorderStroke(
                    width = if (isDragging) 2.dp else (if (isEditMode) 1.5.dp else 1.dp),
                    color = if (isDragging) Color(0xFF00E5FF) else (if (isEditMode) Color(0xFFFFB300) else Color(0xFF64B5F6).copy(alpha = 0.85f))
                ),
                RoundedCornerShape(11.dp)
            )
            .clickable(enabled = !isEditMode, onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        color = if (isDragging) Color(0xFA143754) else Color(0xFA0B1C2A)
    ) {
        if (isEditMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(action) {
                        detectDragGestures(
                            onDragStart = { onDragStart?.invoke() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag?.invoke(dragAmount)
                            },
                            onDragEnd = { onDragEnd?.invoke() },
                            onDragCancel = { onDragEnd?.invoke() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = action.menuIcon(),
                        contentDescription = action.menuShortLabel(),
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = action.menuShortLabel(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 10.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(9.dp)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 3.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = action.menuIcon(),
                    contentDescription = action.menuShortLabel(),
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = action.menuShortLabel(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 10.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
