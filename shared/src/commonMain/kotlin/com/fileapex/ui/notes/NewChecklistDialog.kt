package com.fileapex.ui.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes

@Composable
internal fun NewChecklistDialog(onCreate: (title: String, lines: List<String>) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("checklist_new")) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text(stringRes("checklist_title_hint")) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = lines,
                    onValueChange = { lines = it },
                    label = { Text(stringRes("checklist_items_hint")) },
                    minLines = 3,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onCreate(title, lines.lines()) }
            ) { Text(stringRes("checklist_create")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes("cancel")) } }
    )
}

@Composable
internal fun NotesScope.NotesChecklistDialog() {
    with(frame) { with(live) {
        if (showNewChecklist) {
            NewChecklistDialog(
                onCreate = { title, lines ->
                    showNewChecklist = false
                    viewModel.createChecklist(title, lines)
                },
                onDismiss = { showNewChecklist = false }
            )
        }
    } }
}
