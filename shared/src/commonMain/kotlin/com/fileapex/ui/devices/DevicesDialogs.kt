package com.fileapex.ui.devices

import com.fileapex.data.settings.traits
import com.fileapex.ui.BatteryTerminalOverlay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.ui.devices.DeviceDetailsDialog
import com.fileapex.ui.dialogs.ClipboardTargetConfigDialog

internal data class PendingDelete(
    val deviceId: String,
    val deviceName: String
)

@Composable
internal fun DevicesDialogs(
    state: DevicesUiState,
    viewModel: DevicesViewModel,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    pinText: String,
    onPinTextChange: (String) -> Unit,
    pendingDelete: PendingDelete?,
    onPendingDeleteChange: (PendingDelete?) -> Unit,
    confirmExit: Boolean,
    onConfirmExitChange: (Boolean) -> Unit,
    onExitApp: () -> Unit
) {
    val renameId = state.renameTargetId
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text(stringRes("rename_device")) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { onRenameTextChange(it) },
                    singleLine = true,
                    label = { Text(stringRes("name")) }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRename(renameId, renameText) }) {
                    Text(stringRes("save"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) { Text(stringRes("cancel")) }
            }
        )
    }

    state.pendingPinPairing?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                onPinTextChange("")
                viewModel.cancelPinPairing()
            },
            title = { Text(stringRes("enter_device_pin")) },
            text = {
                Column {
                    Text(
                        text = stringRes("enter_pin_pair", pending.deviceName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { onPinTextChange(it.filter { ch -> ch.isDigit() }.take(8)) },
                        singleLine = true,
                        label = { Text(stringRes("pin")) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinPairing(pinText)
                        onPinTextChange("")
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text(stringRes("pair"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onPinTextChange("")
                        viewModel.cancelPinPairing()
                    }
                ) { Text(stringRes("cancel")) }
            }
        )
    }

    state.pendingPinUnlock?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                onPinTextChange("")
                viewModel.cancelPinUnlock()
            },
            title = { Text(stringRes("enter_device_pin")) },
            text = {
                Column {
                    Text(
                        text = stringRes("enter_pin_browse", pending.displayName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { onPinTextChange(it.filter { ch -> ch.isDigit() }.take(8)) },
                        singleLine = true,
                        label = { Text(stringRes("pin")) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinUnlock(pinText)
                        onPinTextChange("")
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text(stringRes("unlock"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onPinTextChange("")
                        viewModel.cancelPinUnlock()
                    }
                ) { Text(stringRes("cancel")) }
            }
        )
    }

    pendingDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { onPendingDeleteChange(null) },
            title = { Text(stringRes("remove_device")) },
            text = {
                Text(stringRes("remove_device_body", device.deviceName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDevice(device.deviceId)
                        onPendingDeleteChange(null)
                    }
                ) { Text(stringRes("remove")) }
            },
            dismissButton = {
                TextButton(onClick = { onPendingDeleteChange(null) }) { Text(stringRes("cancel")) }
            }
        )
    }

    val detailsOnCard = com.fileapex.data.settings.LocalAppTheme.current.traits.dockHome
    state.deviceDetails?.takeUnless { detailsOnCard }?.let { details ->
        DeviceDetailsDialog(
            details = details,
            onDismiss = viewModel::dismissDeviceDetails
        )
    }

    state.batteryOverlayState?.let { overlay ->
        BatteryTerminalOverlay(
            items = overlay.items,
            logLines = overlay.logLines,
            isComplete = overlay.isComplete,
            onDismiss = viewModel::dismissBatteryOverlay
        )
    }

    if (state.showClipboardConfigDialog) {
        val settings = remember { FileApexServices.settings }
        val currentMode by settings.clipboardShareMode.collectAsState()
        val currentTargets by settings.clipboardTargetDeviceIds.collectAsState()
        ClipboardTargetConfigDialog(
            initialMode = currentMode,
            initialTargetIds = currentTargets,
            peers = state.clipboardConfigPeers,
            onConfirm = { mode, targets ->
                viewModel.confirmClipboardConfig(mode, targets)
            },
            onDismiss = viewModel::dismissClipboardConfigDialog
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { onConfirmExitChange(false) },
            title = { Text(stringRes("exit_fileapex_q")) },
            text = { Text(stringRes("stop_sharing_close")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmExitChange(false)
                        onExitApp()
                    }
                ) { Text(stringRes("exit")) }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmExitChange(false) }) { Text(stringRes("cancel")) }
            }
        )
    }
}
