package com.fileapex.ui.devices

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.fileapex.domain.device.DeviceNameCoordinator
import com.fileapex.i18n.stringRes

/** Renames another device. A name the device would refuse is reported as it is typed and cannot be saved. */
@Composable
internal fun RenameDeviceDialog(
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val error = DeviceNameCoordinator.liveError(renameText)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringRes("rename_device")) },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = onRenameTextChange,
                singleLine = true,
                label = { Text(stringRes("name")) },
                supportingText = {
                    if (error != null) {
                        Text(text = error, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringRes("device_name_alphanumeric_hint"))
                    }
                },
                isError = error != null
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = renameText.isNotBlank() && error == null) {
                Text(stringRes("save"))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringRes("cancel")) } }
    )
}
