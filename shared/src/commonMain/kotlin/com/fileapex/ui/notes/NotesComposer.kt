package com.fileapex.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.ui.theme.FileApexTeal

@Composable
internal fun ColumnScope.NotesComposer(notes: NotesScope) {
    with(notes) { with(frame) { with(live) {
        // 47.6dp composer height keeps the placeholder on a single line.
        val composerInteraction = remember { MutableInteractionSource() }
        val composerFocused by composerInteraction.collectIsFocusedAsState()
        val composerTextStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = 13.sp,
            color = textColor
        )
        val composerBorder = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
        val composerIdleBorder = if (isCustomGlass) {
            Color.White.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.outline
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { pickAttachment() }) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = stringRes("attach_file"),
                    tint = if (pendingAttachmentPath != null) {
                        if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                    } else {
                        subTextColor
                    }
                )
            }
            IconButton(onClick = { showNewChecklist = true }) {
                Icon(Icons.Filled.Checklist, contentDescription = stringRes("checklist_new"), tint = subTextColor)
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = NOTES_COMPOSER_HEIGHT, max = NOTES_COMPOSER_MAX_HEIGHT),
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    if (composerFocused) composerBorder else composerIdleBorder
                )
            ) {
                BasicTextField(
                    value = inputContent,
                    onValueChange = { inputContent = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp)
                        .onPreviewKeyEvent { event ->
                            // Desktop: Enter sends, Shift+Enter inserts a newline.
                            // Android: the IME Enter key inserts a newline.
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (usesDesktopFileSelection() && isEnter && !event.isShiftPressed) {
                                if (event.type == KeyEventType.KeyDown) {
                                    val text = inputContent.trim()
                                    if (text.isNotEmpty() || !pendingAttachmentPath.isNullOrBlank()) {
                                        doSendNote(text)
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 6,
                    textStyle = composerTextStyle,
                    cursorBrush = SolidColor(composerBorder),
                    interactionSource = composerInteraction,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (inputContent.isEmpty()) {
                                Text(
                                    text = stringRes("broadcast_or_attach"),
                                    color = subTextColor,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                onClick = {
                    val text = inputContent.trim()
                    if (text.isNotEmpty() || !pendingAttachmentPath.isNullOrBlank()) {
                        doSendNote(text)
                    }
                },
                enabled = inputContent.trim().isNotEmpty() || !pendingAttachmentPath.isNullOrBlank(),
                shape = CircleShape,
                color = if (inputContent.trim().isNotEmpty() || !pendingAttachmentPath.isNullOrBlank()) (if (isCustomGlass) Color(0xFF00E676) else FileApexTeal) else Color.Gray.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringRes("send_note"),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    } } }
}
