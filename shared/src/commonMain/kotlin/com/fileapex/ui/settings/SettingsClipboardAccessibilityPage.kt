package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.ClipboardRuntimeDiagnostics
import com.fileapex.platform.ClipboardShizukuPolicy
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.ClipboardAccessibilityBanner
import com.fileapex.ui.SettingsScreenLayoutMode
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipboardAccessibilityPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggleAccessibility: (Boolean) -> Unit,
    onToggleShizuku: (Boolean) -> Unit,
    onDismissRestrictedHelp: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    SettingsPageShell(
        title = stringRes("accessibility"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("accessibility"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("accessibility_subtitle"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.clipboardAccessibilityEnabled,
                        onCheckedChange = onToggleAccessibility
                    )
                }
            )
            ClipboardAccessibilityBanner(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (!com.fileapex.di.FileApexServices.isPlayStoreBuild) {
                ClipboardShizukuToggle(
                    enabled = state.clipboardShizukuEnabled,
                    onToggle = onToggleShizuku
                )
            }
        }
        if (state.showAccessibilityRestrictedHelp) {
            AlertDialog(
                onDismissRequest = onDismissRestrictedHelp,
                title = { Text(stringRes("allow_restricted_settings")) },
                text = {
                    Text(stringRes("restricted_settings_body"))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onOpenAppInfo()
                            onDismissRestrictedHelp()
                        }
                    ) { Text(stringRes("open_app_info")) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onOpenAccessibilitySettings()
                            onDismissRestrictedHelp()
                        }
                    ) { Text(stringRes("open_accessibility")) }
                }
            )
        }
    }
}

@Composable
private fun ClipboardShizukuToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var snapshot by remember { mutableStateOf(ClipboardRuntimeDiagnostics.snapshot()) }
    LaunchedEffect(enabled) {
        while (true) {
            snapshot = ClipboardRuntimeDiagnostics.snapshot()
            delay(1_500)
        }
    }
    val supporting = when (
        ClipboardShizukuPolicy.toggleHint(
            optedIn = enabled,
            installed = snapshot.shizukuInstalled,
            running = snapshot.shizukuRunning,
            active = snapshot.shizukuActive
        )
    ) {
        ClipboardShizukuPolicy.ToggleHint.USING -> stringRes("shizuku_using")
        ClipboardShizukuPolicy.ToggleHint.CONNECTED_UNUSED -> stringRes("shizuku_connected_unused")
        ClipboardShizukuPolicy.ToggleHint.AUTHORIZE -> stringRes("shizuku_step_authorize")
        ClipboardShizukuPolicy.ToggleHint.START -> stringRes("shizuku_step_start")
        ClipboardShizukuPolicy.ToggleHint.SUBTITLE -> stringRes("shizuku_subtitle")
    }
    ListItem(
        headlineContent = { Text(stringRes("diag_shizuku_active"), softWrap = true) },
        supportingContent = { Text(supporting, softWrap = true) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    )
}
