package com.fileapex.ui

import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexTiledChrome
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.theme.isFileApexJadedSteel
import com.fileapex.data.settings.AppTheme
import com.fileapex.di.FileApexServices
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.i18n.stringRes

@Composable
fun NoteHeaderButton(
    onOpenNotes: () -> Unit,
    viewMode: ExplorerViewMode = FileApexServices.settings.devicesViewMode.collectAsState().value,
    iconKind: NoteIconKind? = null,
    modifier: Modifier = Modifier
) {
    val currentTheme = LocalAppTheme.current
    val jaded = isFileApexJadedSteel()
    val isAndroid = com.fileapex.cloud.currentPlatformLabel() == "Android"
    val resolvedIconKind = iconKind ?: when {
        jaded || currentTheme.traits.glassChrome -> NoteIconKind.GREEN
        (currentTheme == AppTheme.CLEAN || currentTheme == AppTheme.SIMPLE) && isAndroid -> NoteIconKind.BLACK
        else -> NoteIconKind.WHITE
    }
    val painter = rememberNoteIconPainter(resolvedIconKind)
    val unread by com.fileapex.ui.notes.rememberBulletinUnreadCount()

    Box(modifier = Modifier, contentAlignment = Alignment.TopEnd) {
    NoteHeaderIcon(onOpenNotes, painter, modifier)
    if (unread > 0) UnreadBadge(unread, modifier = Modifier.padding(top = 4.dp, end = 4.dp))
    }
}

@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(Color(0xFFE53935), CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NoteHeaderIcon(onOpenNotes: () -> Unit, painter: androidx.compose.ui.graphics.painter.Painter, modifier: Modifier) {
    if (isFileApexTiledChrome()) {
        Box(
            modifier = modifier
                .size(40.dp)
                .clickable(onClick = onOpenNotes),
            contentAlignment = Alignment.Center
        ) {
            JadedRaisedTile(tileSize = 28.dp) {
                Image(
                    painter = painter,
                    contentDescription = stringRes("bulletin_board"),
                    colorFilter = ColorFilter.tint(fileApexTileTint()),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else {
        IconButton(
            onClick = onOpenNotes,
            modifier = modifier
        ) {
            Image(
                painter = painter,
                contentDescription = stringRes("bulletin_board"),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
