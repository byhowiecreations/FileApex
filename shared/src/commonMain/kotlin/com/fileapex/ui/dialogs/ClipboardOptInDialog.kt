package com.fileapex.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes

@Composable
fun ClipboardOptInDialog(
    senderDeviceName: String,
    sharingEnabled: Boolean,
    onToggleSharing: (Boolean) -> Unit,
    onDone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                text = stringRes("clipboard_opt_in_dialog_title"),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val displayName = senderDeviceName.ifBlank { stringRes("paired_device") }
                Text(
                    text = stringRes("clipboard_opt_in_dialog_body", displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringRes("clipboard_sharing"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = sharingEnabled,
                        onCheckedChange = onToggleSharing
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDone) {
                Text(stringRes("done"))
            }
        }
    )
}
