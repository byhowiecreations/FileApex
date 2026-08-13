package com.fileapex.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Note
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.note.NoteRecord
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
    var inputContent by remember { mutableStateOf("") }
    var noteToDelete by remember { mutableStateOf<NoteRecord?>(null) }
    var showNotesPermissionPrompt by remember { mutableStateOf(false) }
    var pendingNoteToSend by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll to newest message at the bottom when notes change
    LaunchedEffect(displayNotes.size) {
        if (displayNotes.isNotEmpty()) {
            listState.animateScrollToItem(displayNotes.size - 1)
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

    fun doSendNote(text: String) {
        if (!FileApexServices.settings.notesNotificationPromptShown.value) {
            pendingNoteToSend = text
            showNotesPermissionPrompt = true
        } else {
            inputContent = ""
            scope.launch {
                FileApexServices.noteRepository.sendNote(text)
            }
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
                            imageVector = Icons.Filled.Note,
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
                    items(displayNotes, key = { it.noteId }) { item ->
                        NoteBubbleItem(
                            item = item,
                            cardBg = cardBg,
                            textColor = textColor,
                            subTextColor = subTextColor,
                            isCustomGlass = isCustomGlass,
                            onDeleteClick = {
                                noteToDelete = item
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Composer Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputContent,
                    onValueChange = { inputContent = it },
                    placeholder = { Text("Write a note or message...", color = subTextColor) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal,
                        unfocusedBorderColor = if (isCustomGlass) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline,
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor
                    ),
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    onClick = {
                        val text = inputContent.trim()
                        if (text.isNotEmpty()) {
                            doSendNote(text)
                        }
                    },
                    enabled = inputContent.trim().isNotEmpty(),
                    shape = CircleShape,
                    color = if (inputContent.trim().isNotEmpty()) (if (isCustomGlass) Color(0xFF00E676) else FileApexTeal) else Color.Gray.copy(alpha = 0.4f),
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
                if (!text.isNullOrBlank()) {
                    inputContent = ""
                    scope.launch { FileApexServices.noteRepository.sendNote(text) }
                }
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
                        if (!text.isNullOrBlank()) {
                            inputContent = ""
                            scope.launch { FileApexServices.noteRepository.sendNote(text) }
                        }
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
                        if (!text.isNullOrBlank()) {
                            inputContent = ""
                            scope.launch { FileApexServices.noteRepository.sendNote(text) }
                        }
                    }
                ) {
                    Text("Not Now")
                }
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

@Composable
private fun NoteBubbleItem(
    item: NoteRecord,
    cardBg: Color,
    textColor: Color,
    subTextColor: Color,
    isCustomGlass: Boolean,
    onDeleteClick: () -> Unit
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
    val formattedTime = TimeUtils.formatUtcToLocal(item.epochMs)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleBg),
            shape = bubbleShape,
            border = if (isCustomGlass) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)) else null,
            modifier = Modifier.widthIn(max = 380.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = subTextColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete note",
                                tint = subTextColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 19.sp),
                    color = textColor
                )
            }
        }
    }
}
