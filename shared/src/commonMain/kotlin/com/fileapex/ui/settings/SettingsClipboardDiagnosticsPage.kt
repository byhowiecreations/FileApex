package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.i18n.stringRes
import com.fileapex.platform.ClipboardDiagnosticsPolicy
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.ClipboardDiagnosticsContent
import com.fileapex.ui.SettingsScreenLayoutMode

@Composable
internal fun ClipboardDiagnosticsSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    SettingsPageShell(
        title = stringRes("clipboard_diagnostics"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ClipboardDiagnosticsContent(
                sharingEnabled = state.clipboardSharingEnabled,
                recipientsChosen = ClipboardDiagnosticsPolicy.recipientsChosen(
                    shareModeAll = state.clipboardShareMode == ClipboardShareMode.ALL,
                    shareModeSpecific = state.clipboardShareMode == ClipboardShareMode.SPECIFIC,
                    specificTargetCount = state.clipboardTargetDeviceIds.size
                ),
                accessibilityEnabled = state.clipboardAccessibilityEnabled,
                shizukuOptedIn = state.clipboardShizukuEnabled,
                onOpenAccessibility = onOpenAccessibility,
                onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                onOpenAppInfo = onOpenAppInfo
            )
        }
    }
}
