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
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoLaunchOnRebootSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("auto_launch_on_reboot"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        stringRes(if (com.fileapex.platform.BootLaunchPreference.usesDesktopAutostart()) "desktop_autostart_title" else "start_share_after_reboot"),
                        softWrap = true
                    )
                },
                supportingContent = {
                    Text(
                        stringRes(if (com.fileapex.platform.BootLaunchPreference.usesDesktopAutostart()) "desktop_autostart_desc" else "start_share_after_reboot_desc"),
                        softWrap = true
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.autoLaunchOnReboot,
                        onCheckedChange = onToggle
                    )
                }
            )
        }
    }
}
