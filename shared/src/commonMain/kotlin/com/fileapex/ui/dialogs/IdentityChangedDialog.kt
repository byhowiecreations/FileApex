package com.fileapex.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.fileapex.i18n.stringRes

/** Tells the user that this device has a new identity; while a member is being asked it shows the code to compare, otherwise what to do by hand. */
@Composable
fun IdentityChangedDialog(askedName: String?, code: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("identity_changed_title"), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                if (askedName != null && code != null) stringRes("identity_changed_waiting", askedName, code) else stringRes("identity_changed_body"),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringRes("identity_changed_ok")) } }
    )
}
