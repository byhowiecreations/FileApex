package com.fileapex.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.ui.NotesAttachmentTransportOverlay
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.FileApexTeal

@Composable
internal fun NotesScope.NotesBody(modifier: Modifier) {
    with(frame) { with(live) {
    val focusManager = LocalFocusManager.current
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
            .then(
                if (!searchOpen) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val inField = searchAreaRect?.contains(down.position) == true
                            val inToggle = searchToggleRect?.contains(down.position) == true
                            if (!inField && !inToggle) {
                                focusManager.clearFocus()
                                searchOpen = false
                            }
                        }
                    }
                }
            )
            .closeSwipeOnOutsidePress(revealedNoteId != null, { revealedNoteId?.let { bubbleRects[it] } }) { revealedNoteId = null }
    ) {
    val snackbar = LocalNotesSnackbar.current
    Scaffold(
        containerColor = backgroundColor,
        snackbarHost = { snackbar?.let { SnackbarHost(it.host) } },
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
    if (dropHover) {
        val dropAccent = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(dropAccent.copy(alpha = 0.14f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                border = BorderStroke(2.dp, dropAccent)
            ) {
                Text(
                    text = stringRes("drop_files_to_post"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = dropAccent,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }
        }
    }
    }

    } }
}
