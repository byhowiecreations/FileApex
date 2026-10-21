package com.fileapex.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.fileapex.ui.NotesAttachmentTransportOverlay
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.FileApexTeal

@Composable
internal fun NotesScope.NotesBody(modifier: Modifier) {
    with(frame) { with(live) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (dropHover) {
                    if (isCustomGlass) Color(0xFF00E676).copy(alpha = 0.10f) else FileApexTeal.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                }
            )
            .deviceFileDropTarget(
                enabled = true,
                onHoverChange = { dropHover = it },
                onDropPosition = { lastDropRootOffset = it },
                onFilesDropped = { paths -> handleDroppedPaths(paths, lastDropRootOffset) }
            )
            .onGloballyPositioned { overlayCoords = it }
    ) {
    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            NotesTopBar()
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            NotesListSection(this@NotesBody)
            Spacer(modifier = Modifier.height(10.dp))
            NotesAttachmentChip()
            NotesComposer(this@NotesBody)
        }
    }

    val activeTransport = transport
    if (activeTransport != null && !activeTransport.streamDone) {
        NotesAttachmentTransportOverlay(
            state = activeTransport,
            modifier = Modifier.fillMaxSize()
        )
    }
    }

    } }
}
