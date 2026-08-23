package com.fileapex.ui.dialogs

import com.fileapex.i18n.stringRes

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun GoogleDrivePermissionDialog(
    onGrant: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringRes("grant_drive_access"),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = stringRes("grant_drive_body"),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color.Black
                )
            ) {
                Text(stringRes("grant_access"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringRes("not_now"))
            }
        }
    )
}
