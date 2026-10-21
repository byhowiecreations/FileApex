package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.PinIdleTimeout
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PinRequiredSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPinChange: (String) -> Unit,
    onIdleTimeoutSelected: (PinIdleTimeout) -> Unit
) {
    var timeoutExpanded by remember { mutableStateOf(false) }

    SettingsPageShell(
        title = stringRes("pin_required"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("require_pin"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("require_pin_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.pinRequiredEnabled,
                        onCheckedChange = onToggle
                    )
                }
            )
            if (state.pinRequiredEnabled) {
                OutlinedTextField(
                    value = state.devicePin,
                    onValueChange = onPinChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringRes("device_pin")) },
                    supportingText = state.pinError?.let { err ->
                        {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    isError = state.pinError != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }

            Text(
                text = stringRes("browse_unlock_idle"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringRes("browse_unlock_idle_desc"),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(onClick = { timeoutExpanded = true }) {
                    Text(localizedPinIdle(state.pinIdleTimeout))
                }
                DropdownMenu(
                    expanded = timeoutExpanded,
                    onDismissRequest = { timeoutExpanded = false }
                ) {
                    PinIdleTimeout.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(localizedPinIdle(option)) },
                            onClick = {
                                onIdleTimeoutSelected(option)
                                timeoutExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
