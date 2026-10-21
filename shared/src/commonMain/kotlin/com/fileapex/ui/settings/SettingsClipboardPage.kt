package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import com.fileapex.i18n.stringRes
import com.fileapex.platform.ClipboardDiagnosticsPolicy
import com.fileapex.platform.ClipboardRuntimeDiagnostics
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipboardSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleViaCellular: (Boolean) -> Unit,
    onToggleSendNotification: (Boolean) -> Unit,
    onToggleAutoSend: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenShareTargets: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val isAndroid = currentPlatformLabel() == "Android"
    // Simple is built for one paired device, which is assumed as the target; other themes keep the chooser.
    val pairedDevices by FileApexServices.deviceRepository.observeDevices().collectAsState(emptyList())
    val assumedSingleTarget = LocalAppTheme.current == AppTheme.SIMPLE && remember(pairedDevices) {
        val selfId = com.fileapex.data.identity.loadLocalIdentity().deviceId
        val peers = pairedDevices
            .filter { !it.isRemoved && it.deviceId != com.fileapex.data.identity.LocalIdentity.LOCAL_DEVICE_ID && it.deviceId != selfId }
            .distinctBy { it.deviceId }
            .map {
                ClipboardSharePolicy.PeerRef(
                    deviceId = it.deviceId,
                    isDesktop = com.fileapex.domain.peer.PeerPlatform.isDesktop(it.os, it.platform)
                )
            }
        ClipboardSharePolicy.resolveAutoDefaultTargetId(isAndroid, peers) != null
    }
    SettingsPageShell(
        title = stringRes("clipboard"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("clipboard_sharing"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("clipboard_sharing_subtitle"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.clipboardSharingEnabled,
                        onCheckedChange = onToggle
                    )
                }
            )
            if (state.clipboardSharingEnabled) {
                if (isAndroid) {
                    ListItem(
                        modifier = Modifier.padding(start = 16.dp),
                        headlineContent = { Text(stringRes("send_clipboard"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("send_clipboard_notification_subtitle"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardSendNotificationEnabled,
                                onCheckedChange = onToggleSendNotification
                            )
                        }
                    )
                    if (!com.fileapex.di.FileApexServices.isPlayStoreBuild) {
                        SettingsNavItem(
                            title = stringRes("accessibility"),
                            subtitle = if (state.clipboardAccessibilityEnabled) {
                                stringRes("enabled")
                            } else {
                                stringRes("disabled")
                            },
                            onClick = onOpenAccessibility
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringRes("via_cellular"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("via_cellular_subtitle"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardViaCellularEnabled,
                                onCheckedChange = onToggleViaCellular
                            )
                        }
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(stringRes("automatically_send"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("automatically_send_subtitle"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardAutoSendEnabled,
                                onCheckedChange = onToggleAutoSend
                            )
                        }
                    )
                }
                if (!assumedSingleTarget) {
                    SettingsNavItem(
                        title = stringRes("share_clipboard_with"),
                        subtitle = clipboardShareTargetsSubtitle(state),
                        onClick = onOpenShareTargets
                    )
                }
                if (isAndroid) {
                    ClipboardDiagnosticsEntry(
                        accessibilityEnabled = state.clipboardAccessibilityEnabled,
                        shizukuOptedIn = state.clipboardShizukuEnabled,
                        recipientsChosen = ClipboardDiagnosticsPolicy.recipientsChosen(
                            shareModeAll = state.clipboardShareMode == ClipboardShareMode.ALL,
                            shareModeSpecific = state.clipboardShareMode == ClipboardShareMode.SPECIFIC,
                            specificTargetCount = state.clipboardTargetDeviceIds.size
                        ),
                        onOpenDiagnostics = onOpenDiagnostics
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardDiagnosticsEntry(
    accessibilityEnabled: Boolean,
    shizukuOptedIn: Boolean,
    recipientsChosen: Boolean,
    onOpenDiagnostics: () -> Unit
) {
    var snapshot by remember { mutableStateOf(ClipboardRuntimeDiagnostics.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = ClipboardRuntimeDiagnostics.snapshot()
            delay(1_500)
        }
    }
    if (!ClipboardDiagnosticsPolicy.shouldShowEntry(sharingEnabled = true)) return
    val ready = ClipboardDiagnosticsPolicy.allRequiredGranted(
        ClipboardDiagnosticsPolicy.checks(
            sharingEnabled = true,
            recipientsChosen = recipientsChosen,
            accessibilitySettingEnabled = accessibilityEnabled,
            accessibilityListed = snapshot.accessibilityListed,
            accessibilityBound = snapshot.accessibilityBound,
            batteryWhitelisted = snapshot.batteryWhitelisted,
            notificationsEnabled = snapshot.notificationsEnabled,
            restrictedSettingsRelevant = snapshot.restrictedSettingsRelevant,
            restrictedSettingsBlocked = snapshot.restrictedSettingsBlocked,
            shizukuActive = snapshot.shizukuActive,
            shizukuOptedIn = shizukuOptedIn
        )
    )
    SettingsNavItem(
        title = stringRes("clipboard_diagnostics"),
        subtitle = if (ready) stringRes("diag_all_granted") else stringRes("diag_missing_required"),
        onClick = onOpenDiagnostics
    )
}
