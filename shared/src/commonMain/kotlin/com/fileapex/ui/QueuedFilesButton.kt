package com.fileapex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.fileApexHeaderActionTint

import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.isFileApexJadedSteel

object QueueBadgeAnchor {
    var windowRect by mutableStateOf<Rect?>(null)
}

@Composable
fun QueuedFilesButton(
    onClick: () -> Unit,
    iconTint: Color = fileApexHeaderActionTint(),
    modifier: Modifier = Modifier
) {
    val count by FileApexServices.transferQueue.pendingCount.collectAsState(initial = 0)

    DisposableEffect(Unit) {
        onDispose { QueueBadgeAnchor.windowRect = null }
    }

    val positionMod = modifier.onGloballyPositioned { coords ->
        val topLeft = coords.localToWindow(Offset.Zero)
        val next = Rect(
            offset = topLeft,
            size = Size(
                coords.size.width.toFloat().coerceAtLeast(1f),
                coords.size.height.toFloat().coerceAtLeast(1f)
            )
        )
        if (QueueBadgeAnchor.windowRect != next) {
            QueueBadgeAnchor.windowRect = next
        }
    }
    if (count <= 0) {
        Box(modifier = positionMod.size(0.dp))
        return
    }

    val jaded = isFileApexJadedSteel()
    BadgedBox(
        modifier = positionMod,
        badge = {
            Badge(
                containerColor = if (jaded) Color(0xFF6366F1) else MaterialTheme.colorScheme.error,
                contentColor = Color.White
            ) {
                Text(text = if (count > 99) "99+" else count.toString())
            }
        }
    ) {
        if (jaded) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                JadedRaisedTile(tileSize = 28.dp) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = stringRes("queued_files"),
                        tint = KineticStyleLook.steel,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = stringRes("queued_files"),
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
