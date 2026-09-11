package com.fileapex.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.i18n.stringRes

@Composable
fun ClipboardTargetConfigDialog(
    initialMode: ClipboardShareMode,
    initialTargetIds: Set<String>,
    peers: List<PairedDeviceEntity>,
    onConfirm: (ClipboardShareMode, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember {
        mutableStateOf(
            if (initialMode == ClipboardShareMode.UNSET) ClipboardShareMode.ALL else initialMode
        )
    }
    var selectedDeviceIds by remember {
        mutableStateOf(
            if (initialTargetIds.isNotEmpty()) initialTargetIds else peers.map { it.deviceId }.toSet()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringRes("configure_clipboard_targets_title"),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringRes("configure_clipboard_targets_body"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.selectableGroup()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedMode == ClipboardShareMode.ALL,
                                onClick = { selectedMode = ClipboardShareMode.ALL },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == ClipboardShareMode.ALL,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringRes("all_devices"),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringRes("broadcast_clipboard_wifi"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedMode == ClipboardShareMode.SPECIFIC,
                                onClick = { selectedMode = ClipboardShareMode.SPECIFIC },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == ClipboardShareMode.SPECIFIC,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringRes("specific_devices"),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringRes("only_checked_devices"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (selectedMode == ClipboardShareMode.SPECIFIC) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    if (peers.isEmpty()) {
                        Text(
                            text = stringRes("no_paired_devices"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        peers.forEach { peer ->
                            val isChecked = peer.deviceId in selectedDeviceIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDeviceIds = if (isChecked) {
                                            selectedDeviceIds - peer.deviceId
                                        } else {
                                            selectedDeviceIds + peer.deviceId
                                        }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedDeviceIds = if (checked) {
                                            selectedDeviceIds + peer.deviceId
                                        } else {
                                            selectedDeviceIds - peer.deviceId
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = peer.deviceName.ifBlank { stringRes("paired_device") },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(selectedMode, selectedDeviceIds)
                }
            ) {
                Text(stringRes("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringRes("cancel"))
            }
        }
    )
}
