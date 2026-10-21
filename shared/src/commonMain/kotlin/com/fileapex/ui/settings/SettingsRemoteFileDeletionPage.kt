package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode

@Composable
internal fun RemoteFileDeletionSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onAllowRemoteFileDeletionChange: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("bulletin_delete_remote_title"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("bulletin_delete_remote_title"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("remote_delete_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.allowRemoteFileDeletion,
                        onCheckedChange = onAllowRemoteFileDeletionChange
                    )
                }
            )
        }
    }
}
