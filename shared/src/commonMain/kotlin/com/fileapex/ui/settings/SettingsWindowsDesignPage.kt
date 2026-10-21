package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WindowsDesignSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onFluent: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("windows_design"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("windows_11_modern"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("windows_fluent_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.desktopUiStyle == DesktopUiStyle.WindowsFluent,
                        onCheckedChange = onFluent
                    )
                }
            )
        }
    }
}
