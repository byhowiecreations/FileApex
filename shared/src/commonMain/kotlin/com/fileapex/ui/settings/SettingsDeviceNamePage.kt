package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode

@Composable
internal fun DeviceNameSettingsPage(
    state: com.fileapex.presentation.SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val broadcast = state.deviceNameBroadcasting.ifBlank { stringRes("paired_device") }
    SettingsPageShell(
        title = stringRes("device_name"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringRes("device_name_page_desc"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = AppI18n.t("currently_broadcasting_as", broadcast),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleSmall
            )
            if (state.deviceNameClusterSync.isNotBlank() &&
                !state.deviceNameClusterSync.equals(broadcast, ignoreCase = true)
            ) {
                Text(
                    text = AppI18n.t("device_name_cluster_sync", state.deviceNameClusterSync),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = state.deviceNameDraft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringRes("device_name_field_label")) },
                supportingText = state.deviceNameError?.let { err ->
                    { Text(text = err, color = MaterialTheme.colorScheme.error) }
                } ?: {
                    Text(stringRes("device_name_alphanumeric_hint"))
                },
                isError = state.deviceNameError != null,
                keyboardOptions = KeyboardOptions.Default
            )
            Button(
                onClick = onSave,
                enabled = !state.deviceNameSaveBusy &&
                    state.deviceNameDraft.trim().isNotEmpty() &&
                    !state.deviceNameDraft.trim().equals(broadcast, ignoreCase = true),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (state.deviceNameSaveBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringRes("device_name_save"))
            }
            state.deviceNameStatus?.let { status ->
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (state.deviceNamePeerLabels.isNotEmpty()) {
                Text(
                    text = stringRes("device_name_peer_labels_title"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleSmall
                )
                state.deviceNamePeerLabels.forEach { label ->
                    val peerLabel = label.peerDeviceName.ifBlank { label.peerDeviceId }
                    ListItem(
                        headlineContent = {
                            Text(
                                AppI18n.t("device_name_peer_label_row", peerLabel, label.assignedName),
                                softWrap = true
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
