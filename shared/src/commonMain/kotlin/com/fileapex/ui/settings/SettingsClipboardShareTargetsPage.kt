package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipboardShareTargetsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onShareModeChange: (ClipboardShareMode) -> Unit,
    onTogglePeer: (String, Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("share_clipboard_with"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (state.clipboardShareMode == ClipboardShareMode.UNSET) {
                Text(
                    text = stringRes("choose_all_or_specific"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.selectableGroup()) {
                ClipboardShareModeRow(
                    title = stringRes("all_devices"),
                    subtitle = stringRes("broadcast_clipboard_wifi"),
                    selected = state.clipboardShareMode == ClipboardShareMode.ALL,
                    onClick = { onShareModeChange(ClipboardShareMode.ALL) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                ClipboardShareModeRow(
                    title = stringRes("specific_devices"),
                    subtitle = stringRes("only_checked_devices"),
                    selected = state.clipboardShareMode == ClipboardShareMode.SPECIFIC,
                    onClick = { onShareModeChange(ClipboardShareMode.SPECIFIC) }
                )
            }
            if (state.clipboardShareMode == ClipboardShareMode.SPECIFIC) {
                if (state.clipboardPeers.isEmpty()) {
                    Text(
                        text = stringRes("no_paired_devices"),
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.clipboardPeers.forEach { peer ->
                        val checked = peer.deviceId in state.clipboardTargetDeviceIds
                        ListItem(
                            modifier = Modifier.padding(start = 16.dp),
                            headlineContent = { Text(peer.deviceName.ifBlank { stringRes("paired_device") }) },
                            supportingContent = {
                                val platform = peer.platform.ifBlank { peer.os }.ifBlank { null }
                                if (platform != null) {
                                    Text(platform)
                                }
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onTogglePeer(peer.deviceId, it) }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardShareModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
        }
    )
}
