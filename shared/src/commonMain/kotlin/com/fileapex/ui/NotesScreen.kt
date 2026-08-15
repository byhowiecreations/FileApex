package com.fileapex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.data.note.NoteRecord
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.rememberDownloadsFilePicker
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.di.FileApexServices
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.launch

@Composable
fun NotesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE
    val rawNotes by FileApexServices.noteRepository.notes.collectAsState()
    val displayNotes = remember(rawNotes) { rawNotes.sortedBy { it.epochMs } }
    val listRows = remember(displayNotes) { notesListRows(displayNotes) }
    var inputContent by remember { mutableStateOf("") }
    var noteToDelete by remember { mutableStateOf<NoteRecord?>(null) }
    var showNotesPermissionPrompt by remember { mutableStateOf(false) }
    var pendingNoteToSend by remember { mutableStateOf<String?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentName by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentPath by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentSize by remember { mutableLongStateOf(0L) }
    var revealedNoteId by remember { mutableStateOf<String?>(null) }

    val pickAttachment = rememberDownloadsFilePicker { picked ->
        if (picked == null) return@rememberDownloadsFilePicker
        if (picked.sizeBytes > DriveRelayPolicy.NOTES_ATTACHMENT_MAX_BYTES) {
            attachError = "Attachments must be under 5 MB"
            return@rememberDownloadsFilePicker
        }
        pendingAttachmentName = picked.displayName
        pendingAttachmentPath = picked.absolutePath
        pendingAttachmentSize = picked.sizeBytes
        attachError = null
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll to newest message at the bottom when notes change
    LaunchedEffect(listRows.size) {
        if (listRows.isNotEmpty()) {
            listState.animateScrollToItem(listRows.size - 1)
        }
    }

    val backgroundColor = when (currentTheme) {
        AppTheme.FLUX_GLASS -> Color.Transparent
        AppTheme.KINETIC_SPHERE -> Color(0xFF030B14)
        else -> MaterialTheme.colorScheme.background
    }
    val cardBg = when (currentTheme) {
        AppTheme.FLUX_GLASS -> Color(0x330D1F29)
        AppTheme.KINETIC_SPHERE -> Color(0x440A1D2E)
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface
    val subTextColor = if (isCustomGlass) Color.White.copy(alpha = 0.70f) else MaterialTheme.colorScheme.onSurfaceVariant

    fun flushPendingSend(text: String?) {
        if (text == null) return
        inputContent = ""
        val name = pendingAttachmentName
        val path = pendingAttachmentPath
        val size = pendingAttachmentSize
        pendingAttachmentName = null
        pendingAttachmentPath = null
        pendingAttachmentSize = 0L
        if (text.isBlank() && path.isNullOrBlank()) return
        scope.launch {
            FileApexServices.noteRepository.sendNote(
                content = text,
                attachmentPath = path,
                attachmentFileName = name,
                attachmentSizeBytes = size
            )
        }
    }

    fun doSendNote(text: String) {
        val attachmentPath = pendingAttachmentPath
        if (!attachmentPath.isNullOrBlank() && !DriveRelayPolicy.canSend()) {
            attachError = if (!FileApexServices.settings.googleAccountLinkEnabled.value) {
                "Link a Google Account and enable Cellular → Google Drive Relay to attach files."
            } else if (DriveRelayPolicy.needsSendPrompt()) {
                "Confirm Cellular send (first time) then attach again."
            } else {
                "Enable Cellular and Google Drive Relay in Settings to attach files."
            }
            return
        }
        if (!FileApexServices.settings.notesNotificationPromptShown.value) {
            pendingNoteToSend = text
            showNotesPermissionPrompt = true
        } else {
            flushPendingSend(text)
        }
    }

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Notes & Shared Memory",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = textColor
                    )
                    Text(
                        text = "Sync notes, messages, & files across paired devices",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = subTextColor
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (displayNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Note,
                            contentDescription = null,
                            tint = if (isCustomGlass) Color(0xFF00E676).copy(alpha = 0.6f) else FileApexTeal.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No notes yet",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Type a note below to save & send to your paired devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = subTextColor
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(listRows, key = { row ->
                        when (row) {
                            is NotesListRow.DayHeader -> "day-${row.dayKey}"
                            is NotesListRow.Bubble -> row.note.noteId
                        }
                    }) { row ->
                        when (row) {
                            is NotesListRow.DayHeader -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = row.label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = subTextColor
                                    )
                                }
                            }
                            is NotesListRow.Bubble -> {
                                NoteBubbleItem(
                                    item = row.note,
                                    cardBg = cardBg,
                                    textColor = textColor,
                                    subTextColor = subTextColor,
                                    isCustomGlass = isCustomGlass,
                                    revealed = revealedNoteId == row.note.noteId,
                                    onRevealedChange = { open ->
                                        revealedNoteId = if (open) row.note.noteId else null
                                    },
                                    onCloseAnyReveal = { revealedNoteId = null },
                                    onDeleteClick = {
                                        revealedNoteId = null
                                        noteToDelete = row.note
                                    },
                                    onLockClick = {
                                        revealedNoteId = null
                                        scope.launch {
                                            FileApexServices.noteRepository.setAttachmentPinned(
                                                row.note.noteId,
                                                !row.note.attachmentPinned
                                            )
                                        }
                                    },
                                    onOpenAttachment = {
                                        val path = row.note.attachmentLocalPath
                                        val name = row.note.attachmentFileName.orEmpty()
                                        if (!path.isNullOrBlank()) {
                                            openLocalFile(path, name)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            pendingAttachmentName?.let { name ->
                Text(
                    text = "Attached: $name",
                    style = MaterialTheme.typography.labelMedium,
                    color = subTextColor,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // Composer Row — 15% shorter than Material3's 56.dp outlined field so the
            // placeholder stays on one line.
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
                        contentDescription = "Attach file",
                        tint = if (pendingAttachmentPath != null) {
                            if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                        } else {
                            subTextColor
                        }
                    )
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(NOTES_COMPOSER_HEIGHT),
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
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        singleLine = true,
                        textStyle = composerTextStyle,
                        cursorBrush = SolidColor(composerBorder),
                        interactionSource = composerInteraction,
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (inputContent.isEmpty()) {
                                    Text(
                                        text = "Write a note or message...",
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
                            contentDescription = "Send note",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // First Chat Send Notification Prompt Dialog
    if (showNotesPermissionPrompt) {
        AlertDialog(
            onDismissRequest = {
                FileApexServices.settings.setNotesNotificationsEnabled(false)
                FileApexServices.settings.setNotesNotificationPromptShown(true)
                val text = pendingNoteToSend
                pendingNoteToSend = null
                showNotesPermissionPrompt = false
                flushPendingSend(text)
            },
            title = { Text("Enable Note Notifications?") },
            text = { Text("Would you like to receive notifications when new notes or shared messages arrive from your paired devices?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileApexServices.settings.setNotesNotificationsEnabled(true)
                        FileApexServices.settings.setNotesNotificationPromptShown(true)
                        val text = pendingNoteToSend
                        pendingNoteToSend = null
                        showNotesPermissionPrompt = false
                        flushPendingSend(text)
                    }
                ) {
                    Text("Enable Notifications")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        FileApexServices.settings.setNotesNotificationsEnabled(false)
                        FileApexServices.settings.setNotesNotificationPromptShown(true)
                        val text = pendingNoteToSend
                        pendingNoteToSend = null
                        showNotesPermissionPrompt = false
                        flushPendingSend(text)
                    }
                ) {
                    Text("Not Now")
                }
            }
        )
    }

    attachError?.let { message ->
        AlertDialog(
            onDismissRequest = { attachError = null },
            title = { Text("Attachment") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { attachError = null }) { Text("OK") }
            }
        )
    }

    // Delete Scope Dialog (This device only vs All devices)
    val targetToDelete = noteToDelete
    if (targetToDelete != null) {
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete Note") },
            text = { Text("Do you want to delete this note entry from this device only, or delete it from all paired devices?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = targetToDelete
                        noteToDelete = null
                        scope.launch {
                            FileApexServices.noteRepository.deleteNoteFromAllDevices(target.noteId)
                        }
                    }
                ) {
                    Text("All Devices", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val target = targetToDelete
                            noteToDelete = null
                            scope.launch {
                                FileApexServices.noteRepository.deleteNote(target.noteId)
                            }
                        }
                    ) {
                        Text("This Device Only")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = { noteToDelete = null }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteBubbleItem(
    item: NoteRecord,
    cardBg: Color,
    textColor: Color,
    subTextColor: Color,
    isCustomGlass: Boolean,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onCloseAnyReveal: () -> Unit,
    onDeleteClick: () -> Unit,
    onLockClick: () -> Unit,
    onOpenAttachment: () -> Unit
) {
    val isMine = item.isMine
    val alignment = if (isMine) Alignment.End else Alignment.Start
    val bubbleShape = if (isMine) {
        RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    }
    val bubbleBg = if (isMine) {
        if (isCustomGlass) Color(0x4400E676) else FileApexTeal.copy(alpha = 0.16f)
    } else {
        cardBg
    }
    val stamp = TimeUtils.noteListStamp(item.epochMs)
    val attachmentName = item.attachmentFileName?.ifBlank { null }
    val hasAttachment = !attachmentName.isNullOrBlank()
    val caption = item.content.trim().takeIf { text ->
        text.isNotEmpty() && text != attachmentName && !text.startsWith("[Synced note from Drive:")
    }

    val density = LocalDensity.current
    val bubbleScope = rememberCoroutineScope()
    val actionCount = if (hasAttachment) 2 else 1
    val actionWidthPx = with(density) { (52.dp * actionCount).toPx() }
    val offsetAnim = remember { Animatable(0f) }
    val showActions = revealed || offsetAnim.value < -1f

    LaunchedEffect(revealed, actionWidthPx) {
        offsetAnim.animateTo(if (revealed) -actionWidthPx else 0f)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (showActions) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasAttachment) {
                    NoteRevealAction(
                        icon = if (item.attachmentPinned) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (item.attachmentPinned) {
                            "Unlock attachment"
                        } else {
                            "Lock attachment"
                        },
                        containerColor = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal,
                        onClick = onLockClick
                    )
                }
                NoteRevealAction(
                    icon = Icons.Filled.DeleteOutline,
                    contentDescription = "Delete note",
                    containerColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteClick
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .offset { IntOffset(offsetAnim.value.roundToInt(), 0) }
                    .draggable(
                        state = rememberDraggableState { delta ->
                            val next = (offsetAnim.value + delta).coerceIn(-actionWidthPx, 0f)
                            bubbleScope.launch { offsetAnim.snapTo(next) }
                        },
                        orientation = Orientation.Horizontal,
                        onDragStopped = {
                            val open = offsetAnim.value < -actionWidthPx / 2f
                            onRevealedChange(open)
                            bubbleScope.launch {
                                offsetAnim.animateTo(if (open) -actionWidthPx else 0f)
                            }
                        }
                    )
                    .combinedClickable(
                        onClick = {
                            if (revealed) {
                                onRevealedChange(false)
                            } else {
                                onCloseAnyReveal()
                            }
                        },
                        onLongClick = { onRevealedChange(true) }
                    )
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = bubbleBg),
                    shape = bubbleShape,
                    border = if (isCustomGlass) BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)) else null
                ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isMine) "This Device" else item.sourceDeviceName,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                        )
                        if (!item.driveFileId.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0x3300E5FF)
                            ) {
                                Text(
                                    text = "Drive Sync",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                    color = Color(0xFF00E5FF),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (!caption.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 19.sp),
                            color = textColor
                        )
                    }
                    if (attachmentName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (revealed) {
                                            onRevealedChange(false)
                                        } else {
                                            onCloseAnyReveal()
                                            onOpenAttachment()
                                        }
                                    },
                                    onLongClick = { onRevealedChange(true) }
                                )
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = "Open attachment",
                                tint = subTextColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = attachmentName,
                                style = MaterialTheme.typography.labelMedium,
                                color = textColor
                            )
                        }
                    }
                }
            }
            Text(
                text = stamp.timeLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = subTextColor,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )
            }
        }
    }
}

@Composable
private fun NoteRevealAction(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private sealed class NotesListRow {
    data class DayHeader(val dayKey: String, val label: String) : NotesListRow()
    data class Bubble(val note: NoteRecord) : NotesListRow()
}

private fun notesListRows(notes: List<NoteRecord>): List<NotesListRow> {
    val rows = mutableListOf<NotesListRow>()
    var lastDay: String? = null
    for (note in notes) {
        val stamp = TimeUtils.noteListStamp(note.epochMs)
        if (stamp.dayKey != lastDay) {
            rows += NotesListRow.DayHeader(stamp.dayKey, stamp.dateHeader)
            lastDay = stamp.dayKey
        }
        rows += NotesListRow.Bubble(note)
    }
    return rows
}

private val NOTES_COMPOSER_HEIGHT = 47.6.dp
