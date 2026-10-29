package com.fileapex.ui.explorer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.fileapex.i18n.stringRes

/** A remote delete cannot be undone from here, so it is confirmed first. */
@Composable
internal fun RemoteDeleteConfirm(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("remote_delete_confirm_title")) },
        text = { Text(stringRes("remote_delete_confirm_body", name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringRes("delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes("cancel")) } }
    )
}
