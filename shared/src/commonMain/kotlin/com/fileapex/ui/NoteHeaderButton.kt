package com.fileapex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.di.FileApexServices
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.i18n.stringRes

@Composable
fun NoteHeaderButton(
    onOpenNotes: () -> Unit,
    viewMode: ExplorerViewMode = FileApexServices.settings.devicesViewMode.collectAsState().value,
    modifier: Modifier = Modifier
) {
    val currentTheme = LocalAppTheme.current
    val isAndroid = com.fileapex.cloud.currentPlatformLabel() == "Android"
    val iconKind = when {
        currentTheme == AppTheme.KINETIC_SPHERE -> NoteIconKind.GREEN
        currentTheme == AppTheme.FLUX_GLASS -> NoteIconKind.WHITE
        currentTheme == AppTheme.CLEAN && isAndroid -> NoteIconKind.BLACK
        else -> NoteIconKind.WHITE
    }
    val painter = rememberNoteIconPainter(iconKind)

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
