package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.update.rememberRequestInstallUnknownAppsPermission

@Composable
private fun localizedUpdateUnit(unit: UpdateCheckUnit): String = when (unit) {
    UpdateCheckUnit.Hours -> stringRes("unit_hours")
    UpdateCheckUnit.Days -> stringRes("unit_days")
    UpdateCheckUnit.Weeks -> stringRes("unit_weeks")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckForUpdatesSettingsPage(
    state: SettingsUiState,
    updateStatus: String?,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUnitSelected: (UpdateCheckUnit) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onWeekAmountSelected: (Int) -> Unit
) {
    val requestInstallUnknownAppsPermission = rememberRequestInstallUnknownAppsPermission()

    SettingsPageShell(
        title = stringRes("check_for_updates"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("enable_check_for_updates"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("check_updates_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.checkForUpdatesEnabled,
                        onCheckedChange = { enabled ->
                            // BAL-safe: only open install-permission Settings from this user gesture.
                            if (enabled) {
                                requestInstallUnknownAppsPermission()
                            }
                            onToggle(enabled)
                        }
                    )
                }
            )
            if (state.checkForUpdatesEnabled) {
                UpdateFrequencyRow(
                    unit = state.checkForUpdatesIntervalUnit,
                    amountText = state.checkForUpdatesAmountText,
                    amount = state.checkForUpdatesIntervalAmount,
                    onUnitSelected = onUnitSelected,
                    onAmountTextChange = onAmountTextChange,
                    onWeekAmountSelected = onWeekAmountSelected
                )
                Text(
                    text = UpdateCheckFrequency.label(
                        state.checkForUpdatesIntervalUnit,
                        state.checkForUpdatesIntervalAmount
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                updateStatus?.let { status ->
                    Text(
                        text = status,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateFrequencyRow(
    unit: UpdateCheckUnit,
    amountText: String,
    amount: Int,
    onUnitSelected: (UpdateCheckUnit) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onWeekAmountSelected: (Int) -> Unit
) {
    var unitExpanded by remember { mutableStateOf(false) }
    var weekExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringRes("check_every"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                TextButton(onClick = { unitExpanded = true }) {
                    Text(localizedUpdateUnit(unit))
                }
                DropdownMenu(
                    expanded = unitExpanded,
                    onDismissRequest = { unitExpanded = false }
                ) {
                    UpdateCheckUnit.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(localizedUpdateUnit(option)) },
                            onClick = {
                                onUnitSelected(option)
                                unitExpanded = false
                            }
                        )
                    }
                }
            }
        }

        when (unit) {
            UpdateCheckUnit.Weeks -> {
                Column(modifier = Modifier.width(112.dp)) {
                    Text(
                        text = stringRes("unit_weeks"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        TextButton(onClick = { weekExpanded = true }) {
                            Text(amount.toString())
                        }
                        DropdownMenu(
                            expanded = weekExpanded,
                            onDismissRequest = { weekExpanded = false }
                        ) {
                            UpdateCheckFrequency.allowedWeekValues().forEach { weeks ->
                                DropdownMenuItem(
                                    text = { Text(weeks.toString()) },
                                    onClick = {
                                        onWeekAmountSelected(weeks)
                                        weekExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            UpdateCheckUnit.Hours -> {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountTextChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    label = { Text(stringRes("range_1_24")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            UpdateCheckUnit.Days -> {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountTextChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    label = { Text(stringRes("range_1_30")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}
