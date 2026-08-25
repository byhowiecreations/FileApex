package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.ClipboardCheckResult
import com.fileapex.platform.ClipboardCheckStatus
import com.fileapex.platform.ClipboardDiagnosticsPolicy
import com.fileapex.platform.ClipboardRuntimeDiagnostics
import com.fileapex.platform.ClipboardRuntimeSnapshot
import com.fileapex.platform.ClipboardShizukuPolicy
import kotlinx.coroutines.delay

@Composable
fun ClipboardDiagnosticsContent(
    sharingEnabled: Boolean,
    recipientsChosen: Boolean,
    accessibilityEnabled: Boolean,
    shizukuOptedIn: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var snapshot by remember { mutableStateOf(ClipboardRuntimeDiagnostics.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = ClipboardRuntimeDiagnostics.snapshot()
            delay(1_500)
        }
    }
    val checks = ClipboardDiagnosticsPolicy.checks(
        sharingEnabled = sharingEnabled,
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
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringRes("clipboard_diagnostics_intro"),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        checks.forEach { check ->
            CheckRow(
                check = check,
                supporting = supportingFor(check, snapshot, shizukuOptedIn),
                onClick = clickAction(
                    id = check.id,
                    status = check.status,
                    onOpenAccessibility = onOpenAccessibility,
                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                    onOpenAppInfo = onOpenAppInfo
                )
            )
            if (check.id == ClipboardDiagnosticsPolicy.ID_SHIZUKU &&
                check.status == ClipboardCheckStatus.MISSING
            ) {
                ShizukuRegisterRow(running = snapshot.shizukuRunning)
            }
        }
    }
}

@Composable
private fun ShizukuRegisterRow(running: Boolean) {
    ListItem(
        modifier = Modifier.clickable(onClick = { ClipboardRuntimeDiagnostics.activateShizuku() }),
        headlineContent = {
            Text(
                text = if (running) stringRes("shizuku_authorize") else stringRes("shizuku_start"),
                softWrap = true
            )
        },
        supportingContent = {
            Text(
                text = if (running) {
                    stringRes("shizuku_step_authorize")
                } else {
                    stringRes("shizuku_step_start")
                },
                softWrap = true
            )
        }
    )
}

@Composable
private fun CheckRow(
    check: ClipboardCheckResult,
    supporting: String,
    onClick: (() -> Unit)?
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        headlineContent = { Text(titleFor(check.id), softWrap = true) },
        supportingContent = { Text(supporting, softWrap = true) },
        trailingContent = {
            val (label, granted) = when (check.status) {
                ClipboardCheckStatus.GRANTED -> stringRes("diag_granted") to true
                ClipboardCheckStatus.MISSING -> stringRes("diag_missing") to false
                ClipboardCheckStatus.NOT_REQUIRED -> stringRes("diag_not_required") to true
            }
            Text(
                text = label,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    )
}

@Composable
private fun supportingFor(
    check: ClipboardCheckResult,
    snapshot: ClipboardRuntimeSnapshot,
    shizukuOptedIn: Boolean
): String {
    if (check.id == ClipboardDiagnosticsPolicy.ID_SHIZUKU) {
        return when (
            ClipboardShizukuPolicy.toggleHint(
                optedIn = shizukuOptedIn,
                installed = snapshot.shizukuInstalled,
                running = snapshot.shizukuRunning,
                active = snapshot.shizukuActive
            )
        ) {
            ClipboardShizukuPolicy.ToggleHint.USING -> stringRes("shizuku_using")
            ClipboardShizukuPolicy.ToggleHint.CONNECTED_UNUSED -> stringRes("shizuku_connected_unused")
            ClipboardShizukuPolicy.ToggleHint.AUTHORIZE -> stringRes("shizuku_step_authorize")
            ClipboardShizukuPolicy.ToggleHint.START -> stringRes("shizuku_step_start")
            ClipboardShizukuPolicy.ToggleHint.SUBTITLE -> stringRes("diag_optional")
        }
    }
    return if (check.required) stringRes("diag_needed") else stringRes("diag_optional")
}

@Composable
private fun titleFor(id: String): String = when (id) {
    ClipboardDiagnosticsPolicy.ID_SHARING -> stringRes("clipboard_sharing")
    ClipboardDiagnosticsPolicy.ID_RECIPIENTS -> stringRes("diag_recipients")
    ClipboardDiagnosticsPolicy.ID_A11Y_SETTING -> stringRes("diag_a11y_setting")
    ClipboardDiagnosticsPolicy.ID_A11Y_SYSTEM -> stringRes("diag_a11y_system")
    ClipboardDiagnosticsPolicy.ID_A11Y_BOUND -> stringRes("diag_a11y_bound")
    ClipboardDiagnosticsPolicy.ID_BATTERY -> stringRes("diag_battery_whitelisted")
    ClipboardDiagnosticsPolicy.ID_NOTIFICATIONS -> stringRes("diag_notifications")
    ClipboardDiagnosticsPolicy.ID_RESTRICTED -> stringRes("diag_restricted_settings")
    ClipboardDiagnosticsPolicy.ID_SHIZUKU -> stringRes("diag_shizuku_active")
    else -> id
}

private fun clickAction(
    id: String,
    status: ClipboardCheckStatus,
    onOpenAccessibility: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenAppInfo: () -> Unit
): (() -> Unit)? {
    if (status != ClipboardCheckStatus.MISSING) return null
    return when (id) {
        ClipboardDiagnosticsPolicy.ID_A11Y_SETTING,
        ClipboardDiagnosticsPolicy.ID_A11Y_SYSTEM,
        ClipboardDiagnosticsPolicy.ID_A11Y_BOUND -> onOpenAccessibility
        ClipboardDiagnosticsPolicy.ID_BATTERY -> onRequestBatteryUnrestricted
        ClipboardDiagnosticsPolicy.ID_NOTIFICATIONS -> ({ ClipboardRuntimeDiagnostics.openNotificationSettings() })
        ClipboardDiagnosticsPolicy.ID_RESTRICTED -> onOpenAppInfo
        ClipboardDiagnosticsPolicy.ID_SHIZUKU -> ({ ClipboardRuntimeDiagnostics.activateShizuku() })
        else -> null
    }
}
